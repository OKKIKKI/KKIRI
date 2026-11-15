package com.okkikki.kkiri.voice.debug

import com.okkikki.kkiri.contract.DeliveryMode
import com.okkikki.kkiri.contract.Message
import com.okkikki.kkiri.contract.MessageCreated
import com.okkikki.kkiri.contract.MessageKind
import com.okkikki.kkiri.contract.ParticipantRef
import com.okkikki.kkiri.contract.Place
import com.okkikki.kkiri.contract.Proposal
import com.okkikki.kkiri.contract.ProposalResponse
import com.okkikki.kkiri.contract.ProposalStatus
import com.okkikki.kkiri.contract.Sender
import com.okkikki.kkiri.contract.SenderType
import com.okkikki.kkiri.contract.ServerEvent
import com.okkikki.kkiri.contract.Session
import com.okkikki.kkiri.contract.VoiceClip
import com.okkikki.kkiri.contract.WaypointProposed
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 동작 확인용 입력(디버그 빌드 전용). **docs/fixtures가 아니다** — fixtures 재생은 CAR-A의
 * MockSessionRepository.playFixtureEvents를 쓴다. 여기서 만드는 이벤트는 fixtures에 없는 경우를 시험한다.
 *
 * - id는 모두 [ID_PREFIX]("dbg_")로 시작하고 seq는 -1(서버가 보낸 것이 아님).
 * - SPEAK 음성의 클립은 서버(R-25)에서 받지 않고 VoiceHubImpl이 녹음 목업 파일로 대신한다(디버그 빌드 + "dbg_" id일 때만).
 * - VoiceHubImpl.start가 [bind]로 지금 세션·내 차를 알려 주고, 이 [events]를 구독한다. 구독 전에 낸 것은 버려진다.
 */
object DebugTestInputs {
    const val ID_PREFIX = "dbg_"
    private const val DEBUG_SEQ = -1L
    private const val FALLBACK_OTHER_CAR = "car_B" // fixtures의 다른 차(세션을 모를 때만)
    private const val FALLBACK_MY_CAR = "car_A" // fixtures의 내 차(세션을 모를 때만)
    private const val PROPOSAL_EXPIRES_SEC = 60L // 표시용 expiresAt(D-14 값). 서버 만료 처리는 하지 않는다

    private val counter = AtomicInteger()
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    @Volatile private var session: () -> Session? = { null }
    @Volatile private var myCarId: () -> String? = { null }

    fun bind(session: () -> Session?, myCarId: () -> String?) {
        this.session = session
        this.myCarId = myCarId
    }

    fun isDebugMessage(messageId: String): Boolean = messageId.startsWith(ID_PREFIX)

    /** 다른 차에서 온 긴급 글자 메시지(SPEAK). 구독자가 없으면 false */
    fun emitUrgentMessage(): Boolean =
        emit(messageEvent(MessageKind.TEXT, text = "[디버그] 긴급 시험 메시지", clip = null, urgent = true))

    /** 다른 차에서 온 녹음형 무전(SPEAK, VOICE). 구독자가 없으면 false */
    fun emitSpeakVoice(): Boolean {
        val id = nextId("msg")
        val sid = sessionId()
        return emit(
            messageEvent(
                MessageKind.VOICE, text = null,
                clip = VoiceClip(url = "/v1/sessions/$sid/voice-clips/$id", durationMs = 2_500, mime = "audio/mp4"),
                urgent = false, id = id,
            ),
        )
    }

    /**
     * 다른 차가 낸 경유지 제안, 내 차 응답 PENDING(fixtures의 제안은 car_B 대상이라 내 차로는 시험할 수 없다).
     * 내 차를 모르면 fixtures의 내 차 car_A. 장소는 디버그용 가짜 장소(실제 장소 아님). 구독자가 없으면 false
     */
    fun emitProposalToMyCar(): Boolean {
        val (fromCarId, _) = otherCar()
        val n = counter.incrementAndGet()
        val proposal = Proposal(
            proposalId = "${ID_PREFIX}prp_$n",
            place = Place(
                placeId = "${ID_PREFIX}plc_$n",
                name = "디버그 시험 장소 $n",
                address = "(디버그, 실제 주소 아님)",
                lat = 0.0,
                lng = 0.0,
                distanceKm = 0.0,
            ),
            fromCarId = fromCarId,
            proposedBy = ParticipantRef(participantId = "${ID_PREFIX}par", name = "디버그"),
            responses = listOf(ProposalResponse(carId = myCar() ?: FALLBACK_MY_CAR, status = ProposalStatus.PENDING)),
            expiresAt = Instant.now().plusSeconds(PROPOSAL_EXPIRES_SEC).toString(),
        )
        return emit(WaypointProposed(sessionId(), DEBUG_SEQ, now(), proposal))
    }

    internal fun emit(event: ServerEvent): Boolean = _events.subscriptionCount.value > 0 && _events.tryEmit(event)

    internal fun nextId(kind: String) = "${ID_PREFIX}${kind}_${counter.incrementAndGet()}"

    internal fun sessionId(): String = session()?.sessionId ?: "${ID_PREFIX}ses"

    internal fun now(): String = Instant.now().toString()

    /** 내 차가 아닌 첫 차. 세션을 모르면 fixtures의 car_B */
    internal fun otherCar(): Pair<String, String> {
        val me = myCarId()
        val car = session()?.cars?.firstOrNull { it.carId != me }
        return if (car != null) car.carId to car.driverName else FALLBACK_OTHER_CAR to "(디버그)"
    }

    internal fun myCar(): String? = myCarId()

    private fun messageEvent(
        kind: MessageKind,
        text: String?,
        clip: VoiceClip?,
        urgent: Boolean,
        id: String = nextId("msg"),
    ): MessageCreated {
        val (carId, name) = otherCar()
        val message = Message(
            messageId = id,
            kind = kind,
            sender = Sender(type = SenderType.CAR, id = carId, name = name, carId = carId),
            target = myCarId() ?: "ALL",
            text = text,
            clip = clip,
            urgent = urgent,
            createdAt = now(),
        )
        return MessageCreated(sessionId(), DEBUG_SEQ, now(), message, DeliveryMode.SPEAK)
    }
}
