package com.okkikki.kkiri.voice

import android.content.Context
import android.util.Log
import com.okkikki.kkiri.BuildConfig
import com.okkikki.kkiri.contract.DriveStateProvider
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.MessageCreated
import com.okkikki.kkiri.contract.MusicQueueAdded
import com.okkikki.kkiri.contract.MuteChanged
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.SessionEnded
import com.okkikki.kkiri.contract.SessionRepository
import com.okkikki.kkiri.contract.SessionSnapshot
import com.okkikki.kkiri.contract.SpeakerInfo
import com.okkikki.kkiri.contract.VoiceCommand
import com.okkikki.kkiri.contract.VoiceCommandSource
import com.okkikki.kkiri.contract.VoiceHub
import com.okkikki.kkiri.contract.WaypointAdded
import com.okkikki.kkiri.contract.WaypointProposalResolved
import com.okkikki.kkiri.contract.WaypointProposed
import com.okkikki.kkiri.contract.WaypointRemoved
import com.okkikki.kkiri.navi.NaviTaskQueue
import com.okkikki.kkiri.navi.WaypointSync
import com.okkikki.kkiri.voice.debug.DebugTestInputs
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * [VoiceHub] 구현(CAR-B, D-21). 음성 쪽 전체의 입구이며 CAR-A는 이것만 안다.
 *
 * 생성자 `VoiceHubImpl(context)`는 CAR-A가 참조하는 유일한 contract 밖 이름이다(유지. 바꿀 때는 CAR-A와 협의).
 * applicationContext만 보관한다. 어댑터·낭독 출력은 [VoiceComponents] 한 곳에서 고른다.
 *
 * 수명
 * - start: 세션 전용 scope(SupervisorJob)를 만든다. 이미 실행 중이면 이전 scope를 취소하고 output.reset 후 다시 시작한다.
 * - stop: scope 취소 + output.reset. start 전에 불러도 안전하다.
 * - 밖에 내보내는 nowSpeaking·heldCount는 이 클래스의 MutableStateFlow이며 output 값을 복사한다(재시작해도 구독 유지).
 * - repo.events, drive, 음성 명령은 각각 별도 코루틴에서 수집한다(다운로드·재생·녹음이 수신을 막지 않게).
 * - 세션 스냅샷 동기화는 repo.events 구독이 성립한 뒤에 시작한다(그 사이 이벤트를 놓치지 않게. 겹친 이벤트는
 *   경유지·제안 쪽이 id로 중복을 거른다).
 * - 받은 음성 클립 파일(R-25·디버그 목업)은 실행 단위가 기억해 두었다가 stop·재시작·세션 종료 때 한꺼번에 지운다.
 *   재생 직후에는 지우지 않는다(SpeechQueue가 끊긴 클립을 같은 파일로 다시 재생, 팀원 2026-11-12).
 * - SessionEnded는 stop과 같이 실행 전체를 취소한다. 다음 ACTIVE 세션의 start는 CAR-A VoiceHubs.sync가 맡는다.
 * - PAUSED에서는 CAR-A가 기존 실행을 유지하며, 재연결 스냅샷은 목록을 다시 맞춘다.
 */
class VoiceHubImpl internal constructor(
    private val components: VoiceComponents,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val commandSource: VoiceCommandSource = VoiceCommandSources.active,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** 실패 로그(수준·예외 보존) */
    private val warn: (String, Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) : VoiceHub {

    constructor(context: Context) : this(VoiceComponents(context.applicationContext))
    private val output: ConfirmationSpeechOutput = components.speechOutput

    private val _nowSpeaking = MutableStateFlow<SpeakerInfo?>(null)
    override val nowSpeaking: StateFlow<SpeakerInfo?> = _nowSpeaking.asStateFlow()

    private val _heldCount = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val heldCount: StateFlow<Map<String, Int>> = _heldCount.asStateFlow()

    private val _currentDestination = MutableStateFlow<Place?>(null)
    /** 지금 내비 목적지. 모르면 null. 목업 내비(D-08 OPEN)에서는 "목업이 받은 값"이다 */
    override val currentDestination: StateFlow<Place?> = _currentDestination.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _, e -> warn("처리 중 예외", e) }

    /** 세션과 무관한 작업(applyDestination 등)용. VoiceHubImpl 수명 동안 유지 */
    private val hubScope = CoroutineScope(SupervisorJob() + dispatcher + errorHandler)

    /** start~stop 사이의 실행 단위 */
    private inner class Running(
        val scope: CoroutineScope,
        val repo: SessionRepository,
        val drive: DriveStateProvider,
    ) {
        /** 이 실행 단위가 현재일 때만 공유 출력에 넘기는 창구(stop·재시작 뒤 이전 실행의 안내 차단) */
        val out = RunScopedSpeechOutput(output, lock) { running === this }
        val gate = ConfirmGate(components.confirmConfig)
        val commands = CommandHandler(
            repo = repo,
            output = out,
            recorder = components.recorder,
            gate = gate,
            proposals = { accept -> respondProposal(this, accept) },
            targetLabel = { target -> targetLabel(this, target) },
            maxClipMs = MAX_CLIP_MS,
            log = log,
            warn = warn,
        )
        val waypoints = WaypointSync(
            components.navi, announce = { out.announce(it) }, myCarId = { repo.myCarId }, log = log, warn = warn,
        )
        val proposals = ProposalTracker(
            repo,
            log = log,
            announce = { out.announce(it) },
            // 질문 낭독이 실제로 시작될 때 응답 대상이 된다(SpeechOutput.announce onStart, 2026-11-12 합의)
            ask = { text, onStart -> out.announce(text, false, onStart) },
            warn = warn,
        )
        val router = MessageRouter(
            output = out,
            fetchClip = { m, url -> fetchClip(this, m, url) },
            carNameOf = { m -> carNameOf(this, m) },
            log = log,
            warn = warn,
        )

        /** 이 실행 단위가 받은 음성 클립 파일(VoiceHubImpl lock으로 보호). stop 때 지운다 */
        private val clipFiles = ArrayList<File>()

        /** 지금 실행 단위면 파일을 기억하고 true. 이미 끝난 실행이면 기억하지 않고 false(부른 쪽이 지운다) */
        fun trackClip(file: File): Boolean = synchronized(lock) {
            if (running !== this) return@synchronized false
            clipFiles += file
            true
        }

        /** 기억한 파일 목록을 넘기고 비운다(VoiceHubImpl lock 안에서 부른다) */
        fun takeClipFilesLocked(): List<File> = clipFiles.toList().also { clipFiles.clear() }

        /** 경유지 반영 작업 대기열: 이벤트 순서를 지키고, 작업 하나가 예외를 던져도 다음 작업을 계속한다 */
        private val naviQueue = NaviTaskQueue { e -> warn("경유지 작업 실패(다음 작업은 계속)", e) }

        fun startNaviWorker() {
            naviQueue.start(scope)
        }

        fun enqueueNavi(task: suspend () -> Unit) {
            naviQueue.enqueue(task)
        }

        /** 스냅샷으로 경유지·제안을 다시 맞춘다(안내 없음) */
        fun resync(session: Session) {
            proposals.resync(session)
            enqueueNavi { waypoints.resync(session) }
        }
    }

    private val lock = Any()
    private var running: Running? = null

    override fun start(repo: SessionRepository, drive: DriveStateProvider) {
        var oldFiles: List<File> = emptyList()
        synchronized(lock) {
            running?.let {
                log("중복 start: 이전 실행 취소 후 다시 시작")
                it.gate.cancel()
                it.scope.cancel()
                oldFiles = it.takeClipFilesLocked()
            }
            output.reset()
            _nowSpeaking.value = null
            _heldCount.value = emptyMap()
            val r = Running(CoroutineScope(SupervisorJob() + dispatcher + errorHandler), repo, drive)
            running = r
            output.start(r.scope, speechContext(r))

            r.scope.launch {
                output.nowSpeaking.collect { value -> synchronized(lock) { if (running === r) _nowSpeaking.value = value } }
            }
            r.scope.launch {
                output.heldCount.collect { value -> synchronized(lock) { if (running === r) _heldCount.value = value } }
            }
            r.startNaviWorker()
            r.scope.launch {
                repo.events
                    .onSubscription {
                        // 이벤트 구독이 성립한 뒤에 스냅샷 동기화를 시작한다(그 사이 이벤트를 놓치지 않게).
                        // 세션이 생기거나 바뀌면(처음 포함) 경유지·제안을 스냅샷으로 맞춘다. 같은 세션의 반영은 이벤트로 한다
                        r.scope.launch {
                            repo.session.map { it?.sessionId }.distinctUntilChanged().collect { id ->
                                if (id != null) repo.session.value?.let { r.resync(it) }
                            }
                        }
                    }
                    .collect { onEvent(r, it) }
            }
            r.scope.launch { drive.driveState.collect { log("driveState=$it") } }
            r.scope.launch { commandSource.commands.collect { cmd -> r.scope.launch { onCommand(r, cmd) } } }
            if (BuildConfig.DEBUG) {
                // 동작 확인용 입력(fixtures 아님, voice/debug/DebugTestInputs)
                DebugTestInputs.bind(session = { repo.session.value }, myCarId = { repo.myCarId })
                r.scope.launch { DebugTestInputs.events.collect { onEvent(r, it) } }
            }
            log("start: myCarId=${repo.myCarId}, session=${repo.session.value?.sessionId}")
        }
        deleteClipFiles(oldFiles)
    }

    override fun stop() {
        val files = synchronized(lock) {
            val r = running
            r?.gate?.cancel()
            r?.scope?.cancel()
            running = null
            output.reset() // 대기열을 먼저 비운 뒤 파일을 지운다
            _nowSpeaking.value = null
            _heldCount.value = emptyMap()
            r?.takeClipFilesLocked().orEmpty()
        }
        deleteClipFiles(files)
        log("stop")
    }

    private fun deleteClipFiles(files: List<File>) {
        for (f in files) if (f.exists() && !f.delete()) warn("받은 음성 클립 삭제 실패: ${f.name}", null)
        if (files.isNotEmpty()) log("받은 음성 클립 ${files.size}개 정리")
    }

    override fun startPtt(target: String) {
        val r = synchronized(lock) { running }
        if (r == null) {
            log("startPtt(target=$target): start 전이라 무시")
            return
        }
        r.scope.launch { r.commands.ptt(target) }
    }

    /** NAV-01·SES-03. 세션 시작 전(합류 화면)에도 부를 수 있으므로 hubScope에서 실행한다 */
    override fun applyDestination(place: Place) {
        hubScope.launch {
            components.navi.setDestination(place)
                .onSuccess { _currentDestination.value = place }
                .onFailure {
                    warn("setDestination 실패: ${place.placeId}", it)
                    synchronized(lock) { running }?.out?.announce(VoiceTexts.NAVI_FAILED)
                }
        }
    }

    private fun speechContext(r: Running) = SpeechContext(
        tts = components.tts,
        clipPlayer = components.clipPlayer,
        drive = r.drive,
        myCarId = { r.repo.myCarId },
        isHostCar = {
            val s = r.repo.session.value
            s != null && s.hostCarId == r.repo.myCarId
        },
        carName = { carId -> r.repo.session.value?.cars?.firstOrNull { it.carId == carId }?.displayName },
        config = SpeechConfig(minGapMs = BuildConfig.TTS_MIN_GAP_SEC * 1000L),
    )

    /**
     * R-25. 디버그 빌드에서 디버그 입력("dbg_" id)이면 서버 대신 녹음 목업 파일로 대신한다.
     * 받은 파일은 실행 단위에 기억해 두었다가 stop 때 지운다. 이미 끝난 실행이 늦게 받은 파일은 바로 지우고 실패로 돌려준다.
     */
    private suspend fun fetchClip(r: Running, m: Message, url: String): Result<File> {
        val result = if (BuildConfig.DEBUG && DebugTestInputs.isDebugMessage(m.messageId)) {
            log("디버그 입력 클립: 녹음 목업 파일 사용(${m.messageId})")
            components.recorder.record(MAX_CLIP_MS).map { it.file }
        } else {
            r.repo.fetchVoiceClip(url)
        }
        val file = result.getOrNull() ?: return result
        if (r.trackClip(file)) return result
        deleteClipFiles(listOf(file))
        return Result.failure(IllegalStateException("이미 끝난 실행의 클립: ${m.messageId}"))
    }

    private fun carNameOf(r: Running, m: Message): String {
        return r.repo.session.value?.cars?.firstOrNull { it.carId == m.sender.carId }?.displayName ?: m.sender.name
    }

    /** 이벤트 수신 코루틴. 오래 걸리는 일(다운로드 등)은 r.scope에서 따로 띄워 수신을 막지 않는다 */
    private fun onEvent(r: Running, event: ServerEvent) = synchronized(lock) {
        if (running !== r) return@synchronized
        when (event) {
            is MessageCreated -> r.router.route(r.scope, event)
            is WaypointAdded -> r.enqueueNavi { r.waypoints.onAdded(event) }
            is WaypointRemoved -> r.enqueueNavi { r.waypoints.onRemoved(event) }
            is WaypointProposed -> r.proposals.onProposed(event)
            is WaypointProposalResolved -> r.proposals.onResolved(event)
            // 팀원 담당 이벤트(낭독 출력이 필요한 것만 쓴다)
            is MusicQueueAdded, is MuteChanged -> r.out.onServerEvent(event)
            is SessionSnapshot -> {
                r.out.onServerEvent(event)
                r.resync(event.session)
            }
            is SessionEnded -> {
                r.out.onServerEvent(event)
                // 이 실행 단위 전체를 끝내 다운로드·녹음·질문도 취소한다.
                // reset만 하고 같은 scope를 재사용하면 늦은 결과가 새 출력으로 들어온다.
                // CAR-A VoiceHubs.sync가 다음 ACTIVE 세션에서 다시 start한다.
                if (running === r) stop()
            }
            else -> Unit
        }
    }

    /** 명령마다 r.scope에서 별도 코루틴으로 실행된다 */
    private suspend fun onCommand(r: Running, command: VoiceCommand) {
        r.commands.handle(command)
    }

    /** 되묻기 문구용 대상 이름: "ALL" → "모두"(명세 5.1), carId → 차 표시 이름(세션에 없으면 받은 값 그대로) */
    private fun targetLabel(r: Running, target: String): String =
        if (target == "ALL") VoiceTexts.ALL_LABEL
        else r.repo.session.value?.cars?.firstOrNull { it.carId == target }?.displayName ?: target

    /** 제안 응답(R-34). 응답할 제안이 없으면 false */
    private suspend fun respondProposal(r: Running, accept: Boolean): Boolean = r.proposals.respond(accept)

    private companion object {
        const val TAG = "KKIRI.VoiceHub"
        /** D-12 음성 클립 최대 15초 */
        const val MAX_CLIP_MS = 15_000L
    }
}
