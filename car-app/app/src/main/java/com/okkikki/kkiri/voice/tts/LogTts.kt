package com.okkikki.kkiri.voice.tts

import android.util.Log
import com.okkikki.kkiri.contract.TtsAdapter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [TtsAdapter] 기본 목업(D-15 낭독 간격 시험용). 소리를 내지 않고 로그만 남긴다.
 * speak는 글자 수에 비례한 시간(글자 × [msPerChar], 최소 [minMs])만큼 기다린 뒤 반환한다(실제 낭독 시간 흉내).
 *
 * 정지·취소 보장(팀원 SpeechQueue T10과 합의한 동작):
 * - [stop]: 진행 중인 모든 speak가 **정상 반환**한다(예외 없음). speak에 들어온 뒤 대기 시작 전에 stop이 와도 반환한다.
 *   speak가 없을 때 부른 stop은 이후의 speak에 영향을 주지 않는다.
 * - speak를 부른 코루틴이 취소되면 대기를 바로 끝내고 `CancellationException`을 그대로 던진다(소리는 원래 없음).
 *
 * 시간 값·로그 함수는 생성자로 주입한다(목업 흉내용 값이며 명세 타이머가 아님. 로그 함수는 JVM 검증용).
 */
class LogTts(
    private val msPerChar: Long = DEFAULT_MS_PER_CHAR,
    private val minMs: Long = DEFAULT_MIN_MS,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : TtsAdapter {

    private val lock = Any()
    /** stop이 불릴 때마다 증가. speak는 들어올 때 값을 기억해 두고 대기 시작 전에 비교한다 */
    private var stopEpoch = 0L
    private val active = HashSet<Job>()

    override suspend fun speak(text: String) {
        val entryEpoch = synchronized(lock) { stopEpoch }
        val ms = maxOf(minMs, text.length * msPerChar)
        log("speak(${ms}ms): $text")
        coroutineScope {
            val job = launch(start = CoroutineStart.LAZY) { delay(ms) }
            val stoppedBeforeStart = synchronized(lock) {
                if (stopEpoch != entryEpoch) {
                    true
                } else {
                    active += job
                    false
                }
            }
            if (stoppedBeforeStart) {
                job.cancel()
                log("stop됨(대기 시작 전)")
                return@coroutineScope
            }
            try {
                job.start()
                job.join() // stop으로 job이 취소되면 정상 반환, 이 코루틴이 취소되면 CancellationException
            } finally {
                synchronized(lock) { active -= job }
            }
        }
    }

    override fun stop() {
        val jobs = synchronized(lock) {
            stopEpoch++
            active.toList().also { active.clear() }
        }
        if (jobs.isNotEmpty()) log("stop: ${jobs.size}건")
        jobs.forEach { it.cancel() }
    }

    private companion object {
        const val TAG = "KKIRI.LogTts"
        const val DEFAULT_MS_PER_CHAR = 80L
        const val DEFAULT_MIN_MS = 500L
    }
}
