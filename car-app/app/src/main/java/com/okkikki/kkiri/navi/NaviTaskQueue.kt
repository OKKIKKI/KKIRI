package com.okkikki.kkiri.navi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 경유지 반영 작업 대기열(VoiceHubImpl 실행 단위마다 하나). 이벤트 순서(추가→되돌리기 삭제 등)를 지키려고
 * 한 코루틴에서 차례로 실행한다. android.* 의존 없음.
 * - 작업 하나가 일반 예외를 던져도 [onError]로 알리고 **다음 작업을 계속** 실행한다(작업 코루틴이 끝나지 않음).
 * - `CancellationException`은 삼키지 않는다(실행 단위 scope가 취소되면 대기열도 끝난다).
 */
internal class NaviTaskQueue(private val onError: (Throwable) -> Unit) {

    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    fun start(scope: CoroutineScope) {
        scope.launch {
            for (task in tasks) {
                try {
                    task()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    fun enqueue(task: suspend () -> Unit) {
        tasks.trySend(task)
    }
}
