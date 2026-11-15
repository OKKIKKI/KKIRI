package com.okkikki.kkiri.voice

/**
 * 소리 시작과 정지의 경합 정리(CAR-B 내부, TTS·클립 어댑터용). android.* 의존 없음.
 *
 * - [stop]은 정지 세대 번호를 lock 안에서 올린다(정지 확정). 같은 lock 안에서 실제 정지(엔진 stop 등)도 할 수 있다.
 * - 요청은 들어올 때 [open]으로 표를 받고, 소리를 시작하기 직전 [startIfValid]가 **같은 lock 안에서**
 *   표가 아직 유효한지 확인한 뒤 시작 블록을 실행한다.
 *   → 정지가 먼저 확정됐으면 시작하지 않는다. 시작이 먼저면 이후 정지가 그 소리를 멈춘다.
 *   완료 신호(deferred complete)가 언제 전달되는지에 의존하지 않는다.
 * - [exclusive]면 새 [open]이 이전 표를 무효로 만든다(한 번에 하나만 재생하는 클립용).
 * - [stop] 전에 받은 표만 무효가 된다. 요청이 없을 때 부른 stop은 이후 요청에 영향이 없다.
 */
internal class StartGate(private val exclusive: Boolean) {

    data class Ticket(val stopEpoch: Long, val seq: Long)

    private val lock = Any()
    private var stopEpoch = 0L
    private var lastSeq = 0L

    fun open(): Ticket = synchronized(lock) { Ticket(stopEpoch, ++lastSeq) }

    /** 정지 확정. [whileLocked]는 같은 lock 안에서 실행된다(시작 블록과 겹치지 않음) */
    fun stop(whileLocked: () -> Unit = {}) {
        synchronized(lock) {
            stopEpoch++
            whileLocked()
        }
    }

    fun isValid(ticket: Ticket): Boolean = synchronized(lock) { isValidLocked(ticket) }

    /** 표가 유효하면 같은 lock 안에서 [start]를 실행하고 그 결과를, 아니면 null */
    fun <T : Any> startIfValid(ticket: Ticket, start: () -> T): T? =
        synchronized(lock) { if (isValidLocked(ticket)) start() else null }

    private fun isValidLocked(t: Ticket): Boolean =
        t.stopEpoch == stopEpoch && (!exclusive || t.seq == lastSeq)
}
