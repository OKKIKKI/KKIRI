// 명세 v1.3 1.5절 enum, 2절 데이터 모델, 4절 WebSocket 이벤트. 필드 이름·값은 명세 그대로 쓴다.
// 웹 전용 타입이다(차량 앱은 contract/, 백엔드는 자기 types.ts를 쓴다).

// ── 1.5 enum ──────────────────────────────────────────────
export type SessionState = 'RESERVED' | 'ACTIVE' | 'PAUSED' | 'ENDED';
export type CarRole = 'HOST' | 'COHOST';
export type CarStatus = 'CONNECTED' | 'PAUSED' | 'DISCONNECTED';
export type DriveState = 'DRIVING' | 'PARKED' | 'UNKNOWN';
export type ParticipantRole = 'LEADER' | 'CREW';
export type MessageKind = 'TEXT' | 'BROADCAST' | 'VOICE';
export type DeliveryMode = 'SPEAK' | 'HOLD' | 'DISPLAY';
export type WaypointScope = 'MY_CAR' | 'PROPOSE_OTHERS';
export type ProposalStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED';
export type MusicAction = 'NEXT' | 'PREVIOUS' | 'PAUSE' | 'PLAY';
export type EndReason = 'HOST_ENDED' | 'PAUSE_TIMEOUT' | 'ACCOUNT_TTL' | 'RESERVE_TIMEOUT';

// ── 2절 데이터 모델 ───────────────────────────────────────
export interface Place {
  placeId: string;
  name: string;
  address: string;
  lat: number;
  lng: number;
  distanceKm: number;
}

export interface Car {
  carId: string;
  displayName: string;
  driverName: string;
  role: CarRole;
  status: CarStatus;
  driveState: DriveState;
}

export interface Participant {
  participantId: string;
  name: string;
  role: ParticipantRole;
  carId: string;
  isMember: boolean;
  online: boolean;
}

export interface ParticipantRef {
  participantId: string;
  name: string;
}

export interface Waypoint {
  waypointId: string;
  carId: string;
  place: Place;
  addedBy: ParticipantRef;
  createdAt: string;
}

export interface ProposalResponse {
  carId: string;
  status: ProposalStatus;
}

export interface Proposal {
  proposalId: string;
  place: Place;
  fromCarId: string;
  proposedBy: ParticipantRef;
  responses: ProposalResponse[];
  expiresAt: string;
}

export interface Sender {
  type: 'PARTICIPANT' | 'CAR';
  id: string;
  name: string;
  carId: string;
}

export interface VoiceClip {
  url: string;
  durationMs: number;
  mime: string;
}

export interface Message {
  messageId: string;
  kind: MessageKind;
  sender: Sender;
  target: string; // "ALL" 또는 carId
  text: string | null;
  clip: VoiceClip | null;
  urgent: boolean;
  createdAt: string;
}

export interface Mute {
  byCarId: string;
  targetCarId: string;
}

export interface NowPlaying {
  title: string;
  artist: string;
  albumImageUrl: string | null;
  isPlaying: boolean;
}

export interface Music {
  connected: boolean;
  locked: boolean;
  nowPlaying: NowPlaying | null;
}

export interface Qr {
  url: string;
  expiresAt: string;
}

export interface Session {
  sessionId: string;
  name: string;
  state: SessionState;
  isAccountSession: boolean;
  hostCarId: string;
  destination: Place | null;
  joinCode: string;
  qr: Qr;
  cars: Car[];
  participants: Participant[];
  waypoints: Waypoint[];
  proposals: Proposal[];
  mutes: Mute[];
  music: Music;
  createdAt: string;
  pausedAt: string | null;
  expiresAt: string | null;
}

// ── 1.4 오류 형식 ─────────────────────────────────────────
export interface ErrorBody {
  error: { code: string; message: string; details: Record<string, unknown> | null };
}

// ── 3절 REST (1회차에 쓰는 것) ────────────────────────────
export interface JoinRequest {
  qrToken: string;
  name: string;
}

export interface JoinResponse {
  guestToken: string;
  participantId: string;
  session: Session;
}

export interface SessionResponse {
  session: Session;
}

/** R-20 요청. 웹은 kind=TEXT만 보낸다. */
export interface SendMessageRequest {
  kind: MessageKind;
  text: string;
  urgent: boolean;
  target: string; // "ALL" 또는 carId
}

export interface MessageResponse {
  message: Message;
}

// ── 3.2 계정 (운전자, userToken) ──────────────────────────
/** R-12 */
export interface MeResponse {
  userId: string;
  name: string;
}

/** R-13 (1차 목업: 서버 시드) */
export interface MyCar {
  carId: string;
  displayName: string;
}
export interface MyCarsResponse {
  cars: MyCar[];
}

/** R-14 요청 */
export interface ReserveRequest {
  carId: string;
  name: string;
  destination: Place | null;
}

/** R-15 (1차 목업: 고정 목록) */
export interface FriendCar {
  carId: string;
  displayName: string;
  ownerName: string;
}
export interface FriendCarsResponse {
  friendCars: FriendCar[];
}

// ── 3.5 음악 (Spotify, D-09 OPEN → 목업) ──────────────────
/** R-42 응답 */
export interface MusicResponse {
  music: Music;
}

/** R-44 결과 한 곡 */
export interface Track {
  uri: string;
  title: string;
  artist: string;
  albumImageUrl: string | null;
}
/** R-44 응답 (최대 10개) */
export interface TracksResponse {
  tracks: Track[];
}

/** R-45 요청 */
export interface QueueRequest {
  uri: string;
  title: string;
  artist: string;
}

/** R-30 응답 (1차 목업: distanceKm은 고정값) */
export interface PlacesResponse {
  places: Place[];
}

/** R-31 요청 */
export interface AddWaypointRequest {
  place: Place;
  scope: WaypointScope;
}

/** R-31 응답. scope=PROPOSE_OTHERS면 proposal에 값 */
export interface AddWaypointResponse {
  waypoint: Waypoint;
  proposal: Proposal | null;
}

// ── 4절 WebSocket ─────────────────────────────────────────
export interface You {
  actorType: 'CAR' | 'PARTICIPANT';
  carId: string | null;
  participantId: string | null;
  role: string | null;
}

export interface Summary {
  places: string[];
  cars: string[];
  trackCount: number;
}

export interface EventPayloads {
  'session.snapshot': { session: Session; you: You };
  'session.state_changed': { state: SessionState; pausedAt: string | null; expiresAt: string | null };
  'session.ended': { reason: EndReason; summary: Summary };
  'participant.joined': { participant: Participant };
  'participant.left': { participant: Participant };
  'car.joined': { car: Car };
  'car.status_changed': { carId: string; status: CarStatus; driveState: DriveState };
  'message.created': { message: Message; delivery: { mode: DeliveryMode } };
  'mute.changed': { mutes: Mute[] };
  'waypoint.added': { waypoint: Waypoint; byUndo: boolean };
  'waypoint.removed': { waypointId: string; carId: string; byUndo: boolean };
  'waypoint.proposed': { proposal: Proposal };
  'waypoint.proposal_resolved': { proposalId: string; carId: string; status: ProposalStatus };
  'music.now_playing': { music: Music };
  'music.queue_added': { title: string; artist: string; addedBy: { name: string } };
  'music.lock_changed': { locked: boolean };
}

export type EventType = keyof EventPayloads;

/** 4.2 봉투. 모르는 type도 이 모양으로 받고, 처리할 때 무시한다. */
export interface Envelope<T extends string = string> {
  type: T;
  sessionId: string;
  seq: number;
  ts: string;
  payload: T extends EventType ? EventPayloads[T] : unknown;
}

export type KnownEnvelope = { [K in EventType]: Envelope<K> }[EventType];
