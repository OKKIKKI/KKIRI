package com.okkikki.kkiri.contract

// 명세 4.3 서버 → 클라이언트 이벤트. 이벤트 1개 = 타입 1개. 공통 값은 4.2 봉투(sessionId, seq, ts).
// 모르는 type은 Unknown으로 받아 무시한다(에러 아님, 명세 1.1).

sealed interface ServerEvent {
    val sessionId: String
    val seq: Long
    val ts: String
}

/** snapshot.you */
data class You(val actorType: ActorType, val carId: String?, val participantId: String?, val role: String?)

/** session.ended.summary */
data class Summary(val places: List<String>, val cars: List<String>, val trackCount: Int)

/** music.queue_added.addedBy */
data class NameRef(val name: String)

data class SessionSnapshot(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val session: Session, val you: You,
) : ServerEvent

data class SessionStateChanged(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val state: SessionState, val pausedAt: String?, val expiresAt: String?,
) : ServerEvent

data class SessionEnded(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val reason: EndReason, val summary: Summary,
) : ServerEvent

data class ParticipantJoined(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val participant: Participant,
) : ServerEvent

data class ParticipantLeft(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val participant: Participant,
) : ServerEvent

data class CarJoined(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val car: Car,
) : ServerEvent

data class CarStatusChanged(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val carId: String, val status: CarStatus, val driveState: DriveState,
) : ServerEvent

data class CarLookChanged(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val carId: String, val look: CarLook,
) : ServerEvent

/** payload의 delivery.mode는 deliveryMode로 펼친다 */
data class MessageCreated(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val message: Message, val deliveryMode: DeliveryMode,
) : ServerEvent

data class MuteChanged(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val mutes: List<Mute>,
) : ServerEvent

data class WaypointAdded(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val waypoint: Waypoint, val byUndo: Boolean,
) : ServerEvent

data class WaypointRemoved(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val waypointId: String, val carId: String, val byUndo: Boolean,
) : ServerEvent

data class WaypointProposed(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val proposal: Proposal,
) : ServerEvent

data class WaypointProposalResolved(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val proposalId: String, val carId: String, val status: ProposalStatus,
) : ServerEvent

data class MusicNowPlaying(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val music: Music,
) : ServerEvent

data class MusicQueueAdded(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val title: String, val artist: String, val addedBy: NameRef,
) : ServerEvent

data class MusicLockChanged(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val locked: Boolean,
) : ServerEvent

data class Unknown(
    override val sessionId: String, override val seq: Long, override val ts: String,
    val type: String,
) : ServerEvent
