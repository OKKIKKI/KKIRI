package com.okkikki.kkiri.voice.debug

import com.okkikki.kkiri.contract.VoiceCommand
import com.okkikki.kkiri.contract.VoiceCommandSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [VoiceCommandSource] 목업 (HB-F-COM-05, D-05 OPEN 동안 디버그 버튼 패널이 명령을 낸다).
 * android.* 에 의존하지 않는다. 로그·상태 표시는 [VoiceCommandDebugPanel]이 한다.
 *
 * 정책
 * - replay=0, extraBufferCapacity=16, onBufferOverflow=SUSPEND, 발생은 [MutableSharedFlow.tryEmit]만 쓴다.
 * - 구독자가 없을 때 낸 명령은 버린다([EmitResult.NoSubscriber]). 나중에 구독해도 **다시 전달하지 않는다**.
 *   명령은 "방금 거 취소"·지금 떠 있는 제안 수락처럼 그 순간의 상황에 묶여 있어 늦게 실행하면 안 된다.
 * - [EmitResult.Accepted]는 SharedFlow 버퍼에 들어갔다는 뜻일 뿐이다. 구독자가 실제로 받았는지,
 *   명령 처리(서버 호출 등)가 성공했는지는 이 결과로 알 수 없다. 수집 확인은 구독하는 쪽에서 한다.
 * - 구독자가 2개 이상이면 같은 명령을 여러 곳이 처리할 위험이 있다. SharedFlow로는 막을 수 없으므로
 *   [EmitResult.Accepted.duplicateRisk]로 알린다. 구독을 하나로 유지하는 것은 구독하는 쪽(후속 VoiceHub)의 일이다.
 *
 * 경쟁 가능성: [emit]은 구독자 수를 읽은 뒤 tryEmit을 부른다. 그 사이에 구독자가 사라지면 Accepted로 보고되지만
 * 실제로는 버려질 수 있고, 그 사이에 구독자가 생기면 NoSubscriber로 보고되고 버려진다. 결과의 구독자 수는
 * 읽은 순간의 값이다. 사람이 버튼을 누르는 디버그 용도라 허용한다.
 *
 * 이 클래스에는 코루틴 스코프가 없다. 수명은 이 인스턴스를 가진 쪽(VoiceCommandSources)을 따른다.
 */
class DebugVoiceCommandSource : VoiceCommandSource {

    private val flow = MutableSharedFlow<VoiceCommand>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override val commands: SharedFlow<VoiceCommand> = flow.asSharedFlow()

    /** 지금 [commands]를 구독 중인 수(읽은 순간의 값) */
    val subscriberCount: Int
        get() = flow.subscriptionCount.value

    /** 명령을 낸다. 결과는 발생 결과이며 수집·처리 결과가 아니다. */
    fun emit(command: VoiceCommand): EmitResult {
        val subscribers = flow.subscriptionCount.value
        if (subscribers == 0) {
            // 구독자가 없으면 tryEmit은 true를 돌려주면서 값을 버린다. 오해를 막으려고 부르지 않는다.
            return EmitResult.NoSubscriber(command)
        }
        return if (flow.tryEmit(command)) {
            EmitResult.Accepted(command, subscribers)
        } else {
            EmitResult.BufferFull(command, subscribers)
        }
    }

    companion object {
        const val BUFFER_CAPACITY = 16
    }
}

/** [DebugVoiceCommandSource.emit]의 결과. 모두 "발생" 단계의 결과다. */
sealed interface EmitResult {
    val command: VoiceCommand

    /** 구독자 없음. 버렸고 나중에 다시 전달하지 않는다. */
    data class NoSubscriber(override val command: VoiceCommand) : EmitResult

    /** 버퍼 포화(느린 구독자). 버렸다. */
    data class BufferFull(override val command: VoiceCommand, val subscribers: Int) : EmitResult

    /** 버퍼에 들어감. 수집·처리 성공이 아니다. */
    data class Accepted(override val command: VoiceCommand, val subscribers: Int) : EmitResult {
        /** 구독자가 2개 이상 — 같은 명령이 중복 처리될 위험 */
        val duplicateRisk: Boolean get() = subscribers > 1
    }
}
