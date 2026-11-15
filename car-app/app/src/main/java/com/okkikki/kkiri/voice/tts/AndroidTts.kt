package com.okkikki.kkiri.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.okkikki.kkiri.contract.TtsAdapter
import com.okkikki.kkiri.voice.StartGate
import com.okkikki.kkiri.voice.audio.FocusChange
import com.okkikki.kkiri.voice.audio.SpeechFocus
import com.okkikki.kkiri.voice.audio.VoicePreroll
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android 기본 TTS(android.speech.tts.TextToSpeech)로 읽는 [TtsAdapter].
 *
 * **D-24로 사용 승인**(한국어). 실기기 동작은 별도 검증. Pleos SDK의 TTS가 아니다.
 * - 한국어 음성이 없거나(setLanguage 결과 MISSING_DATA·NOT_SUPPORTED) 엔진 초기화가 실패하면 **엔진으로 읽지 않고**
 *   [fallback](기본 [LogTts])으로 대신한다. 한국어 미지원 상태에서 엔진 기본 언어로 읽지 않는다.
 * - fallback으로 읽는 동안에도 아래 정지·취소 보장은 같다(stop → 정상 반환, 코루틴 취소 → 예외 전파).
 *
 * 정지·취소 보장(팀원 SpeechQueue T10과 합의한 동작):
 * - [stop]: 엔진을 멈추고(TextToSpeech.stop) 진행 중인 모든 speak가 **정상 반환**한다.
 *   엔진 준비(초기화) 전에 stop되면 읽지 않고 반환한다. speak에 들어온 뒤 읽기 요청 전에 stop이 와도 읽지 않는다.
 * - 시작·정지 경합: speak는 들어올 때 [StartGate] 표를 받는다. **대기 등록**과 **읽기 요청** 직전에 각각 같은 lock 안에서
 *   표가 유효한지 본다. stop은 그 lock 안에서 정지 세대를 올리고 엔진을 멈추고 등록된 대기를 모두 꺼낸다.
 *   - 표 발급 뒤 등록 전에 stop이 오면: 등록하지 않고, 엔진 준비도 기다리지 않고 바로 정상 반환한다.
 *   - 등록 뒤에 stop이 오면: stop이 그 대기를 찾아 해제하므로 speak가 정상 반환한다(엔진 준비 전이어도).
 *   대기 해제(deferred complete)는 lock 밖에서 하지만, 시작 여부는 세대 번호로만 판단하므로 완료 신호가 늦게 전달돼도
 *   stop 뒤에 읽기를 시작하지 않는다. 잠금 순서는 항상 gate → waiting lock이다.
 * - speak를 부른 코루틴이 취소되면, 그 문장이 엔진에 요청된 채 아직 끝나지 않았을 때 **엔진을 멈추고**
 *   `CancellationException`을 그대로 던진다. TextToSpeech.stop은 엔진 전체를 멈추므로 동시에 한 문장만 읽는 사용
 *   (SpeechOutput이 한 번에 하나씩 읽음)을 전제로 한다.
 * - 정상 종료는 onDone. 요청 실패·onError·외부 onStop은 예외로 알린다(확인 질문은 ABORTED).
 *   직접 stop은 정상 반환하고, 초기화 실패는 승인된 fallback으로 읽는다.
 * - 다 쓰면 [shutdown]을 부른다.
 *
 * 엔진 초기화 제한 시간([initTimeoutMs], null이면 제한 없음 = 이전 동작):
 * - 제한 시간은 **엔진 준비(ready)를 기다리는 구간에만** 건다. 준비된 뒤의 실제 낭독 시간에는 걸지 않는다(정상 낭독을 끊지 않음).
 * - 준비 신호가 제한 시간 안에 오지 않으면 그 문장은 [fallback]으로 읽는다.
 * - **전환 정책: 한 번 시간 초과가 나면 이 인스턴스는 수명 동안 엔진을 쓰지 않고 fallback으로 읽는다.**
 *   이후 문장마다 같은 제한 시간을 다시 기다리지 않고, 늦게 성공 신호가 와도 엔진으로 바꾸지 않는다
 *   (이미 fallback으로 읽은 문장을 엔진으로 다시 읽지 않음). 동시에 준비를 기다리던 다른 문장도 바로 fallback으로 간다.
 * - 초기화 실패(ready=false)·한국어 없음은 기존처럼 제한 시간과 무관하게 바로 fallback.
 * - 구분: stop(대기 해제)은 정상 반환하고 fallback을 시작하지 않는다. 호출 코루틴 취소는 `CancellationException`을
 *   그대로 던지고 시간 초과로 기록하지 않는다(정상 엔진을 실패 상태로 바꾸지 않음). 공유 `engine.ready`는 취소하지 않는다.
 *
 * 오디오 용도·포커스([focus]가 있을 때, 2026-11-12 CAR-B 지시 #3 — 클립 MediaPlayerClipPlayer와 같은 규칙):
 * - 엔진으로 **실제로 읽을 때만** 문장마다 포커스를 요청한다(대체 TTS(무음 LogTts)로 읽을 때는 요청하지 않는다).
 *   엔진의 오디오 속성도 클립과 같다(공개 생성자의 audioAttributes).
 * - 거절·영구 상실은 예외(SpeechQueue: 그 항목 버림 / 확인 질문: ABORTED).
 * - 일시 상실(duck 포함)은 엔진을 멈추고 gain을 기다렸다가 **그 문장을 처음부터 다시** 읽는다(TTS는 중간 재개가 없음).
 *   [focusSuspendLimitMs] 넘게 gain이 없으면 예외. 기다리는 중 [stop]이면 정상 반환, 호출 취소면 예외 전파.
 * - 문장이 끝나면(정상·실패·취소 모두) 포커스를 반납한다.
 * - 실제 차량 포커스 동작·엔진 프로세스가 이 속성을 따르는지는 기기 확인 전이다.
 */
class AndroidTts internal constructor(
    private val engine: TtsEngine,
    private val log: (String) -> Unit,
    /** 한국어 음성이 없거나 엔진이 없을 때 대신 읽는 어댑터(D-24: LogTts) */
    private val fallback: TtsAdapter,
    /** 엔진 초기화를 기다리는 최대 시간(ms). null = 제한 없음. 값은 [com.okkikki.kkiri.voice.VoiceComponents] 한곳에서 주입 */
    private val initTimeoutMs: Long? = null,
    /**
     * JVM 검증 전용 경계: 표 발급 직후·대기 등록 직전에 불린다. 실제 사용(공개 생성자)에서는 아무것도 하지 않는다.
     * "표 발급 → stop → 대기 등록" 순서를 결정적으로 재현하려고 둔다(다른 방법으로는 이 틈에 stop을 끼울 수 없음).
     */
    private val onTicketIssued: () -> Unit = {},
    /** 엔진으로 읽을 때 문장마다 요청할 오디오 포커스. null이면 요청하지 않는다(이전 동작) */
    private val focus: SpeechFocus? = null,
    /** 포커스 일시 상실을 기다리는 최대 시간(ms). [focus]가 있으면 필수. 값은 VoiceComponents 한곳에서 주입 */
    private val focusSuspendLimitMs: Long? = null,
) : TtsAdapter {

    /** 앱 구성용(VoiceComponents). focus 경계 타입이 CAR-B 내부라 internal */
    internal constructor(
        context: Context,
        fallback: TtsAdapter = LogTts(),
        initTimeoutMs: Long? = null,
        audioAttributes: AudioAttributes? = null,
        focus: SpeechFocus? = null,
        focusSuspendLimitMs: Long? = null,
    ) : this(
        TextToSpeechEngine(context, audioAttributes), { Log.i(TAG, it) }, fallback, initTimeoutMs,
        focus = focus, focusSuspendLimitMs = focusSuspendLimitMs,
    )

    init {
        require(initTimeoutMs == null || initTimeoutMs > 0) { "initTimeoutMs must be > 0: $initTimeoutMs" }
        require(focus == null || (focusSuspendLimitMs != null && focusSuspendLimitMs > 0)) {
            "focusSuspendLimitMs must be > 0 when focus is set: $focusSuspendLimitMs"
        }
    }

    private enum class InitWait { READY, FAILED, STOPPED, TIMED_OUT }
    private enum class AttemptEnd { FINISHED, TRANSIENT, LOST }
    private enum class GainWait { GAIN, LOST, STOPPED }

    /** 초기화 시간 초과가 한 번이라도 났는지(인스턴스 수명 동안 유지) */
    @Volatile private var initTimedOut = false
    /** 시간 초과가 나면 complete: 동시에 준비를 기다리던 다른 문장도 바로 깨운다 */
    private val initTimedOutSignal = CompletableDeferred<Unit>()

    /** 시작·정지 판정(동시에 여러 문장 요청 가능 → exclusive=false) */
    private val gate = StartGate(exclusive = false)

    /** waiting 보호용. gate의 lock 안에서 잡을 수 있지만 그 반대(이 lock 안에서 gate)는 하지 않는다 */
    private val lock = Any()
    /** utteranceId → 끝나면 complete. stop·콜백이 complete한다(대기 해제 신호일 뿐, 시작 판정에 쓰지 않음) */
    private val waiting = HashMap<String, CompletableDeferred<Unit>>()
    /** fallback으로 읽는 작업들. stop이 gate lock 안에서 꺼내 취소한다(lock으로 보호) */
    private val fallbackJobs = HashSet<Job>()
    @Volatile private var fallbackLogged = false

    init {
        engine.setListener { id -> finish(id) }
        engine.setErrorListener { id ->
            synchronized(lock) { waiting.remove(id) }
                ?.completeExceptionally(IllegalStateException("TTS 낭독 실패·외부 중단: $id"))
        }
    }

    private fun finish(utteranceId: String) {
        val d = synchronized(lock) { waiting.remove(utteranceId) }
        d?.complete(Unit)
    }

    override suspend fun speak(text: String) {
        val ticket = gate.open()
        onTicketIssued()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        // 대기 등록도 표 확인과 같은 gate lock 안에서 한다(gate → waiting lock 순서):
        // stop이 먼저 확정됐으면 등록하지 않고 엔진 준비도 기다리지 않고 반환하고,
        // 등록이 먼저면 이후 stop이 gate lock 안에서 이 대기를 반드시 찾아 해제한다
        val registered = gate.startIfValid(ticket) {
            synchronized(lock) { waiting[id] = done }
            true
        }
        if (registered == null) {
            log("stop됨(대기 등록 전): $text")
            return
        }
        try {
            when (awaitEngineReady(done)) {
                InitWait.STOPPED -> { log("stop됨(엔진 준비 전): $text"); return }
                InitWait.FAILED -> { speakWithFallback(ticket, text, "엔진 초기화 실패"); return }
                InitWait.TIMED_OUT -> { speakWithFallback(ticket, text, "엔진 초기화 시간 초과"); return }
                InitWait.READY -> Unit
            }
            if (!engine.setKorean()) {
                speakWithFallback(ticket, text, "한국어 음성 없음")
                return
            }
            val f = focus
            if (f == null) speakOnce(ticket, text, id, done) else speakWithFocus(f, ticket, text, id, done)
        } finally {
            synchronized(lock) { waiting.remove(id) }
        }
    }

    /**
     * 엔진으로 한 번 읽는다(대기 [id]/[done]은 이미 gate 안에서 등록돼 있어야 한다).
     * 끝(onDone)·stop이면 정상 반환, 요청 실패·onError·외부 onStop이면 예외, 호출 취소면 엔진을 멈추고 예외 전파.
     */
    private suspend fun speakOnce(ticket: StartGate.Ticket, text: String, id: String, done: CompletableDeferred<Unit>) {
        var issued = false
        try {
            // stop과 같은 lock 안에서 표를 확인하고 요청: stop이 먼저 확정됐으면 요청하지 않고(완료 신호 전달 여부와 무관),
            // 요청이 먼저면 이후 stop이 엔진을 멈춘다
            val started: Boolean? = gate.startIfValid(ticket) {
                engine.speak(text, id).also { issued = it }
            }
            when (started) {
                null -> { log("stop됨(읽기 요청 전): $text"); return }
                false -> {
                    engine.stop()
                    throw IllegalStateException("TTS 읽기 요청 실패")
                }
                true -> Unit
            }
            done.await()
        } catch (e: CancellationException) {
            // 요청한 문장이 아직 끝나지 않았으면 실제 출력도 멈춘다
            val stillSpeaking = synchronized(lock) { issued && waiting.remove(id) != null }
            if (stillSpeaking) {
                engine.stop()
                log("코루틴 취소: 엔진 stop")
            }
            throw e
        } finally {
            synchronized(lock) { waiting.remove(id) }
        }
    }

    /** 포커스 안에서 읽는다(클래스 KDoc "오디오 용도·포커스"). 일시 상실이면 멈추고 gain 뒤 처음부터 다시 */
    private suspend fun speakWithFocus(
        f: SpeechFocus,
        ticket: StartGate.Ticket,
        text: String,
        firstId: String,
        firstDone: CompletableDeferred<Unit>,
    ) {
        val limitMs = checkNotNull(focusSuspendLimitMs)
        val changes = Channel<FocusChange>(Channel.UNLIMITED)
        val held = f.acquire { changes.trySend(it) } ?: throw IllegalStateException("오디오 포커스 거절")
        try {
            var id = firstId
            var done = firstDone
            while (true) {
                val attemptId = id
                val attemptDone = done
                val end = coroutineScope {
                    val attempt = async { speakOnce(ticket, text, attemptId, attemptDone); AttemptEnd.FINISHED }
                    val watcher = async { awaitLoss(changes) }
                    // 둘 다 준비됐으면 읽기가 끝난 쪽을 먼저 본다(이미 다 읽은 문장을 다시 읽지 않음)
                    select<AttemptEnd> {
                        attempt.onAwait { it }
                        watcher.onAwait { it }
                    }.also {
                        watcher.cancel()
                        attempt.cancel() // 상실로 끝내면 speakOnce가 엔진을 멈춘다
                    }
                }
                when (end) {
                    AttemptEnd.FINISHED -> return
                    AttemptEnd.LOST -> throw IllegalStateException("오디오 포커스 영구 상실")
                    AttemptEnd.TRANSIENT -> log("포커스 일시 상실: 멈추고 gain을 기다림: $text")
                }
                // 기다리는 동안에도 stop이 깨울 수 있게 gate 안에서 대기를 등록한다. stop이 먼저면 정상 반환
                val waitId = UUID.randomUUID().toString()
                val waitDone = CompletableDeferred<Unit>()
                gate.startIfValid(ticket) { synchronized(lock) { waiting[waitId] = waitDone } } ?: return
                val gained = try {
                    withTimeoutOrNull(limitMs) { awaitGain(changes, waitDone) }
                } finally {
                    synchronized(lock) { waiting.remove(waitId) }
                }
                when (gained) {
                    null -> throw IllegalStateException("오디오 포커스 일시 상실이 ${limitMs}ms 넘게 이어짐")
                    GainWait.STOPPED -> { log("stop됨(포커스 대기 중): $text"); return }
                    GainWait.LOST -> throw IllegalStateException("오디오 포커스 영구 상실")
                    GainWait.GAIN -> Unit
                }
                val nextId = UUID.randomUUID().toString()
                val nextDone = CompletableDeferred<Unit>()
                gate.startIfValid(ticket) { synchronized(lock) { waiting[nextId] = nextDone } } ?: return
                log("포커스 다시 받음: 처음부터 다시 읽음: $text")
                id = nextId
                done = nextDone
            }
        } finally {
            held.release()
            changes.close()
        }
    }

    private suspend fun awaitLoss(changes: Channel<FocusChange>): AttemptEnd {
        while (true) {
            when (changes.receive()) {
                FocusChange.LOSS_TRANSIENT -> return AttemptEnd.TRANSIENT
                FocusChange.LOSS -> return AttemptEnd.LOST
                FocusChange.GAIN -> Unit
            }
        }
    }

    /** 일시 상실 뒤 gain을 기다린다. [stopped]가 끝나면(stop) STOPPED */
    private suspend fun awaitGain(changes: Channel<FocusChange>, stopped: CompletableDeferred<Unit>): GainWait {
        while (true) {
            val c = select<FocusChange?> {
                stopped.onAwait { null }
                changes.onReceive { it }
            } ?: return GainWait.STOPPED
            when (c) {
                FocusChange.GAIN -> return GainWait.GAIN
                FocusChange.LOSS -> return GainWait.LOST
                FocusChange.LOSS_TRANSIENT -> Unit
            }
        }
    }

    /**
     * 엔진 준비를 기다린다. stop(대기 해제)되면 STOPPED, 초기화 실패면 FAILED.
     * 제한 시간은 이 대기에만 건다. 이미 시간 초과가 났으면 기다리지 않고 TIMED_OUT(늦은 성공 신호가 있어도 같음).
     * 호출 코루틴이 취소되면 `CancellationException`이 그대로 나간다(withTimeoutOrNull은 자기 시간 초과만 null로 바꿈).
     * select는 engine.ready를 기다리기만 하고 취소하지 않는다.
     */
    private suspend fun awaitEngineReady(done: CompletableDeferred<Unit>): InitWait {
        if (initTimedOut) return InitWait.TIMED_OUT
        val waitReady: suspend () -> InitWait = {
            select {
                engine.ready.onAwait { if (it) InitWait.READY else InitWait.FAILED }
                done.onAwait { InitWait.STOPPED }
                initTimedOutSignal.onAwait { InitWait.TIMED_OUT }
            }
        }
        val limit = initTimeoutMs ?: return waitReady()
        val r = withTimeoutOrNull(limit) { waitReady() }
        if (r != null && r != InitWait.TIMED_OUT) return r
        if (!initTimedOut) {
            initTimedOut = true
            log("엔진 초기화 신호가 ${limit}ms 안에 오지 않음: 이 인스턴스는 이후 엔진 대신 대체 TTS로 읽는다")
        }
        initTimedOutSignal.complete(Unit)
        return InitWait.TIMED_OUT
    }

    /**
     * 엔진 대신 [fallback]으로 읽는다. 작업을 LAZY로 만들어 gate lock 안에서 표를 확인하며 등록한 뒤 시작한다:
     * stop이 먼저면 읽지 않고 반환, 등록이 먼저면 stop이 이 작업을 취소해 speak가 정상 반환한다.
     */
    private suspend fun speakWithFallback(ticket: StartGate.Ticket, text: String, reason: String) = coroutineScope {
        if (!fallbackLogged) {
            fallbackLogged = true
            log("$reason: 엔진으로 읽지 않고 대체 TTS로 읽는다")
        }
        val job = launch(start = CoroutineStart.LAZY) { fallback.speak(text) }
        val registered = gate.startIfValid(ticket) {
            synchronized(lock) { fallbackJobs += job }
            true
        }
        if (registered == null) {
            job.cancel()
            log("stop됨(대체 TTS 시작 전): $text")
            return@coroutineScope
        }
        try {
            job.start()
            job.join() // stop으로 job이 취소되면 정상 반환, 이 코루틴이 취소되면 CancellationException
        } finally {
            synchronized(lock) { fallbackJobs -= job }
        }
    }

    override fun stop() {
        var toFinish: List<CompletableDeferred<Unit>> = emptyList()
        var fallbacks: List<Job> = emptyList()
        // 정지 확정(세대 번호)과 엔진 정지를 시작 판정과 같은 lock 안에서 한다
        gate.stop {
            synchronized(lock) {
                toFinish = waiting.values.toList().also { waiting.clear() }
                fallbacks = fallbackJobs.toList().also { fallbackJobs.clear() }
            }
            // 직접 stop은 정상 반환. 동기 onStop 콜백이 실패로 바꾸기 전에 대기를 꺼낸다.
            engine.stop()
        }
        // 대기 해제·대체 TTS 취소는 lock 밖에서 한다(시작 판정은 이미 세대 번호로 막혀 있음)
        toFinish.forEach { it.complete(Unit) }
        fallbacks.forEach { it.cancel() }
        if (fallbacks.isNotEmpty()) fallback.stop()
    }

    /** 엔진이 지금 소리를 내고 있는지(확인·로그용) */
    fun isSpeaking(): Boolean = engine.isSpeaking()

    fun shutdown() {
        stop()
        engine.shutdown()
    }

    private companion object {
        const val TAG = "KKIRI.AndroidTts"
    }
}

/** [AndroidTts]가 쓰는 엔진 경계(실제는 [TextToSpeechEngine]). JVM 검증 때 가짜 엔진으로 바꿔 끼운다 */
internal interface TtsEngine {
    /** 초기화 결과(true = 사용 가능) */
    val ready: Deferred<Boolean>
    /** 문장이 끝·오류·중단되면 utteranceId로 부른다 */
    fun setListener(onFinished: (String) -> Unit)
    /** 엔진 오류·외부 중단. 완료와 구별해 확인 질문이 실패를 정상 종료로 오인하지 않게 한다 */
    fun setErrorListener(onFailed: (String) -> Unit) {}
    /** 한국어 설정. 데이터가 없으면 false */
    fun setKorean(): Boolean
    /** 읽기 요청(대기열 뒤에 추가). 요청 성공 여부 */
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun isSpeaking(): Boolean
    fun shutdown()
}

internal class TextToSpeechEngine(context: Context, audioAttributes: AudioAttributes? = null) : TtsEngine {

    private val _ready = CompletableDeferred<Boolean>()
    override val ready: Deferred<Boolean> = _ready

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (!ok) Log.e(TAG, "TTS 엔진 초기화 실패: $status")
        _ready.complete(ok)
    }

    init {
        // 클립과 같은 용도(#3). 엔진 프로세스가 실제로 이 속성으로 내보내는지는 기기 확인 전
        audioAttributes?.let { tts.setAudioAttributes(it) }
    }

    private var onFailed: (String) -> Unit = {}

    override fun setErrorListener(onFailed: (String) -> Unit) { this.onFailed = onFailed }

    override fun setListener(onFinished: (String) -> Unit) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let(onFinished) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let(onFailed) }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let(onFailed) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let(onFailed) }
        })
    }

    override fun setKorean(): Boolean {
        val r = tts.setLanguage(Locale.KOREAN)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    // 엔진 큐에 200ms 묵음 → 본문 순으로 요청한다. 별도 묵음 ID는 본문 완료로 처리되지 않는다.
    // 출력 경로 활성화와 첫 소리 잘림 완화 효과는 기기 검증이 필요하다.
    override fun speak(text: String, utteranceId: String): Boolean = VoicePreroll.enqueueTts(
        text, utteranceId,
        silence = { duration, id -> tts.playSilentUtterance(duration, TextToSpeech.QUEUE_ADD, id) == TextToSpeech.SUCCESS },
        speech = { line, id -> tts.speak(line, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS },
    )

    override fun stop() {
        tts.stop()
    }

    override fun isSpeaking(): Boolean = tts.isSpeaking

    override fun shutdown() {
        tts.shutdown()
    }

    private companion object {
        const val TAG = "KKIRI.AndroidTts"
    }
}
