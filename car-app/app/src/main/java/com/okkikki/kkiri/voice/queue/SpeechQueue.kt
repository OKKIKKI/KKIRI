package com.okkikki.kkiri.voice.queue

import com.okkikki.kkiri.contract.DeliveryMode
import com.okkikki.kkiri.contract.DriveState
import com.okkikki.kkiri.contract.MessageKind
import com.okkikki.kkiri.contract.MusicQueueAdded
import com.okkikki.kkiri.contract.Mute
import com.okkikki.kkiri.contract.MuteChanged
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.SessionSnapshot
import com.okkikki.kkiri.contract.SpeakerInfo
import com.okkikki.kkiri.voice.ConfirmationSpeechOutput
import com.okkikki.kkiri.voice.IncomingMessage
import com.okkikki.kkiri.voice.SpeechContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 낭독 출력(팀원, D-21). MSG-02 낭독 대기열, VOI-05 발화 차량 표시, VOI-06 HOLD 보관·정차 안내, MUS-05 음악 알림.
 * 기준: 명세 5절-3·4·5, 4.3 `music.queue_added`, D-15(간격)·D-17(시간 값은 config로만).
 *
 * 순서(한 번에 소리 하나):
 * 1. 긴급 메시지·긴급 안내 — 간격 없음. 지금 읽는 것은 끊지 않는다(명세 v1.9 5절-3, D-24)
 * 2. 일반 안내(announce) — 간격 없음
 * 3. 일반 메시지·음악 알림 — 직전 소리가 끝나고 `minGapMs`가 지나야 읽는다.
 *    PARKED로 바뀐 순간 대기 중이던 것은 간격 없이 이어서 읽는다(묶어 읽기)
 *
 * 스레드: 모든 공개 메서드는 아무 스레드에서나 동시에 불러도 된다(내부 `lock`으로 동기화).
 * 어댑터(tts·clipPlayer)와 ctx의 람다는 `lock` 밖에서 부른다.
 *
 * pause: 항목마다 재생 코루틴을 따로 두고 pause가 그 코루틴을 취소한다(CAR-B TTS 보장: 취소되면 실제 소리도 멈춤).
 * 보관 건수는 안내 뒤에도 유지하고, 내 차가 그 차를 더는 음소거하지 않으면 지운다(명세 v1.9 5절-5, D-24).
 *
 * 확인 질문(announceAndAwait, CAR-B ConfirmationSpeechOutput): 일반 안내 줄에 요청마다 따로 넣고 낭독이 끝나면 true.
 * pause로 끊겨 다시 기다리는 동안은 끝난 것으로 치지 않는다. reset·start·어댑터 실패는 false.
 * "낭독 작업이 끝났다"는 뜻이며 실제로 들렸는지(기기 검증)와는 다르다.
 */
class SpeechQueue : ConfirmationSpeechOutput {

    private sealed interface Item {
        data class Msg(val value: IncomingMessage) : Item
        /** done: announceAndAwait 요청이면 그 요청만의 완료 신호(같은 문장이어도 공유하지 않도록 data class가 아님) */
        class Announce(val text: String, val done: CompletableDeferred<Boolean>? = null) : Item
        data class Music(val text: String) : Item
    }

    private enum class Lane { URGENT, ANNOUNCE, NORMAL }

    /** 대기열에서 꺼낸 항목과 원래 줄(pause 때 되돌릴 곳) */
    private class Pick(val item: Item, val lane: Lane)

    private val lock = Any()

    // ---- 아래 상태는 모두 lock 안에서만 읽고 쓴다 ----
    private val urgentLane = ArrayDeque<Item>()
    private val announceLane = ArrayDeque<Item>()
    private val normalLane = ArrayDeque<Item>()
    private val held = LinkedHashMap<String, Int>()
    private val heldNames = HashMap<String, String>()

    private var ctx: SpeechContext? = null
    private var scope: CoroutineScope? = null
    /** start·reset마다 올린다. 옛 작업이 늦게 끝나도 새 상태를 건드리지 못하게 한다 */
    private var generation = 0L
    private val jobs = mutableListOf<Job>()
    private var gapJob: Job? = null
    private var gapOpen = true
    private var bundleRemaining = 0
    /** pause로 끊겨 맨 앞에 되돌린 일반 항목 수. 간격 없이 다시 읽는다 */
    private var resumeBypass = 0
    private var lastDrive: DriveState? = null
    private var paused = false
    private var current: Pick? = null
    private var interrupted = false
    /** 지금 항목을 재생 중인 코루틴. pause·reset이 취소한다 */
    private var playJob: Job? = null
    /** 지금 항목이 announceAndAwait 취소로 버려졌다(다시 읽지 않는다) */
    private var discardCurrent = false
    // ------------------------------------------------

    /** 일거리가 생겼음을 작업 코루틴에 알린다 */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _nowSpeaking = MutableStateFlow<SpeakerInfo?>(null)
    override val nowSpeaking: StateFlow<SpeakerInfo?> = _nowSpeaking.asStateFlow()

    private val _heldCount = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val heldCount: StateFlow<Map<String, Int>> = _heldCount.asStateFlow()

    override fun start(scope: CoroutineScope, ctx: SpeechContext) {
        val gen: Long
        val orphan: CompletableDeferred<Boolean>?
        synchronized(lock) {
            // reset 없이 start가 다시 불리면 읽던 항목은 버려진다 → 기다리던 질문은 false로 끝낸다
            orphan = current?.let(::doneOf)
            cancelJobsLocked()
            generation++
            gen = generation
            this.ctx = ctx
            this.scope = scope
            gapOpen = true
            bundleRemaining = 0
            resumeBypass = 0
            lastDrive = null
            current = null
            interrupted = false
            discardCurrent = false
        }
        orphan?.complete(false)
        val worker = scope.launch { runWorker(scope, ctx, gen) }
        val driveWatcher = scope.launch { ctx.drive.driveState.collect { onDriveState(it, gen) } }
        synchronized(lock) {
            if (generation == gen) {
                jobs += worker
                jobs += driveWatcher
            } else {
                worker.cancel()
                driveWatcher.cancel()
            }
        }
        wake.trySend(Unit)
    }

    override fun reset() {
        val c: SpeechContext?
        val waiting: List<CompletableDeferred<Boolean>>
        synchronized(lock) {
            waiting = listOfNotNull(current?.let(::doneOf)) +
                announceLane.mapNotNull { (it as? Item.Announce)?.done } +
                urgentLane.mapNotNull { (it as? Item.Announce)?.done }
            cancelJobsLocked()
            generation++
            urgentLane.clear()
            announceLane.clear()
            normalLane.clear()
            held.clear()
            heldNames.clear()
            gapOpen = true
            bundleRemaining = 0
            resumeBypass = 0
            lastDrive = null
            paused = false
            current = null
            interrupted = false
            discardCurrent = false
            c = ctx
            _nowSpeaking.value = null
            _heldCount.value = emptyMap()
        }
        waiting.forEach { it.complete(false) }
        c?.tts?.stop()
        c?.clipPlayer?.stop()
    }

    override suspend fun announceAndAwait(text: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val item = Item.Announce(text, done)
        synchronized(lock) { announceLane.addLast(item) }
        wake.trySend(Unit)
        try {
            return done.await()
        } catch (e: CancellationException) {
            withdraw(item)
            throw e
        }
    }

    /** announceAndAwait 호출이 취소됨: 대기 중이면 빼고, 읽는 중이면 멈추고 다시 읽지 않는다 */
    private fun withdraw(item: Item.Announce) {
        val c: SpeechContext?
        val job: Job?
        synchronized(lock) {
            item.done?.complete(false)
            if (announceLane.removeAll { it === item }) return
            if (current?.item !== item) return
            discardCurrent = true
            c = ctx
            job = playJob
        }
        job?.cancel()
        c?.tts?.stop()
        c?.clipPlayer?.stop()
    }

    override fun onMessage(item: IncomingMessage) {
        synchronized(lock) {
            when (item.mode) {
                DeliveryMode.SPEAK ->
                    (if (item.message.urgent) urgentLane else normalLane).addLast(Item.Msg(item))
                DeliveryMode.HOLD -> {
                    val carId = item.message.sender.carId
                    held[carId] = (held[carId] ?: 0) + 1
                    heldNames[carId] = item.carName
                    publishHeldLocked()
                }
                DeliveryMode.DISPLAY -> return // CAR-B 쪽에서 거른다. 와도 무시
            }
        }
        wake.trySend(Unit)
    }

    override fun announce(text: String, urgent: Boolean) {
        synchronized(lock) {
            (if (urgent) urgentLane else announceLane).addLast(Item.Announce(text))
        }
        wake.trySend(Unit)
    }

    override fun onServerEvent(event: ServerEvent) {
        when (event) {
            is MusicQueueAdded -> {
                // 호스트 차 여부는 읽을 때 확인한다(start 전에 와도 담아 둘 수 있게)
                synchronized(lock) {
                    normalLane.addLast(Item.Music(SpeechTexts.musicQueued(event.addedBy.name, event.title)))
                }
                wake.trySend(Unit)
            }
            is MuteChanged -> dropUnmutedHeld(event.mutes)
            is SessionSnapshot -> dropUnmutedHeld(event.session.mutes)
            else -> Unit
        }
    }

    override fun pause() {
        val c: SpeechContext?
        val job: Job?
        synchronized(lock) {
            paused = true
            if (current == null) return
            interrupted = true
            c = ctx
            job = playJob
        }
        // 재생 코루틴 취소가 주 경로(시작 전이면 소리가 아예 나지 않는다). stop은 어댑터가 취소를 늦게 볼 때를 위한 보조
        job?.cancel()
        c?.tts?.stop()
        c?.clipPlayer?.stop()
    }

    override fun resume() {
        synchronized(lock) { paused = false }
        wake.trySend(Unit)
    }

    // ---------------------------------------------------------------- 작업 코루틴

    private suspend fun runWorker(scope: CoroutineScope, ctx: SpeechContext, gen: Long) {
        while (true) {
            val pick = synchronized(lock) {
                if (generation != gen) return
                takeNextLocked()
            }
            if (pick == null) {
                wake.receive()
                continue
            }
            play(scope, ctx, pick, gen)
        }
    }

    private fun takeNextLocked(): Pick? {
        if (paused || current != null) return null
        val pick = when {
            urgentLane.isNotEmpty() -> Pick(urgentLane.removeFirst(), Lane.URGENT)
            announceLane.isNotEmpty() -> Pick(announceLane.removeFirst(), Lane.ANNOUNCE)
            normalLane.isNotEmpty() && resumeBypass > 0 -> {
                resumeBypass--
                Pick(normalLane.removeFirst(), Lane.NORMAL)
            }
            normalLane.isNotEmpty() && bundleRemaining > 0 -> {
                bundleRemaining--
                Pick(normalLane.removeFirst(), Lane.NORMAL)
            }
            normalLane.isNotEmpty() && gapOpen -> Pick(normalLane.removeFirst(), Lane.NORMAL)
            else -> null
        } ?: return null
        current = pick
        interrupted = false
        _nowSpeaking.value = (pick.item as? Item.Msg)?.value?.let(::speakerOf)
        return pick
    }

    /**
     * 항목 하나를 별도 코루틴(playJob)에서 재생한다. pause는 이 코루틴을 취소한다.
     * LAZY로 만들어 lock 안에서 등록한 뒤 시작하므로, 꺼낸 직후~시작 전에 온 pause도 놓치지 않는다
     * (그 경우 재생 코루틴을 시작하지 않아 소리가 나지 않는다).
     */
    private suspend fun play(scope: CoroutineScope, ctx: SpeechContext, pick: Pick, gen: Long) {
        var madeSound = false
        var ok = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                madeSound = playItem(ctx, pick.item)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 어댑터 실패: 이 항목은 버리고 다음으로 간다. 소리가 났을 수 있으므로 간격은 적용한다
                madeSound = true
            }
        }
        var run = false
        try {
            run = synchronized(lock) {
                if (generation != gen || interrupted || discardCurrent) {
                    false
                } else {
                    playJob = job
                    true
                }
            }
            if (run) job.join() else job.cancel()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } finally {
            var finished: CompletableDeferred<Boolean>? = null
            synchronized(lock) {
                if (playJob === job) playJob = null
                if (generation == gen) {
                    current = null
                    _nowSpeaking.value = null
                    val discarded = discardCurrent
                    val wasInterrupted = interrupted
                    discardCurrent = false
                    interrupted = false
                    when {
                        // 확인 질문 취소: 다시 읽지 않는다. 소리가 시작됐으면 간격 적용(임시 T12)
                        discarded -> if (run) closeGapLocked(ctx, gen)
                        // pause: 맨 앞에 되돌리고 완료 처리하지 않는다
                        wasInterrupted -> requeueFrontLocked(pick)
                        else -> {
                            if (madeSound) closeGapLocked(ctx, gen)
                            finished = doneOf(pick)
                        }
                    }
                }
            }
            finished?.complete(ok)
        }
    }

    private fun doneOf(pick: Pick): CompletableDeferred<Boolean>? = (pick.item as? Item.Announce)?.done

    /** 재생 코루틴 안에서 실행. 소리를 냈으면 true */
    private suspend fun playItem(ctx: SpeechContext, item: Item): Boolean = when (item) {
        is Item.Msg -> playMessage(ctx, item.value)
        is Item.Announce -> speak(ctx, item.text)
        is Item.Music -> ctx.isHostCar() && speak(ctx, item.text)
    }

    /**
     * VOI-05(명세 5절-4): TEXT·BROADCAST는 앱이 안 보일 때만 소개 문장을 앞에 붙인다.
     * VOICE는 앱 표시와 무관하게 항상 소개 문장을 먼저 읽고 클립을 재생한다(팀원·CAR-B 합의 B, 2026-11-12).
     */
    private suspend fun playMessage(ctx: SpeechContext, m: IncomingMessage): Boolean {
        val msg = m.message
        return when (msg.kind) {
            MessageKind.VOICE -> {
                val file = m.clipFile ?: return false
                speak(ctx, SpeechTexts.intro(msg.sender.name).trimEnd())
                currentCoroutineContext().ensureActive()
                ctx.clipPlayer.play(file) // stop으로 끊긴 failure는 interrupted로 처리된다
                true
            }
            MessageKind.TEXT, MessageKind.BROADCAST -> {
                val text = msg.text ?: return false
                val visible = ctx.drive.isAppVisible.value
                speak(ctx, if (visible) text else SpeechTexts.intro(msg.sender.name) + text)
            }
        }
    }

    /** 취소됐으면 어댑터를 부르지 않는다 */
    private suspend fun speak(ctx: SpeechContext, text: String): Boolean {
        currentCoroutineContext().ensureActive()
        ctx.tts.speak(text)
        return true
    }

    private fun speakerOf(m: IncomingMessage): SpeakerInfo {
        val sender = m.message.sender
        return SpeakerInfo(carId = sender.carId, carName = m.carName, name = sender.name, kind = m.message.kind)
    }

    // ---------------------------------------------------------------- 간격·정차

    private fun closeGapLocked(ctx: SpeechContext, gen: Long) {
        gapOpen = false
        gapJob?.cancel()
        gapJob = scope?.launch {
            delay(ctx.config.minGapMs)
            synchronized(lock) { if (generation == gen) gapOpen = true }
            wake.trySend(Unit)
        }
    }

    private fun requeueFrontLocked(pick: Pick) {
        when (pick.lane) {
            Lane.URGENT -> urgentLane.addFirst(pick.item)
            Lane.ANNOUNCE -> announceLane.addFirst(pick.item)
            Lane.NORMAL -> {
                // 이미 차례를 받았다가 끊긴 항목이므로 resume 뒤에는 간격 없이 다시 읽는다
                normalLane.addFirst(pick.item)
                resumeBypass++
            }
        }
    }

    private fun onDriveState(state: DriveState, gen: Long) {
        val heldSnapshot: Map<String, Int>
        val c: SpeechContext
        synchronized(lock) {
            if (generation != gen) return
            val wasParked = lastDrive == DriveState.PARKED
            lastDrive = state
            if (state != DriveState.PARKED) {
                bundleRemaining = 0
                return
            }
            if (wasParked) return
            // MSG-02 묶어 읽기: 지금 대기 중인 일반 항목만 간격 없이
            bundleRemaining = normalLane.size
            heldSnapshot = held.filterValues { it >= 1 }
            c = ctx ?: return
        }
        // VOI-06: PARKED로 바뀔 때 차마다 한 번. 이름 조회(CAR-B 람다)는 lock 밖에서
        val notices = heldSnapshot.map { (carId, n) ->
            val name = c.carName(carId) ?: synchronized(lock) { heldNames[carId] } ?: carId
            SpeechTexts.heldNotice(name, n)
        }
        synchronized(lock) {
            if (generation != gen) return
            notices.forEach { announceLane.addLast(Item.Announce(it)) }
        }
        wake.trySend(Unit)
    }

    // ---------------------------------------------------------------- 보관

    /**
     * 명세 v1.9 5절-5(D-24): `mutes`에 byCarId=내 차·targetCarId=그 차인 항목이 없으면 그 차의 건수를 지운다.
     * 서버 보관함의 메시지는 건드리지 않는다(앱 안 건수만).
     */
    private fun dropUnmutedHeld(mutes: List<Mute>) {
        val c = synchronized(lock) { ctx } ?: return
        val my = c.myCarId() ?: return
        val stillMuted = mutes.filter { it.byCarId == my }.map { it.targetCarId }.toSet()
        synchronized(lock) {
            if (held.keys.retainAll(stillMuted)) {
                heldNames.keys.retainAll(stillMuted)
                publishHeldLocked()
            }
        }
    }

    private fun publishHeldLocked() {
        _heldCount.value = held.toMap()
    }

    private fun cancelJobsLocked() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        gapJob?.cancel()
        gapJob = null
        playJob?.cancel()
        playJob = null
    }
}
