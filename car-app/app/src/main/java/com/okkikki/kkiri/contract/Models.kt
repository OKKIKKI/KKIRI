package com.okkikki.kkiri.contract

// 명세 2절 데이터 모델. 필드 이름은 JSON 그대로(camelCase).
// null 가능한 필드는 2절 JSON에서 null로 적힌 것(destination, text, clip, pausedAt, expiresAt, albumImageUrl, nowPlaying)뿐이다.
// 시간은 ISO-8601 UTC 문자열, ID는 접두사가 붙은 문자열이다(명세 1.1).
// 인라인 객체의 클래스 이름(ParticipantRef, Sender, ProposalResponse, VoiceClip, NowPlaying, Qr)은 contract가 붙인 것이다.

data class Place(
    val placeId: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val distanceKm: Double,
)

data class Car(
    val carId: String,
    val displayName: String,
    val driverName: String,
    val role: CarRole,
    val status: CarStatus,
    val driveState: DriveState,
    /** v1.15: 그 차가 R-47로 알린 모양. 알린 적 없으면 null(기본 모양·가운데) */
    val look: CarLook? = null,
)

/** 명세 2절 CarLook. steerStep은 -2~2(음수=왼쪽), color는 #RRGGBB 또는 null */
data class CarLook(
    val steerStep: Int,
    val color: String?,
    val hat: Boolean,
    val balloon: Boolean,
    /** v1.16 캐릭터 id(없으면 null=곰) */
    val character: String? = null,
    /** v1.16 소품 id 목록. 비어 있으면 hat·balloon을 쓴다 */
    val items: List<String> = emptyList(),
)

data class Participant(
    val participantId: String,
    val name: String,
    val role: ParticipantRole,
    val carId: String,
    val isMember: Boolean,
    val online: Boolean,
)

data class ParticipantRef(val participantId: String, val name: String)

data class Waypoint(
    val waypointId: String,
    val carId: String,
    val place: Place,
    val addedBy: ParticipantRef,
    val createdAt: String,
)

data class ProposalResponse(val carId: String, val status: ProposalStatus)

data class Proposal(
    val proposalId: String,
    val place: Place,
    val fromCarId: String,
    val proposedBy: ParticipantRef,
    val responses: List<ProposalResponse>,
    val expiresAt: String,
)

/** `type`=PARTICIPANT(웹) 또는 CAR(차량 운전자, id=carId, name=driverName) */
data class Sender(val type: SenderType, val id: String, val name: String, val carId: String)

data class VoiceClip(val url: String, val durationMs: Long, val mime: String)

data class Message(
    val messageId: String,
    val kind: MessageKind,
    val sender: Sender,
    /** "ALL" 또는 carId */
    val target: String,
    /** VOICE면 null */
    val text: String?,
    /** VOICE가 아니면 null */
    val clip: VoiceClip?,
    val urgent: Boolean,
    val createdAt: String,
)

/** byCarId 차에서 targetCarId 차가 안 들린다 */
data class Mute(val byCarId: String, val targetCarId: String)

data class NowPlaying(
    val title: String,
    val artist: String,
    val albumImageUrl: String?,
    val isPlaying: Boolean,
)

data class Music(val connected: Boolean, val locked: Boolean, val nowPlaying: NowPlaying?)

data class Qr(val url: String, val expiresAt: String)

data class Session(
    val sessionId: String,
    val name: String,
    val state: SessionState,
    val isAccountSession: Boolean,
    val hostCarId: String,
    val destination: Place?,
    val joinCode: String,
    val qr: Qr,
    val cars: List<Car>,
    val participants: List<Participant>,
    /** 모든 차의 경유지 */
    val waypoints: List<Waypoint>,
    /** PENDING만 */
    val proposals: List<Proposal>,
    val mutes: List<Mute>,
    val music: Music,
    val createdAt: String,
    val pausedAt: String?,
    val expiresAt: String?,
)
