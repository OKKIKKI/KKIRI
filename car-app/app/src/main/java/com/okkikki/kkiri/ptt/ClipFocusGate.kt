package com.okkikki.kkiri.ptt

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 클립 한 건의 포커스 상태. Android API와 분리해 늦은 gain/거절/정리 순서를 검증한다. */
internal class ClipFocusGate(
    private val play: () -> Unit,
    private val pause: () -> Unit,
    private val fail: () -> Unit,
) {
    private enum class State { WAITING, GRANTED, SUSPENDED, CLOSED }
    private val lock = Any()
    private var state = State.WAITING
    private var requested = false
    private var playing = false
    private val _suspended = MutableStateFlow(false)

    /** 일시 상실로 멈춰 gain을 기다리는 중인지. 상한 감시([withSuspendLimit])가 본다 */
    val suspended: StateFlow<Boolean> = _suspended.asStateFlow()

    fun requestResult(granted: Boolean) = synchronized(lock) {
        if (state == State.CLOSED) return@synchronized
        if (!granted) {
            state = State.CLOSED
            _suspended.value = false
            fail()
        } else if (state == State.WAITING) {
            state = State.GRANTED
            playIfReady()
        }
    }

    fun start() = synchronized(lock) {
        if (state != State.CLOSED) {
            requested = true
            playIfReady()
        }
    }

    fun gain() = synchronized(lock) {
        if (state != State.CLOSED) {
            state = State.GRANTED
            _suspended.value = false
            playIfReady()
        }
    }

    /** 음성은 duck 대신 일시정지한다. 영구 상실은 실패로 끝내며 이후 gain으로 되살리지 않는다. */
    fun loss(temporary: Boolean) = synchronized(lock) {
        if (state == State.CLOSED) return@synchronized
        state = if (temporary) State.SUSPENDED else State.CLOSED
        _suspended.value = temporary
        if (playing) {
            playing = false
            pause()
        }
        if (!temporary) fail()
    }

    fun close() = synchronized(lock) {
        state = State.CLOSED
        playing = false
        _suspended.value = false
    }

    private fun playIfReady() {
        if (requested && state == State.GRANTED && !playing) {
            play()
            playing = true
        }
    }
}

/**
 * [block]을 실행하는 동안 [suspended]가 true인 상태가 [limitMs] 넘게 **이어지면** [onLimit]을 한 번 부른다.
 * 중간에 false로 돌아오면(gain) 그 구간의 시간은 버리고 다음 일시 상실 때 다시 센다. [block]이 끝나면 감시도 끝난다.
 * 클립이 포커스 일시 상실로 멈춘 채 gain이 오지 않아 낭독 대기열 전체가 멈추는 것을 막는다(onLimit에서 실패로 끝냄).
 */
internal suspend fun <T> withSuspendLimit(
    suspended: StateFlow<Boolean>,
    limitMs: Long,
    onLimit: () -> Unit,
    block: suspend () -> T,
): T = coroutineScope {
    require(limitMs > 0) { "limitMs must be > 0: $limitMs" }
    val watchdog = launch {
        suspended.collectLatest { isSuspended ->
            if (isSuspended) {
                delay(limitMs)
                onLimit()
            }
        }
    }
    try {
        block()
    } finally {
        watchdog.cancel()
    }
}
