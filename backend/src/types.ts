/** 명세 1.5절 enum, 2절 데이터 모델. 필드 이름은 명세 그대로. */

export type SessionState = 'RESERVED' | 'ACTIVE' | 'PAUSED' | 'ENDED';
export type CarRole = 'HOST' | 'COHOST';
export type CarStatus = 'CONNECTED' | 'PAUSED' | 'DISCONNECTED';
export type DriveState = 'DRIVING' | 'PARKED' | 'UNKNOWN';
export type ParticipantRole = 'LEADER' | 'CREW';

export interface Place {
  placeId: string;
  name: string;
  address: string;
  lat: number;
  lng: number;
  distanceKm: number;
}

/** 광장에서 그 차를 어떻게 그릴지(명세 v1.15 2절) */
export interface CarLook {
  steerStep: number;
  color: string | null;
  hat: boolean;
  balloon: boolean;
  /** v1.16: 캐릭터 id 또는 null */
  character: string | null;
  /** v1.16: 소품 id 목록(최대 4) */
  items: string[];
}

export interface Car {
  carId: string;
  displayName: string;
  driverName: string;
  role: CarRole;
  status: CarStatus;
  driveState: DriveState;
  look: CarLook | null;
}

export interface Participant {
  participantId: string;
  name: string;
  role: ParticipantRole;
  carId: string;
  isMember: boolean;
  online: boolean;
}

export interface Waypoint {
  waypointId: string;
  carId: string;
  place: Place;
  addedBy: { participantId: string; name: string };
  createdAt: string;
}

export interface Proposal {
  proposalId: string;
  place: Place;
  fromCarId: string;
  proposedBy: { participantId: string; name: string };
  responses: { carId: string; status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' }[];
  expiresAt: string;
}

export type MessageKind = 'TEXT' | 'BROADCAST' | 'VOICE';
export type DeliveryMode = 'SPEAK' | 'HOLD' | 'DISPLAY';

export interface Message {
  messageId: string;
  kind: MessageKind;
  /** PARTICIPANT(웹) 또는 CAR(id=carId, name=driverName) */
  sender: { type: 'PARTICIPANT' | 'CAR'; id: string; name: string; carId: string };
  /** "ALL" 또는 carId */
  target: string;
  text: string | null;
  clip: { url: string; durationMs: number; mime: string } | null;
  urgent: boolean;
  createdAt: string;
}

export interface Mute {
  byCarId: string;
  targetCarId: string;
}

export interface Music {
  connected: boolean;
  locked: boolean;
  nowPlaying: { title: string; artist: string; albumImageUrl: string | null; isPlaying: boolean } | null;
}

export interface Session {
  sessionId: string;
  name: string;
  state: SessionState;
  isAccountSession: boolean;
  hostCarId: string;
  destination: Place | null;
  joinCode: string;
  qr: { url: string; expiresAt: string };
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

/** 4.3 session.snapshot의 you */
export interface You {
  actorType: 'CAR' | 'PARTICIPANT';
  carId: string;
  participantId: string | null;
  role: CarRole | ParticipantRole;
}
