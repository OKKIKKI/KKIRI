package com.okkikki.kkiri.voice

import android.util.Log
import com.okkikki.kkiri.contract.DeliveryMode
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.MessageCreated
import com.okkikki.kkiri.contract.MessageKind
import com.okkikki.kkiri.state.AmbientTrigger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * message.created(명세 4.3) → 낭독 출력([SpeechOutput.onMessage]) 분기. deliveryMode부터 본다(서버 값대로만, 스스로 판단하지 않음).
 * - DISPLAY → 무시(자기 차가 보낸 메시지 등)
 * - HOLD → 다운로드 없이 onMessage
 * - SPEAK + VOICE → 클립을 받아(R-25) 성공하면 clipFile을 넣어 onMessage, 실패하면 로그만
 * - SPEAK + TEXT·BROADCAST → onMessage
 * 다운로드는 scope에서 따로 실행해 이벤트 수신을 막지 않는다. 받은 클립 파일의 정리는 아직 하지 않는다(캐시).
 */
internal class MessageRouter(
    private val output: SpeechOutput,
    /** (메시지, clip.url) → 로컬 파일 */
    private val fetchClip: suspend (Message, String) -> Result<File>,
    /** 보낸 차 표시 이름(session.cars의 displayName, 없으면 sender.name) */
    private val carNameOf: (Message) -> String,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val warn: (String, Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) {

    fun route(scope: CoroutineScope, event: MessageCreated) {
        val m = event.message
        when (event.deliveryMode) {
            DeliveryMode.DISPLAY -> log("DISPLAY 무시: ${m.messageId}")
            DeliveryMode.HOLD -> output.onMessage(IncomingMessage(m, DeliveryMode.HOLD, carNameOf(m), null))
            DeliveryMode.SPEAK -> when (m.kind) {
                MessageKind.VOICE -> {
                    val url = m.clip?.url
                    if (url == null) {
                        warn("VOICE인데 clip 없음: ${m.messageId}", null)
                        return
                    }
                    scope.launch {
                        fetchClip(m, url)
                            .onSuccess { file -> AmbientTrigger.fire(); output.onMessage(IncomingMessage(m, DeliveryMode.SPEAK, carNameOf(m), file)) }
                            .onFailure { warn("클립 받기 실패, 낭독 안 함: ${m.messageId}", it) }
                    }
                }
                MessageKind.TEXT, MessageKind.BROADCAST ->
                    { AmbientTrigger.fire(); output.onMessage(IncomingMessage(m, DeliveryMode.SPEAK, carNameOf(m), null)) }
            }
        }
    }

    private companion object {
        const val TAG = "KKIRI.MessageRouter"
    }
}
