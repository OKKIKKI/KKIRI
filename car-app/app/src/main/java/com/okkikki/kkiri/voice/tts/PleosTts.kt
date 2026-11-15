package com.okkikki.kkiri.voice.tts

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import com.okkikki.kkiri.contract.TtsAdapter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pleos TextToSpeech SDK 3.3.1(ON_DEVICE, ko-KR)로 읽는 [TtsAdapter]. 앱의 기본 TTS([com.okkikki.kkiri.voice.VoiceComponents]).
 * **D-26(CONFIRMED, 명세 v1.12 5절-9)**: AndroidTts(D-24) 대신 쓴다. 표준 TTS는 Pleos 에뮬레이터에서 초기화 실패·무응답이었고
 * 이 SDK는 팀원이 에뮬레이터에서 실제 음성을 확인했다(본 앱에서의 소리는 통합 2회차에서 확인).
 * 빌드 설정은 CAR-A(D-26): Pleos Maven 저장소, `ai.pleos.playground:TextToSpeech:3.3.1`, 매니페스트 `pleos.car.permission.TTS_SERVICE`.
 * 검증 이력은 레포 밖 `scratch-car-b-sdk-spike`·`scratch-car-b-final-audit`(같은 코드, JVM).
 *
 * 팀원 요구(2026-11-12, SpeechQueue와의 약속)
 * 1. speak는 onDone까지 기다렸다 반환한다.
 * 2. [stop]: SDK stop 뒤 **onStop이 오면 정상 반환**(예외 없음 → SpeechQueue가 "끊김"으로 보고 다시 읽음).
 *    onStop이 [stopAckMs] 안에 안 오면 그래도 정상 반환한다(대기열이 멈추지 않게).
 * 3. 호출 코루틴 취소: SDK stop을 부르고 `CancellationException`을 전파한다(onStop을 최대 [stopAckMs] 기다린 뒤 —
 *    다음 문장에 늦은 콜백이 섞이지 않게).
 * 4. onError면 예외. 우리가 부르지 않은 onStop(외부 중단)도 예외(AndroidTts와 같은 규칙: 확인 질문은 ABORTED).
 *
 * 명세 v1.12 5절-9(D-26): 초기화 실패·`PERMISSION_DENIED`·`NOT_SUPPORTED_API`·`ON_DEVICE_ENGINE_ERROR`·`UNAVAILABLE_LANGUAGE`면
 * **그 뒤로는** [fallback](LogTts)으로 읽는다. 그 오류가 난 문장은 4대로 예외로 끝낸다. 권한이 없어 SDK가 `SecurityException`을
 * 던지는 경우도 `PERMISSION_DENIED`와 같이 본다. 그 밖의 오류(TIMEOUT·AUDIO_ERROR 등)는 그 문장만 예외, 다음 문장은 SDK를 다시 쓴다.
 *
 * SDK 주의(팀원 확인): addEventListener는 initialize 성공 **뒤에**(앞이면 앱 종료), onReady는 speak마다 온다.
 * SDK의 speak에는 요청 ID가 없고 콜백도 어느 요청인지 알려주지 않는다 → 한 번에 한 문장만 보내고([Mutex]),
 * 직전 문장의 끝 신호를 확인하지 못했을 때(stop 확인 시간 초과·끝 신호 없음)만 다음 문장은 onReady 뒤의 끝 신호만 믿는다.
 *
 * 초기화 실패·SDK 호출 예외(권한 없음 SecurityException 포함)·초기화 제한 시간 초과는 [fallback]으로 읽는다(AndroidTts와 같음,
 * 소개 문장 실패로 VOICE 클립까지 버려지지 않게). 시간 초과 뒤에는 그 인스턴스 수명 동안 fallback(늦은 성공으로 이중 재생 없음).
 * 오디오 속성은 SDK 기본값 그대로다(바이트코드상 USAGE_MEDIA/SPEECH). `setAudioAttributes`로 클립과 같은 NOTIFICATION을 줄지는
 * 소리가 그대로 나는지 기기 확인 뒤 정한다(팀원이 소리를 확인한 설정을 바꾸지 않음). 포커스도 서비스(caas)가 관리하는지 미확인이라 요청하지 않는다.
 */
class PleosTts internal constructor(
    private val engine: PleosTtsEngine,
    private val fallback: TtsAdapter,
    /** 초기화를 기다리는 최대 시간(ms). null = 제한 없음 */
    private val initTimeoutMs: Long?,
    /** stop·취소 뒤 onStop을 기다리는 최대 시간(ms). 가정 값 */
    private val stopAckMs: Long,
    /** 한 문장의 끝 신호를 기다리는 최대 시간(ms). 문서에 완료 보장 시간이 없어 둔 안전장치, 가정 값 */
    private val speakLimitMs: Long,
    private val log: (String) -> Unit,
) : TtsAdapter {

    constructor(context: Context, fallback: TtsAdapter = LogTts(), initTimeoutMs: Long? = null) : this(
        SdkPleosTtsEngine(context), fallback, initTimeoutMs, DEFAULT_STOP_ACK_MS, DEFAULT_SPEAK_LIMIT_MS, { Log.i(TAG, it) },
    )

    private sealed interface End {
        data object Done : End
        data object Stopped : End
        data class Error(val type: String) : End
    }

    private class Utterance {
        val end = CompletableDeferred<End>()
        val stopAsked = CompletableDeferred<Unit>()
        var readySeen = false
        var stopRequested = false
    }

    private enum class Ready { OK, FAILED, TIMED_OUT, STOPPED }

    private val lock = Any()
    private var stopEpoch = 0L
    private var current: Utterance? = null
    /** 직전 문장의 끝 신호를 확인하지 못함(늦은 콜백 가능) → 다음 문장은 onReady 뒤의 끝 신호만 믿는다 */
    private var staleRisk = false
    private val initWaiters = HashSet<CompletableDeferred<Unit>>()
    private val fallbackJobs = HashSet<Job>()
    private val oneAtATime = Mutex()
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var initTimedOut = false
    /** 명세 5절-9의 치명 오류가 한 번이라도 났으면 이 인스턴스는 이후 SDK를 쓰지 않는다 */
    @Volatile private var sdkDisabled = false
    @Volatile private var listenerAdded = false

    private val listener = object : PleosTtsEngine.Listener {
        override fun onReady() = synchronized(lock) {
            val u = current ?: return@synchronized
            u.readySeen = true
            staleRisk = false
        }

        override fun onDone() = synchronized(lock) {
            val u = current ?: return@synchronized
            if (staleRisk && !u.readySeen) return@synchronized // 직전 문장의 늦은 신호
            u.end.complete(End.Done)
            Unit
        }

        override fun onStop() = synchronized(lock) {
            val u = current ?: return@synchronized
            if (staleRisk && !u.readySeen && !u.stopRequested) return@synchronized
            u.end.complete(End.Stopped)
            Unit
        }

        override fun onError(type: String) = synchronized(lock) {
            val u = current ?: return@synchronized
            if (staleRisk && !u.readySeen && !u.stopRequested) return@synchronized
            u.end.complete(End.Error(type))
            Unit
        }
    }

    init {
        require(initTimeoutMs == null || initTimeoutMs > 0) { "initTimeoutMs must be > 0" }
        require(stopAckMs > 0 && speakLimitMs > 0) { "stopAckMs·speakLimitMs must be > 0" }
        try {
            engine.initialize(
                ok = {
                    try {
                        if (!ready.isCompleted) {
                            engine.addListener(listener) // SDK: initialize 성공 뒤에만
                            listenerAdded = true
                            engine.setKorean()
                        }
                        ready.complete(true)
                    } catch (e: Exception) {
                        log("초기화 뒤 설정 실패: $e")
                        ready.complete(false)
                    }
                },
                failed = { type ->
                    log("초기화 실패: $type")
                    ready.complete(false)
                },
            )
        } catch (e: Exception) {
            log("초기화 호출 실패(권한·서비스): $e")
            ready.complete(false)
        }
    }

    override suspend fun speak(text: String) {
        val ticket = synchronized(lock) { stopEpoch }
        if (sdkDisabled) { speakFallback(ticket, text, "SDK 사용 중단(치명 오류 뒤)"); return }
        when (awaitReady(ticket)) {
            Ready.STOPPED -> { log("stop됨(엔진 준비 전): $text"); return }
            Ready.FAILED -> { speakFallback(ticket, text, "초기화 실패"); return }
            Ready.TIMED_OUT -> { speakFallback(ticket, text, "초기화 시간 초과"); return }
            Ready.OK -> Unit
        }
        oneAtATime.withLock {
            val u = Utterance()
            try {
                // stop과 같은 lock 안에서 표를 확인하고 요청한다(stop이 먼저면 요청하지 않음)
                val started = synchronized(lock) {
                    if (stopEpoch != ticket) return@synchronized false
                    current = u
                    try {
                        engine.speak(text)
                    } catch (e: SecurityException) {
                        disableSdk("PERMISSION_DENIED(SecurityException: ${e.message})")
                        throw e
                    }
                    true
                }
                if (!started) { log("stop됨(요청 전): $text"); return@withLock }
                val end = awaitEnd(u)
                val requested = synchronized(lock) { u.stopRequested }
                when {
                    requested -> Unit // 2: 우리가 멈춤 → 정상 반환
                    end is End.Done -> Unit
                    end is End.Error -> {
                        if (end.type in FATAL_ERRORS) disableSdk(end.type)
                        throw IllegalStateException("Pleos TTS 오류: ${end.type}")
                    }
                    else -> throw IllegalStateException("Pleos TTS 외부 중단")
                }
            } catch (e: CancellationException) {
                // 3: 호출 취소 → SDK stop, onStop을 잠깐 기다려 다음 문장과 섞이지 않게 한 뒤 전파
                val wasSpeaking = synchronized(lock) {
                    val speaking = current === u && !u.end.isCompleted
                    // 같은 문장에 SDK stop은 한 번만(SpeechQueue.pause는 취소 직후 stop()도 부른다 → 두 번째 onStop이 다음 문장에 붙지 않게)
                    if (speaking && !u.stopRequested) { u.stopRequested = true; safeStop() }
                    speaking
                }
                if (wasSpeaking) {
                    val acked = withContext(NonCancellable) { withTimeoutOrNull(stopAckMs) { u.end.await() } } != null
                    if (!acked) synchronized(lock) { staleRisk = true }
                }
                throw e
            } finally {
                synchronized(lock) { if (current === u) current = null }
            }
        }
    }

    /** 끝 신호를 기다린다. stop이면 onStop(확인)을 [stopAckMs]까지 기다린다. 끝 신호가 [speakLimitMs] 안에 없으면 SDK stop 후 예외 */
    private suspend fun awaitEnd(u: Utterance): End {
        val first: Any = withTimeoutOrNull(speakLimitMs) {
            select<Any> {
                u.end.onAwait { it }
                u.stopAsked.onAwait { STOP_MARK }
            }
        } ?: run {
            synchronized(lock) {
                if (!u.stopRequested) { u.stopRequested = true; safeStop() }
                staleRisk = true
            }
            throw IllegalStateException("Pleos TTS 끝 신호 없음(${speakLimitMs}ms)")
        }
        if (first !== STOP_MARK) return first as End
        val acked = withTimeoutOrNull(stopAckMs) { u.end.await() }
        if (acked == null) {
            synchronized(lock) { staleRisk = true }
            log("stop 뒤 onStop이 ${stopAckMs}ms 안에 오지 않음: 정상 반환, 다음 문장은 onReady 뒤 신호만 믿음")
        }
        return End.Stopped
    }

    private suspend fun awaitReady(ticket: Long): Ready {
        if (initTimedOut) return Ready.TIMED_OUT
        val w = CompletableDeferred<Unit>()
        val registered = synchronized(lock) {
            if (stopEpoch != ticket) false else { initWaiters += w; true }
        }
        if (!registered) return Ready.STOPPED
        try {
            val wait: suspend () -> Ready = {
                select {
                    ready.onAwait { if (it) Ready.OK else Ready.FAILED }
                    w.onAwait { Ready.STOPPED }
                }
            }
            val limit = initTimeoutMs ?: return wait()
            val r = withTimeoutOrNull(limit) { wait() }
            if (r != null) return if (initTimedOut && r == Ready.OK) Ready.TIMED_OUT else r
            if (!initTimedOut) {
                initTimedOut = true
                log("초기화 신호가 ${limit}ms 안에 오지 않음: 이 인스턴스는 이후 대체 TTS로 읽는다")
            }
            return Ready.TIMED_OUT
        } finally {
            synchronized(lock) { initWaiters -= w }
        }
    }

    private suspend fun speakFallback(ticket: Long, text: String, reason: String) = coroutineScope {
        log("$reason: 대체 TTS로 읽음: $text")
        val job = launch(start = CoroutineStart.LAZY) { fallback.speak(text) }
        val registered = synchronized(lock) {
            if (stopEpoch != ticket) false else { fallbackJobs += job; true }
        }
        if (!registered) { job.cancel(); return@coroutineScope }
        try {
            job.start()
            job.join() // stop으로 job이 취소되면 정상 반환, 이 코루틴이 취소되면 예외
        } finally {
            synchronized(lock) { fallbackJobs -= job }
        }
    }

    override fun stop() {
        val u: Utterance?
        val waiters: List<CompletableDeferred<Unit>>
        val fallbacks: List<Job>
        synchronized(lock) {
            stopEpoch++
            u = current?.takeIf { !it.end.isCompleted }
            // 읽는 중일 때만, 그 문장에 한 번만 SDK stop(아무것도 없을 때나 두 번 부르면 늦은 onStop이 다음 문장에 붙을 수 있음)
            if (u != null && !u.stopRequested) { u.stopRequested = true; safeStop() }
            waiters = initWaiters.toList().also { initWaiters.clear() }
            fallbacks = fallbackJobs.toList().also { fallbackJobs.clear() }
        }
        u?.stopAsked?.complete(Unit)
        waiters.forEach { it.complete(Unit) }
        fallbacks.forEach { it.cancel() }
        if (fallbacks.isNotEmpty()) fallback.stop()
    }

    fun release() {
        stop()
        try {
            if (listenerAdded) engine.removeListener(listener)
            engine.release()
        } catch (e: Exception) {
            log("release 실패: $e")
        }
    }

    private fun disableSdk(reason: String) {
        if (!sdkDisabled) {
            sdkDisabled = true
            log("치명 오류($reason): 이 인스턴스는 이후 SDK 대신 대체 TTS로 읽는다(명세 5절-9)")
        }
    }

    /** lock 안에서 부른다. SDK가 같은 스레드에서 콜백을 불러도 lock은 재진입된다 */
    private fun safeStop() {
        try { engine.stop() } catch (e: Exception) { log("SDK stop 실패: $e") }
    }

    private companion object {
        const val TAG = "KKIRI.PleosTts"
        val STOP_MARK = Any()
        const val DEFAULT_STOP_ACK_MS = 1_000L // 가정 값
        const val DEFAULT_SPEAK_LIMIT_MS = 60_000L // 가정 값
        /** 명세 v1.12 5절-9: 이 오류 뒤로는 LogTts (SDK ErrorType 이름 그대로) */
        val FATAL_ERRORS = setOf("PERMISSION_DENIED", "NOT_SUPPORTED_API", "ON_DEVICE_ENGINE_ERROR", "UNAVAILABLE_LANGUAGE")
    }
}

/** [PleosTts]가 쓰는 SDK 경계(실제는 [SdkPleosTtsEngine]). JVM 검증 때 가짜로 바꿔 끼운다. SDK 클래스를 흉내 내지 않는다 */
internal interface PleosTtsEngine {
    /** 던지면(권한 없음 등) 초기화 실패로 본다 */
    fun initialize(ok: () -> Unit, failed: (String) -> Unit)
    /** initialize 성공 **뒤에만** 부른다(SDK: 앞에서 부르면 앱 종료, 팀원 확인) */
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun setKorean()
    fun speak(text: String)
    fun stop()
    fun release()

    interface Listener {
        fun onReady()
        fun onDone()
        fun onStop()
        fun onError(type: String)
    }
}

/** 실제 SDK 연결. 시그니처는 SDK 3.3.1 바이너리(javap)로 확인한 것만 쓴다 */
internal class SdkPleosTtsEngine(context: Context) : PleosTtsEngine {
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var bridge: EventListener? = null

    override fun initialize(ok: () -> Unit, failed: (String) -> Unit) {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() = ok()
            override fun onFailure(errorType: ErrorType) = failed(errorType.name)
        })
    }

    override fun addListener(listener: PleosTtsEngine.Listener) {
        val b = object : EventListener {
            override fun onStart() = Unit
            override fun onReady() = listener.onReady()
            override fun onDone() = listener.onDone()
            override fun onError(errorType: ErrorType) = listener.onError(errorType.name)
            override fun onStop() = listener.onStop()
            override fun onUpdatedRms(rms: Double) = Unit
        }
        bridge = b
        tts.addEventListener(b)
    }

    override fun removeListener(listener: PleosTtsEngine.Listener) {
        bridge?.let { tts.removeEventListener(it) }
        bridge = null
    }

    override fun setKorean() = tts.setLocale(Locale.KOREA)
    override fun speak(text: String) = tts.speak(text, true)
    override fun stop() = tts.stop()
    override fun release() = tts.release()
}
