package com.okkikki.kkiri.contract

// 명세 1.5. 값은 서버 JSON의 대문자 문자열과 같다. 바꾸거나 더하지 않는다.

enum class SessionState { RESERVED, ACTIVE, PAUSED, ENDED }
enum class CarRole { HOST, COHOST }
enum class CarStatus { CONNECTED, PAUSED, DISCONNECTED }
enum class DriveState { DRIVING, PARKED, UNKNOWN }
enum class Ignition { ON, OFF }
enum class ParticipantRole { LEADER, CREW }
enum class MessageKind { TEXT, BROADCAST, VOICE }
enum class DeliveryMode { SPEAK, HOLD, DISPLAY }
enum class WaypointScope { MY_CAR, PROPOSE_OTHERS }
enum class ProposalStatus { PENDING, ACCEPTED, DECLINED, EXPIRED }
enum class MusicAction { NEXT, PREVIOUS, PAUSE, PLAY }
enum class EndReason { HOST_ENDED, PAUSE_TIMEOUT, ACCOUNT_TTL, RESERVE_TIMEOUT }

// 명세 2절 설명(sender.type)과 4.3(snapshot.you.actorType)의 값
enum class SenderType { PARTICIPANT, CAR }
enum class ActorType { CAR, PARTICIPANT }
