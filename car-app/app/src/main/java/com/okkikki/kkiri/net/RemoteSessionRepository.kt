package com.okkikki.kkiri.net

import android.content.Context
import android.util.Log
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.contract.CarJoined
import com.okkikki.kkiri.contract.CarLookChanged
import com.okkikki.kkiri.contract.CarStatusChanged
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.MessageCreated
import com.okkikki.kkiri.contract.MusicLockChanged
import com.okkikki.kkiri.contract.MusicNowPlaying
import com.okkikki.kkiri.contract.MusicQueueAdded
import com.okkikki.kkiri.contract.MuteChanged
import com.okkikki.kkiri.contract.ParticipantJoined
import com.okkikki.kkiri.contract.ParticipantLeft
import com.okkikki.kkiri.contract.ProposalStatus
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SessionEnded
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.contract.SessionSnapshot
import com.okkikki.kkiri.contract.SessionState
import com.okkikki.kkiri.contract.SessionStateChanged
import com.okkikki.kkiri.contract.Unknown
import com.okkikki.kkiri.contract.WaypointAdded
import com.okkikki.kkiri.contract.WaypointProposalResolved
import com.okkikki.kkiri.contract.WaypointProposed
import com.okkikki.kkiri.contract.WaypointRemoved
import com.okkikki.kkiri.contract.You
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * 실제 서버용 SessionRepository (명세 6절, 4절 WebSocket, D-22 OkHttp).
 *
 * - [open]으로 세션을 넣으면 WebSocket(GET {KKIRI_WS_BASE}/v1/ws?sessionId=&token=)에 붙는다.
 *   session.snapshot이 오면 세션을 통째로 바꾸고, 4.3 이벤트는 session에 반영한 뒤 [events]로도 내보낸다.
 * - 클라이언트 → 서버는 {"type":"ping"}만 30초마다(4.1). 상태 변경은 전부 REST.
 * - 끊기면 1·2·4·8·10초…(최대 10초) 간격으로 다시 붙는다. 다시 붙으면 서버가 스냅샷을 다시 준다.
 *   WebSocket이 401로 거절되면 R-01로 토큰을 새로 받고 다시 붙는다(명세 5절-1).
 * - seq 빈틈은 오류가 아니다(4.2). 다른 세션의 이벤트와 모르는 이벤트는 무시한다.
 * - session.ended를 받으면 상태를 ENDED로 바꾸고 다시 붙지 않는다.
 * - CAR-B용 함수(R-20~R-25, R-33, R-34, R-46)는 지금 세션에 대해 부른다. 세션이 없으면 실패.
 */
class RemoteSessionRepository(
    context: Context,
    private val api: ApiClient,
    private val wsBase: String = BuildConfig.KKIRI_WS_BASE,
) : SessionRepository {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** 스냅샷의 you(서버가 본 나) */
    @Volatile private var you: You? = null
    override val myCarId: String? get() = you?.carId ?: api.carId

    // ── 연결 관리 ───────────────────────────────────────────

    /** 지금 붙어 있어야 할 세션. null이면 다시 붙지 않는다 */
    @Volatile private var target: String? = null
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    /** REST로 받은 세션을 넣고 WebSocket에 붙는다(R-02·R-04·R-05·R-07 다음에 부름) */
    @Synchronized
    fun open(s: Session) {
        _session.value = s
        if (target == s.sessionId && socket != null) return
        closeSocket()
        attempt = 0
        target = s.sessionId
        connect(s.sessionId)
    }

    /** 연결을 끊고 세션을 비운다(종료 후·화면에서 나갈 때) */
    @Synchronized
    fun close() {
        target = null
        closeSocket()
        you = null
        _session.value = null
    }

    private fun closeSocket() {
        reconnectJob?.cancel(); reconnectJob = null
        pingJob?.cancel(); pingJob = null
        socket?.close(1000, null); socket = null
    }

    private fun connect(sessionId: String) {
        val token = api.carToken
        if (token == null) {
            scheduleReconnect(sessionId, reRegister = true)
            return
        }
        val url = "${wsBase.trimEnd('/')}/v1/ws?sessionId=${enc(sessionId)}&token=${enc(token)}"
        Log.i(TAG, "WebSocket 연결 시도 session=$sessionId (시도 ${attempt + 1})")
        socket = api.http.newWebSocket(Request.Builder().url(url).build(), Listener(sessionId))
    }

    private inner class Listener(private val sessionId: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket 연결됨 session=$sessionId")
            attempt = 0
            pingJob?.cancel()
            pingJob = scope.launch {
                while (isActive) {
                    delay(PING_MS)
                    webSocket.send("""{"type":"ping"}""")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 닫힘 code=$code reason=$reason")
            pingJob?.cancel()
            if (target == sessionId && _session.value?.state != SessionState.ENDED) scheduleReconnect(sessionId, false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket 실패 http=${response?.code}: ${t.message}")
            pingJob?.cancel()
            if (target == sessionId) scheduleReconnect(sessionId, reRegister = response?.code == 401)
        }
    }

    @Synchronized
    private fun scheduleReconnect(sessionId: String, reRegister: Boolean) {
        if (reconnectJob?.isActive == true) return
        val wait = minOf(1_000L shl minOf(attempt, 4), MAX_BACKOFF_MS)
        attempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (reRegister) api.registerCar().onFailure { Log.w(TAG, "재연결 전 R-01 실패: ${it.message}") }
            synchronized(this@RemoteSessionRepository) {
                if (target == sessionId) connect(sessionId)
            }
        }
    }

    // ── 이벤트 반영 (4.3) ───────────────────────────────────

    private fun handle(text: String) {
        val event = try {
            JsonMapper.serverEvent(text)
        } catch (e: Exception) {
            Log.e(TAG, "이벤트를 해석하지 못함: ${text.take(200)}", e)
            return
        }
        if (event.sessionId != target) return
        if (event is SessionSnapshot) you = event.you
        val current = _session.value
        _session.value = if (event is SessionSnapshot) event.session else current?.let { apply(it, event) }
        if (event is SessionEnded) target = null
        _events.tryEmit(event)
    }

    private fun apply(s: Session, e: ServerEvent): Session = when (e) {
        is SessionSnapshot -> e.session
        is SessionStateChanged -> s.copy(state = e.state, pausedAt = e.pausedAt, expiresAt = e.expiresAt)
        is SessionEnded -> s.copy(state = SessionState.ENDED)
        is ParticipantJoined -> s.copy(participants = s.participants.filter { it.participantId != e.participant.participantId } + e.participant)
        is ParticipantLeft -> s.copy(participants = s.participants.filter { it.participantId != e.participant.participantId })
        is CarJoined -> s.copy(cars = s.cars.filter { it.carId != e.car.carId } + e.car)
        is CarStatusChanged -> s.copy(cars = s.cars.map {
            if (it.carId == e.carId) it.copy(status = e.status, driveState = e.driveState) else it
        })
        is CarLookChanged -> s.copy(cars = s.cars.map { if (it.carId == e.carId) it.copy(look = e.look) else it })
        is MuteChanged -> s.copy(mutes = e.mutes)
        is WaypointAdded -> s.copy(waypoints = s.waypoints.filter { it.waypointId != e.waypoint.waypointId } + e.waypoint)
        is WaypointRemoved -> s.copy(waypoints = s.waypoints.filter { it.waypointId != e.waypointId })
        is WaypointProposed -> s.copy(proposals = s.proposals.filter { it.proposalId != e.proposal.proposalId } + e.proposal)
        is WaypointProposalResolved -> s.copy(proposals = s.proposals.mapNotNull { p ->
            if (p.proposalId != e.proposalId) return@mapNotNull p
            val updated = p.copy(responses = p.responses.map { r -> if (r.carId == e.carId) r.copy(status = e.status) else r })
            // session.proposals는 PENDING만(명세 2절): 응답이 모두 끝난 제안은 뺀다
            if (updated.responses.any { it.status == ProposalStatus.PENDING }) updated else null
        })
        is MusicNowPlaying -> s.copy(music = e.music)
        is MusicLockChanged -> s.copy(music = s.music.copy(locked = e.locked))
        is MessageCreated, is MusicQueueAdded, is Unknown -> s
    }

    // ── CAR-B용 (명세 6절) ─────────────────────────────────

    override suspend fun sendBroadcast(text: String, target: String): Result<Unit> = withSession { id ->
        val body = JSONObject().put("kind", "BROADCAST").put("text", text).put("urgent", false).put("target", target)
        api.request("POST", "/v1/sessions/$id/messages", body).getOrThrow()
        Unit
    }

    override suspend fun sendVoiceClip(file: File, durationMs: Long, target: String): Result<Unit> = withSession { id ->
        api.uploadVoiceClip(id, file, durationMs, target).getOrThrow()
        Unit
    }

    override suspend fun fetchVoiceClip(clipUrl: String): Result<File> {
        val dest = File(appContext.cacheDir, "clip_${clipUrl.hashCode().toUInt()}.m4a")
        return api.download(clipUrl, dest)
    }

    override suspend fun setMute(targetCarId: String, muted: Boolean): Result<Unit> = withSession { id ->
        val body = JSONObject().put("targetCarId", targetCarId).put("muted", muted)
        val text = api.request("PUT", "/v1/sessions/$id/mutes", body).getOrThrow()
        val arr = JSONObject(text!!).getJSONArray("mutes")
        val mutes = (0 until arr.length()).map { JsonMapper.mute(arr.getJSONObject(it)) }
        _session.value = _session.value?.copy(mutes = mutes)
    }

    override suspend fun heldMessages(): Result<List<Message>> = withSession { id ->
        val text = api.request("GET", "/v1/sessions/$id/held-messages").getOrThrow()
        val arr: JSONArray = JSONObject(text!!).getJSONArray("messages")
        (0 until arr.length()).map { JsonMapper.message(arr.getJSONObject(it)) }
    }

    override suspend fun ackHeld(ids: List<String>): Result<Unit> = withSession { id ->
        api.request("POST", "/v1/sessions/$id/held-messages/ack", JSONObject().put("messageIds", JSONArray(ids))).getOrThrow()
        Unit
    }

    override suspend fun undoWaypoint(): Result<Unit> = withSession { id ->
        api.request("POST", "/v1/sessions/$id/waypoints/undo").getOrThrow()
        Unit
    }

    override suspend fun respondProposal(proposalId: String, accept: Boolean): Result<Unit> = withSession { id ->
        api.request("POST", "/v1/sessions/$id/proposals/$proposalId/respond", JSONObject().put("accept", accept)).getOrThrow()
        Unit
    }

    override suspend fun setMusicLock(locked: Boolean): Result<Unit> = withSession { id ->
        api.request("PUT", "/v1/sessions/$id/music/lock", JSONObject().put("locked", locked)).getOrThrow()
        Unit
    }

    private suspend fun <T> withSession(block: suspend (sessionId: String) -> T): Result<T> = runCatching {
        val id = _session.value?.sessionId ?: throw IllegalStateException("세션이 없어요")
        block(id)
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private companion object {
        const val TAG = "KKIRI-Remote"
        const val PING_MS = 30_000L
        const val MAX_BACKOFF_MS = 10_000L
    }
}
