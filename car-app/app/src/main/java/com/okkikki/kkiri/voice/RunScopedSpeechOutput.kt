package com.okkikki.kkiri.voice

import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.SpeakerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceHubImpl 실행 단위(start~stop) 하나가 쓰는 출력 창구(CAR-B 내부). 출력 계약([ConfirmationSpeechOutput])은 그대로다.
 * - 이 실행 단위가 아직 현재일 때만([isCurrent]) 공유 출력에 넘긴다. 확인은 [lock] 안에서 하며, VoiceHubImpl의
 *   start·stop도 같은 lock 안에서 출력을 reset·start하므로 stop·재시작 뒤 이전 실행의 안내·메시지·pause/resume이
 *   새 출력에 들어가지 않는다.
 * - [announceAndAwait]는 suspend라 lock 안에서 부를 수 없다: 현재가 아니면 false로 바로 끝내고(확인 종료),
 *   확인 직후 재시작이 끼면 이전 실행의 scope 취소로 질문이 철회된다(ConfirmationSpeechOutput 계약).
 * - start·reset은 VoiceHubImpl이 원래 출력에 직접 부른다(여기서는 넘기지 않음).
 * android.* 의존 없음.
 */
internal class RunScopedSpeechOutput(
    private val output: ConfirmationSpeechOutput,
    private val lock: Any,
    private val isCurrent: () -> Boolean,
) : ConfirmationSpeechOutput {

    override val nowSpeaking: StateFlow<SpeakerInfo?> get() = output.nowSpeaking
    override val heldCount: StateFlow<Map<String, Int>> get() = output.heldCount

    override fun start(scope: CoroutineScope, ctx: SpeechContext) =
        throw UnsupportedOperationException("start는 VoiceHubImpl이 원래 출력에 부른다")

    override fun reset() =
        throw UnsupportedOperationException("reset은 VoiceHubImpl이 원래 출력에 부른다")

    override fun onMessage(item: IncomingMessage) = guarded { output.onMessage(item) }

    override fun announce(text: String, urgent: Boolean) = guarded { output.announce(text, urgent) }

    /** 시작 신호도 그대로 넘긴다(감싸지 않음: 출력이 부를 때 VoiceHubImpl lock을 잡지 않게) */
    override fun announce(text: String, urgent: Boolean, onStart: (() -> Unit)?) =
        guarded { output.announce(text, urgent, onStart) }

    override fun onServerEvent(event: ServerEvent) = guarded { output.onServerEvent(event) }

    override fun pause() = guarded { output.pause() }

    override fun resume() = guarded { output.resume() }

    override suspend fun announceAndAwait(text: String): Boolean {
        if (!synchronized(lock) { isCurrent() }) return false
        return output.announceAndAwait(text)
    }

    private inline fun guarded(block: () -> Unit) {
        synchronized(lock) { if (isCurrent()) block() }
    }
}
