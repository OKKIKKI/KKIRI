import { randomInt } from 'node:crypto';
import type { Principal } from '../auth.js';
import { config } from '../config.js';
import type {
  CarLook, CarRole, CarStatus, DriveState, Message, Music, Mute, Participant, Place, Proposal, Session, SessionState, Waypoint, You,
} from '../types.js';
import { isoNow, newId, newSecret } from '../util.js';
import { getCar } from './cars.js';
import type { MusicProvider } from '../adapters/music.js';

interface SessionCar {
  carId: string;
  role: CarRole;
  status: CarStatus;
  driveState: DriveState;
  /** R-47로 받은 마지막 모양. 메모리에만 둔다(세션과 함께 사라짐) */
  look: CarLook | null;
}

interface ParticipantRecord extends Omit<Participant, 'online'> {
  userId: string | null;
  /** 이 참가자의 열린 WebSocket 수. online = connections > 0 (명세 v1.1 2절) */
  connections: number;
}

/** 서버 내부 세션. 밖으로 내보낼 때는 반드시 toSession()으로 명세 2절 모양을 만든다. */
export interface SessionRecord {
  sessionId: string;
  name: string;
  state: SessionState;
  isAccountSession: boolean;
  hostCarId: string;
  destination: Place | null;
  joinCode: string;
  qrToken: string;
  qrExpiresAt: string;
  cars: SessionCar[];
  participants: ParticipantRecord[];
  waypoints: Waypoint[];
  proposals: Proposal[];
  mutes: Mute[];
  music: Music;
  createdAt: string;
  pausedAt: string | null;
  expiresAt: string | null;
  /** 예약 세션을 연 계정(R-14). 빠른 세션은 null */
  ownerUserId: string | null;
  /** 4.2 봉투의 seq. 마지막으로 보낸 이벤트 번호 */
  seq: number;
  /** 이 세션의 모든 메시지(세션 종료 시 파기). 스냅샷에는 나가지 않는다 */
  messages: Message[];
  /** carId → 그 차에 HOLD로 쌓인 메시지 ID (R-23·R-24) */
  held: Map<string, string[]>;
  /** `${보낸사람키}>${받는carId}` → 마지막 긴급 시각(ms). MSG-05 */
  lastUrgentAt: Map<string, number>;
  /** messageId → 음성 클립 본문(R-21 저장, R-25 받기). 세션 종료 시 파기 */
  clips: Map<string, { data: Buffer; mime: string }>;
  /** carId → 경유지 변경 기록(최근이 끝). R-33 되돌리기 */
  waypointHistory: Map<string, { action: 'ADDED' | 'REMOVED'; waypoint: Waypoint }[]>;
  /** 이 세션의 타이머(제안 만료 등). 세션 종료 시 모두 취소 */
  timers: Set<NodeJS.Timeout>;
  /** 세션 종료 타이머(일시정지 무입력·계정 세션 TTL). 하나만 둔다 */
  endTimer: NodeJS.Timeout | null;
  /** 마지막 "입력"(Q-006) 시각(ms) */
  lastActivityAt: number;
  /** 음악 연결(R-40). 토큰은 이 안에만 있고 클라이언트에 내려주지 않는다. 세션 종료 시 dispose */
  musicProvider: MusicProvider | null;
  /** 지금 재생 주기 조회(연결 방법이 pollIntervalMs를 줄 때만). 세션 종료 시 취소 */
  musicPoll: NodeJS.Timeout | null;
  /** participantId → 마지막 NEXT/PREVIOUS 시각(ms). R-43 연타 제한 */
  lastControlAt: Map<string, number>;
  /** 이 세션에서 재생된 곡 수(summary.trackCount) */
  trackCount: number;
}

const sessions = new Map<string, SessionRecord>();
const byJoinCode = new Map<string, string>();
const byQrToken = new Map<string, string>();
// 파기된 세션의 흔적(ID·코드만). SESSION_ENDED를 돌려주기 위해서만 남긴다.
const endedSessionIds = new Set<string>();
const endedJoinCodes = new Set<string>();
const endedQrTokens = new Set<string>();

function newJoinCode(): string {
  for (;;) {
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    if (!byJoinCode.has(code) && !endedJoinCodes.has(code)) return code;
  }
}

export function createSession(opts: {
  name: string;
  destination: Place | null;
  hostCarId: string;
  state: SessionState;
  isAccountSession: boolean;
  ownerUserId: string | null;
  hostStatus: CarStatus;
}): SessionRecord {
  const rec: SessionRecord = {
    sessionId: newId('ses_'),
    name: opts.name,
    state: opts.state,
    isAccountSession: opts.isAccountSession,
    hostCarId: opts.hostCarId,
    destination: opts.destination,
    joinCode: newJoinCode(),
    qrToken: newSecret('').slice(0, 16),
    qrExpiresAt: isoNow(config.qrTtlSec),
    cars: [{ carId: opts.hostCarId, role: 'HOST', status: opts.hostStatus, driveState: 'UNKNOWN', look: null }],
    participants: [],
    waypoints: [],
    proposals: [],
    mutes: [],
    music: { connected: false, locked: false, nowPlaying: null },
    createdAt: isoNow(),
    pausedAt: null,
    expiresAt: null,
    ownerUserId: opts.ownerUserId,
    seq: 0,
    messages: [],
    held: new Map(),
    lastUrgentAt: new Map(),
    clips: new Map(),
    waypointHistory: new Map(),
    timers: new Set(),
    endTimer: null,
    lastActivityAt: Date.now(),
    musicProvider: null,
    musicPoll: null,
    lastControlAt: new Map(),
    trackCount: 0,
  };
  sessions.set(rec.sessionId, rec);
  byJoinCode.set(rec.joinCode, rec.sessionId);
  byQrToken.set(rec.qrToken, rec.sessionId);
  return rec;
}

export type Lookup = { rec: SessionRecord } | { ended: true } | null;

export function findById(sessionId: string): Lookup {
  const rec = sessions.get(sessionId);
  if (rec) return { rec };
  return endedSessionIds.has(sessionId) ? { ended: true } : null;
}

export function findByJoinCode(code: string): Lookup {
  const id = byJoinCode.get(code);
  if (id) return { rec: sessions.get(id)! };
  return endedJoinCodes.has(code) ? { ended: true } : null;
}

export function findByQrToken(token: string): Lookup {
  const id = byQrToken.get(token);
  if (id) return { rec: sessions.get(id)! };
  return endedQrTokens.has(token) ? { ended: true } : null;
}

/**
 * 세션을 저장소에서 지운다(SES-04 파기). 세션 ID·접속코드·QR 토큰만 흔적으로 남겨
 * 이후 호출에 SESSION_ENDED를 돌려준다. 메시지·클립·참가자 등은 레코드와 함께 사라진다.
 */
export function removeSession(rec: SessionRecord): void {
  sessions.delete(rec.sessionId);
  byJoinCode.delete(rec.joinCode);
  byQrToken.delete(rec.qrToken);
  endedSessionIds.add(rec.sessionId);
  endedJoinCodes.add(rec.joinCode);
  endedQrTokens.add(rec.qrToken);
  rec.messages = [];
  rec.clips.clear();
  rec.held.clear();
  rec.participants = [];
  rec.waypoints = [];
  rec.proposals = [];
  rec.waypointHistory.clear();
  rec.lastUrgentAt.clear();
  if (rec.musicPoll) clearInterval(rec.musicPoll);
  rec.musicPoll = null;
  rec.musicProvider?.dispose();
  rec.musicProvider = null;
  rec.lastControlAt.clear();
}

/** 이 차가 속한 살아 있는 세션들(최근에 만든 것이 앞). */
export function sessionsOfCar(carId: string): SessionRecord[] {
  return [...sessions.values()]
    .filter((s) => s.state !== 'ENDED' && s.cars.some((c) => c.carId === carId))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function sessionCar(rec: SessionRecord, carId: string): SessionCar | undefined {
  return rec.cars.find((c) => c.carId === carId);
}

export function addCar(rec: SessionRecord, carId: string, role: CarRole): SessionCar {
  const car: SessionCar = { carId, role, status: 'CONNECTED', driveState: 'UNKNOWN', look: null };
  rec.cars.push(car);
  return car;
}

export function addParticipant(rec: SessionRecord, p: Omit<ParticipantRecord, 'participantId' | 'connections'>): ParticipantRecord {
  const par: ParticipantRecord = { participantId: newId('par_'), connections: 0, ...p };
  rec.participants.push(par);
  return par;
}

export function toCar(c: SessionCar) {
  const reg = getCar(c.carId);
  return {
    carId: c.carId,
    displayName: reg?.displayName ?? '',
    driverName: reg?.driverName ?? '',
    role: c.role,
    status: c.status,
    driveState: c.driveState,
    look: c.look,
  };
}

export function toParticipant(p: ParticipantRecord): Participant {
  return { participantId: p.participantId, name: p.name, role: p.role, carId: p.carId, isMember: p.isMember, online: p.connections > 0 };
}

/** WS 연결·해제 때 hub가 부른다. 바뀌어도 이벤트는 보내지 않는다(명세 v1.1 2절). */
export function trackParticipantConnection(rec: SessionRecord, participantId: string, delta: 1 | -1): void {
  const p = rec.participants.find((x) => x.participantId === participantId);
  if (p) p.connections = Math.max(0, p.connections + delta);
}

/** 명세 2절 Session 모양. 없는 값은 null, 빈 목록은 []. */
export function toSession(rec: SessionRecord): Session {
  return {
    sessionId: rec.sessionId,
    name: rec.name,
    state: rec.state,
    isAccountSession: rec.isAccountSession,
    hostCarId: rec.hostCarId,
    destination: rec.destination,
    joinCode: rec.joinCode,
    qr: { url: `${config.webBase}/j/${rec.qrToken}`, expiresAt: rec.qrExpiresAt },
    cars: rec.cars.map(toCar),
    participants: rec.participants.map(toParticipant),
    waypoints: rec.waypoints,
    proposals: rec.proposals.filter((p) => p.responses.some((r) => r.status === 'PENDING')),
    mutes: rec.mutes,
    music: rec.music,
    createdAt: rec.createdAt,
    pausedAt: rec.pausedAt,
    expiresAt: rec.expiresAt,
  };
}

/**
 * 이 토큰의 주인이 세션 소속이면 snapshot의 you를, 아니면 null.
 * 세션 안의 호출은 carToken·guestToken만 받는다. userToken은 세션 밖 전용(명세 v1.1 1.3).
 */
export function actorFor(rec: SessionRecord, p: Principal): You | null {
  if (p.kind === 'CAR') {
    const car = sessionCar(rec, p.carId);
    return car ? { actorType: 'CAR', carId: car.carId, participantId: null, role: car.role } : null;
  }
  if (p.kind !== 'GUEST' || p.sessionId !== rec.sessionId) return null;
  const par = rec.participants.find((x) => x.participantId === p.participantId);
  return par ? { actorType: 'PARTICIPANT', carId: par.carId, participantId: par.participantId, role: par.role } : null;
}
