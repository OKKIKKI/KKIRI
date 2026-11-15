package com.okkikki.kkiri.voice

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 확인 설정. 시간 값은 여기로만 주입한다(D-17).
 * timeoutMs = `CONFIRM_TIMEOUT_SEC`(명세 1.2, D-24) × 1000. 주입 지점은 [VoiceComponents] 한 곳.
 */
data class ConfirmConfig(val timeoutMs: Long)

/**
 * 실행 전 한 줄 확인(명세 5절-7, v1.9 D-24: 경유지 되돌리기·음소거).
 * - 질문은 [ask]의 `speak`(= [ConfirmationSpeechOutput.announceAndAwait])로 읽는다. 시스템 안내 우선순위로 대기열에 들어간다.
 * - **제한 시간은 질문 낭독이 정상 종료(true)된 뒤부터** 센다. 대기열에서 기다리거나 읽는 동안은 세지 않는다.
 * - 낭독이 false(reset·폐기·재생 실패)로 끝나거나 예외가 나면 [Answer.ABORTED] — 명령을 실행하지 않는다.
 * - 확인 대기 중에는 AcceptProposal = "응", DeclineProposal = "아니"로 쓰고 제안 응답보다 우선한다([answer]가 true면 소비).
 *   **낭독 중에 온 응답도 소비해 기억해 두고**(마지막 응답), 낭독이 정상 종료되면 타이머 없이 바로 그 답으로 끝낸다.
 * - 확인은 한 번에 하나. 새 확인이 오면 이전 확인은 [Answer.REPLACED]로 끝나고 이전 질문 낭독 작업도 취소된다
 *   (announceAndAwait 취소 → 출력이 그 질문을 빼거나 읽던 소리를 멈춤).
 * - [cancel](세션 종료·stop)도 같은 방식으로 끝낸다([Answer.CANCELLED]).
 * - [ask]를 부른 코루틴이 취소되면 질문 낭독도 취소하고 CancellationException을 전파한다.
 * ask는 호출한 코루틴만 기다리게 한다. 명령 수집 코루틴이 막히지 않도록 VoiceHubImpl은 명령마다 따로 코루틴을 띄운다.
 */
internal class ConfirmGate(private val config: ConfirmConfig) {

    enum class Answer { YES, NO, TIMEOUT, REPLACED, CANCELLED, ABORTED }

    /** 진행 중인 확인 하나. lock으로 보호 */
    private class Pending {
        val result = CompletableDeferred<Answer>()
        var speaking = true
        /** 낭독 중에 온 응답(마지막 것) */
        var early: Boolean? = null
        var speakJob: Job? = null
    }

    private val lock = Any()
    private var pending: Pending? = null

    suspend fun ask(prompt: String, speak: suspend (String) -> Boolean): Answer = coroutineScope {
        val p = Pending()
        val previous = synchronized(lock) { pending.also { pending = p } }
        previous?.let { end(it, Answer.REPLACED) }
        try {
            val speakJob = async {
                try {
                    speak(prompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false // 출력 예외는 확인 종료로 본다
                }
            }
            val endedBeforeSpeak = synchronized(lock) {
                p.speakJob = speakJob
                p.result.isCompleted
            }
            if (endedBeforeSpeak) speakJob.cancel()

            // 확인 종료(대체·취소)와 낭독 종료 중 먼저 오는 쪽. 확인 종료를 먼저 본다:
            // end()는 result를 끝낸 뒤 낭독 작업을 취소하므로, 둘 다 준비됐을 때 취소된 낭독을 기다려 예외가 나지 않게 한다
            val spoke: Boolean? = select {
                p.result.onAwait { null }
                speakJob.onAwait { it }
            }
            if (spoke == null) {
                speakJob.cancel()
                return@coroutineScope p.result.await()
            }
            if (!spoke) {
                p.result.complete(Answer.ABORTED)
                return@coroutineScope p.result.await()
            }
            // 낭독 정상 종료: 이때부터 응답을 바로 받고, 낭독 중 응답이 있으면 그것으로 끝낸다
            val early = synchronized(lock) {
                p.speaking = false
                p.early
            }
            if (early != null) p.result.complete(if (early) Answer.YES else Answer.NO)
            withTimeoutOrNull(config.timeoutMs) { p.result.await() } ?: run {
                p.result.complete(Answer.TIMEOUT)
                p.result.await()
            }
        } finally {
            synchronized(lock) { if (pending === p) pending = null }
        }
    }

    /** 확인 대기 중이면 답을 넣고 true(명령 소비). 낭독 중이면 기억해 두었다가 낭독이 끝나면 쓴다. 대기 중이 아니면 false */
    fun answer(yes: Boolean): Boolean {
        val p = synchronized(lock) {
            val p = pending ?: return false
            if (p.result.isCompleted) return false
            if (p.speaking) {
                p.early = yes
                return true
            }
            pending = null
            p
        }
        p.result.complete(if (yes) Answer.YES else Answer.NO)
        return true
    }

    /** 세션 종료·stop: 진행 중인 확인과 질문 낭독을 끝낸다 */
    fun cancel() {
        val p = synchronized(lock) { pending.also { pending = null } } ?: return
        end(p, Answer.CANCELLED)
    }

    private fun end(p: Pending, answer: Answer) {
        val job = synchronized(lock) { p.speakJob }
        p.result.complete(answer)
        job?.cancel()
    }
}
