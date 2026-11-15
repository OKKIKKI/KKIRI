package com.okkikki.kkiri.state

import android.content.Context
import android.util.Log
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.contract.SessionSnapshot
import com.okkikki.kkiri.net.JsonMapper
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 명세 6절 SessionRepository의 목업 구현 (서버 대신 docs/fixtures 재생, 05_DEV_RULES 4절).
 * - fixtures는 app/build.gradle.kts에서 assets로 연결해 둔 것을 읽는다(복사본 아님).
 * - 내 차는 fixtures의 호스트 차(hostCarId = car_A)로 본다.
 * - 보내기 계열(R-20~R-24, R-33, R-34, R-46)은 서버가 없으므로 로그만 남기고 성공을 돌려준다.
 *   실제 구현(net/)에서는 서버 응답대로 성공·실패를 돌려준다.
 * - R-25(음성 클립 받기)는 목업에 파일이 없으므로 실패를 돌려준다(가짜 파일을 만들지 않음).
 * - 이벤트를 받아 session에 반영하는 일은 실제 WebSocket 구현에서 한다. 목업은 스냅샷만 넣는다.
 */
class MockSessionRepository(private val context: Context) : SessionRepository {

    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    override val myCarId: String?
        get() = _session.value?.hostCarId

    /** session_snapshot.json을 읽어 session에 넣는다. 실패하면 로그를 남기고 session은 그대로 둔다 */
    fun loadFixtureSession() {
        try {
            val text = readAsset(SNAPSHOT_FILE)
            _session.value = JsonMapper.session(org.json.JSONObject(text))
            Log.i(TAG, "목업 세션 불러옴: ${_session.value?.sessionId}")
        } catch (e: Exception) {
            Log.e(TAG, "목업 세션을 읽지 못함 ($SNAPSHOT_FILE)", e)
        }
    }

    /** 세션 종료 등으로 세션을 비운다(목업용) */
    fun clearSession() {
        _session.value = null
    }

    /**
     * 디버그용: ws_events.json의 이벤트를 차례로 events에 흘려보낸다(스냅샷 제외).
     * CAR-B가 낭독·음소거 표시 등을 시험할 때 쓴다. 부르는 쪽의 코루틴에서 실행한다.
     */
    suspend fun playFixtureEvents(gapMs: Long = 1_000) {
        val list = try {
            JSONArray(readAsset(EVENTS_FILE))
        } catch (e: Exception) {
            Log.e(TAG, "목업 이벤트를 읽지 못함 ($EVENTS_FILE)", e)
            return
        }
        for (i in 0 until list.length()) {
            val event = try {
                JsonMapper.serverEvent(list.getJSONObject(i))
            } catch (e: Exception) {
                Log.e(TAG, "목업 이벤트 ${i}번을 해석하지 못함", e)
                continue
            }
            if (event is SessionSnapshot) continue
            _events.emit(event)
            Log.i(TAG, "목업 이벤트 보냄: ${event::class.simpleName} seq=${event.seq}")
            delay(gapMs)
        }
    }

    override suspend fun sendBroadcast(text: String, target: String): Result<Unit> =
        mockOk("R-20 sendBroadcast(target=$target, text=$text)")

    override suspend fun sendVoiceClip(file: File, durationMs: Long, target: String): Result<Unit> =
        mockOk("R-21 sendVoiceClip(target=$target, durationMs=$durationMs, file=${file.name})")

    override suspend fun fetchVoiceClip(clipUrl: String): Result<File> {
        Log.w(TAG, "목업: R-25 fetchVoiceClip($clipUrl) — 목업에는 음성 파일이 없음")
        return Result.failure(UnsupportedOperationException("목업에는 음성 클립 파일이 없습니다: $clipUrl"))
    }

    override suspend fun setMute(targetCarId: String, muted: Boolean): Result<Unit> =
        mockOk("R-22 setMute(targetCarId=$targetCarId, muted=$muted)")

    override suspend fun heldMessages(): Result<List<Message>> {
        Log.i(TAG, "목업: R-23 heldMessages() → 빈 목록")
        return Result.success(emptyList())
    }

    override suspend fun ackHeld(ids: List<String>): Result<Unit> = mockOk("R-24 ackHeld($ids)")

    override suspend fun undoWaypoint(): Result<Unit> = mockOk("R-33 undoWaypoint()")

    override suspend fun respondProposal(proposalId: String, accept: Boolean): Result<Unit> =
        mockOk("R-34 respondProposal(proposalId=$proposalId, accept=$accept)")

    override suspend fun setMusicLock(locked: Boolean): Result<Unit> = mockOk("R-46 setMusicLock(locked=$locked)")

    private fun mockOk(call: String): Result<Unit> {
        Log.i(TAG, "목업: $call → 성공(서버 없음)")
        return Result.success(Unit)
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        const val TAG = "KKIRI-MockSession"
        const val SNAPSHOT_FILE = "session_snapshot.json"
        const val EVENTS_FILE = "ws_events.json"
    }
}

/**
 * 앱 전체에서 하나만 쓰는 SessionRepository. 화면이 다시 만들어져도 유지된다.
 * 실제 구현(net/)이 생기면 빌드 설정 하나로 이 자리를 바꾼다(05_DEV_RULES 4절).
 */
object SessionRepositories {
    @Volatile private var mockInstance: MockSessionRepository? = null

    fun mock(context: Context): MockSessionRepository =
        mockInstance ?: synchronized(this) {
            mockInstance ?: MockSessionRepository(context.applicationContext).also { mockInstance = it }
        }
}
