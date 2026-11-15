import placesFixture from '../../../docs/fixtures/places.json';
import restExamples from '../../../docs/fixtures/rest_examples.json';
import wsEvents from '../../../docs/fixtures/ws_events.json';
import { ApiError } from './client';
import type { ConnectionHandlers, KkiriApi, SessionConnection } from './KkiriApi';
import type {
  AddWaypointRequest,
  AddWaypointResponse,
  Place,
  PlacesResponse,
  Proposal,
  Session,
  Waypoint,
  Envelope,
  FriendCarsResponse,
  JoinRequest,
  JoinResponse,
  MeResponse,
  Message,
  MessageResponse,
  Music,
  MusicAction,
  MusicResponse,
  MyCarsResponse,
  NowPlaying,
  Participant,
  QueueRequest,
  ReserveRequest,
  SendMessageRequest,
  SessionResponse,
  Track,
  TracksResponse,
  You,
} from './types';

// 목업 구현: docs/fixtures/를 그대로 재생한다(VITE_USE_MOCK=1). 실제 구현과 같은 인터페이스.
// 목업에만 있는 동작은 이 파일 안에만 둔다. 화면 코드는 목업인지 모른다.
//
// 오류 확인용 qrToken(목업에서만): expired → QR_EXPIRED, ended → SESSION_ENDED, missing → SESSION_NOT_FOUND
// 메시지(R-20): 보내면 서버처럼 message.created 이벤트를 돌려준다. 긴급을 연달아 보내면 URGENT_RATE_LIMITED
//   (목업은 "음소거한 차가 있는지"를 따지지 않고 항상 제한한다)
// 장소 검색(R-30): docs/fixtures/places.json에서 이름 포함 검색, 빈 검색어면 전체.
// 경유지(R-31·R-32): 서버처럼 waypoint.added / waypoint.removed를 돌려준다.
//   PROPOSE_OTHERS면 다른 차들(fixtures 스냅샷 기준)에 제안을 만들고, 몇 초 뒤 그 차가 수락한 것처럼
//   waypoint.proposal_resolved(ACCEPTED) + 그 차의 waypoint.added를 보낸다.
// 계정(R-10~R-15): 로그인은 서버 없이 `{redirect}#userToken=ut_mock`으로 바로 돌아온다. userToken이 `bad`면 R-12가 401.
//   R-14로 미리 연 세션(RESERVED)은 이 탭의 sessionStorage에 기억하고, R-06(userToken)으로 리더가 들어가면
//   그 세션의 스냅샷을 보낸다.
// 이벤트 재생(개발자 도구 콘솔): kkiriMock.replay() / kkiriMock.emit({ type, sessionId, seq, ts, payload })
//   kkiriMock.crewJoin('민지') — 동승자 입장, kkiriMock.attach() — 차가 연결됨(RESERVED → ACTIVE)
// 음악(R-40·R-42~R-45): fixtures 스냅샷 music(연결됨)에서 시작. 이전/다음 곡은 3초에 1회, 잠금이면 MUSIC_LOCKED.
//   kkiriMock.lock(true|false) — 운전자 잠금, kkiriMock.disconnectMusic() — 미연결 화면 확인

const MOCK_DELAY_MS = 300;
const REPLAY_GAP_MS = 800;
/** 목업 전용. 서버의 URGENT_LIMIT_SEC 기본값(명세 1.2)을 흉내 낸다. */
const MOCK_URGENT_LIMIT_SEC = 30;
/** 목업 전용. 서버의 PROPOSAL_TIMEOUT_SEC 기본값(명세 1.2, D-14) — 제안 expiresAt 계산에만 쓴다. */
const MOCK_PROPOSAL_TIMEOUT_SEC = 60;
/** 목업 전용. 제안받은 차가 수락하기까지 걸리는 시간. */
const MOCK_PROPOSAL_ACCEPT_MS = 5_000;

const PLACES = placesFixture as Place[];
let wptCount = 0;
let prpCount = 0;

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const isoNow = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');

const MOCK_ERRORS: Record<string, ApiError> = {
  expired: new ApiError(410, 'QR_EXPIRED', 'QR이 만료됐어요', null),
  ended: new ApiError(410, 'SESSION_ENDED', '끝난 세션', null),
  missing: new ApiError(404, 'SESSION_NOT_FOUND', '세션을 찾을 수 없어요', null),
};

const EVENTS = wsEvents as unknown as Envelope[];
/** 목업의 "나": fixtures 첫 이벤트(session.snapshot)의 세션과 you */
const SNAPSHOT = (EVENTS[0] as Envelope<'session.snapshot'>).payload as {
  session: Session;
  you: { carId: string; participantId: string };
};

// 지금 열려 있는 목업 연결(서버가 이벤트를 보내는 대상)과 마지막 seq
let active: ConnectionHandlers | null = null;
let seq = 0;
let msgCount = 0;
let lastUrgentAt = 0;
let crewCount = 0;

// 미리 연 세션(R-14 → R-06 리더 입장). 목업 전용이라 새로고침해도 이어지도록 이 탭에만 둔다.
const RESERVED_KEY = 'kkiri.mock.reserved';
const LEADER_TOKEN = 'gt_yyy'; // fixtures R-06_member
interface Reserved {
  session: Session;
  you: You | null;
}
function loadReserved(): Reserved | null {
  try {
    const raw = sessionStorage.getItem(RESERVED_KEY);
    return raw ? (JSON.parse(raw) as Reserved) : null;
  } catch {
    return null;
  }
}
function saveReserved(r: Reserved) {
  try {
    sessionStorage.setItem(RESERVED_KEY, JSON.stringify(r));
  } catch {
    /* 목업: 저장 못 하면 새로고침 때 fixtures 스냅샷으로 돌아간다 */
  }
}

function push(env: Envelope) {
  seq = Math.max(seq, env.seq);
  active?.onEnvelope(clone(env));
}

function pushEvent(type: string, payload: unknown) {
  push({ type, sessionId: 'ses_01', seq: seq + 1, ts: isoNow(), payload } as Envelope);
}

// ── 음악(R-40·R-42~R-45) 목업 상태. 시작값은 fixtures 스냅샷의 music ──
/** 목업 전용. 서버의 MUSIC_CONTROL_LIMIT_SEC(Q-015 제안, 명세 3.5 "3초에 1회")를 흉내 낸다. */
const MOCK_CONTROL_LIMIT_MS = 3_000;
const TRACKS = (restExamples['R-44'].response.tracks as Track[]).concat([
  { uri: 'spotify:track:MOCK1', title: '밤편지', artist: '아이유', albumImageUrl: null },
  { uri: 'spotify:track:MOCK2', title: '여행', artist: '볼빨간사춘기', albumImageUrl: null },
]);
let music: Music = clone(SNAPSHOT.session.music);
const playlist: NowPlaying[] = TRACKS.map((t) => ({ title: t.title, artist: t.artist, albumImageUrl: null, isPlaying: true }));
let playIndex = Math.max(0, playlist.findIndex((p) => p.title === music.nowPlaying?.title));
let lastSkipAt = 0;

function pushMusic() {
  pushEvent('music.now_playing', { music: clone(music) });
}

interface MockControl {
  replay(gapMs?: number): void;
  emit(env: Envelope): void;
  crewJoin(name?: string): void;
  attach(): void;
  /** 운전자가 음악을 잠금/해제(R-46 대신) */
  lock(locked?: boolean): void;
  /** Spotify 연결을 끊은 상태로 만들기(미연결 화면 확인용) */
  disconnectMusic(): void;
}

declare global {
  interface Window {
    kkiriMock?: MockControl;
  }
}

function connectSession(sessionId: string, token: string, h: ConnectionHandlers): SessionConnection {
  const timers: ReturnType<typeof setTimeout>[] = [];
  let closed = false;
  const reserved = token === LEADER_TOKEN ? loadReserved() : null;

  h.onStatus?.('connecting');
  timers.push(
    setTimeout(() => {
      if (closed) return;
      active = h;
      h.onStatus?.('open');
      // 연결 직후 session.snapshot (4.1). 리더 토큰이면 미리 연 세션, 아니면 fixtures.
      if (reserved?.you) {
        push({
          type: 'session.snapshot',
          sessionId,
          seq,
          ts: isoNow(),
          payload: { session: reserved.session, you: reserved.you },
        } as Envelope);
      } else {
        push(EVENTS[0]);
      }
    }, MOCK_DELAY_MS),
  );

  const hostCarId = reserved?.session.hostCarId ?? SNAPSHOT.session.hostCarId;
  window.kkiriMock = {
    replay(gapMs = REPLAY_GAP_MS) {
      EVENTS.slice(1).forEach((env, i) => timers.push(setTimeout(() => push(env), gapMs * (i + 1))));
    },
    emit: push,
    crewJoin(name = '동승자') {
      crewCount += 1;
      const participant: Participant = {
        participantId: `par_mock_${crewCount}`,
        name,
        role: 'CREW',
        carId: hostCarId,
        isMember: false,
        online: true,
      };
      pushEvent('participant.joined', { participant });
    },
    attach() {
      pushEvent('session.state_changed', { state: 'ACTIVE', pausedAt: null, expiresAt: null });
    },
    lock(locked = true) {
      music = { ...music, locked };
      pushEvent('music.lock_changed', { locked });
    },
    disconnectMusic() {
      music = { connected: false, locked: false, nowPlaying: null };
      pushMusic();
    },
  };

  return {
    close() {
      closed = true;
      if (active === h) active = null;
      timers.forEach(clearTimeout);
    },
  };
}

export const mockApi: KkiriApi = {
  kakaoLoginUrl: (redirect: string) => `${redirect}#userToken=ut_mock`,

  async getMe(userToken: string): Promise<MeResponse> {
    await wait(MOCK_DELAY_MS);
    if (userToken === 'bad') throw new ApiError(401, 'UNAUTHORIZED', '토큰 없음·만료', null);
    return clone(restExamples['R-12'].response);
  },

  async getMyCars(_userToken: string): Promise<MyCarsResponse> {
    await wait(MOCK_DELAY_MS);
    return clone(restExamples['R-13'].response);
  },

  async reserveSession(_userToken: string, req: ReserveRequest): Promise<SessionResponse> {
    await wait(MOCK_DELAY_MS);
    const session = clone(restExamples['R-14'].response.session) as unknown as Session;
    session.name = req.name;
    session.hostCarId = req.carId;
    session.destination = req.destination;
    session.cars = session.cars.map((c) => ({ ...c, carId: req.carId }));
    saveReserved({ session, you: null });
    return { session: clone(session) };
  },

  async getFriendCars(_userToken: string): Promise<FriendCarsResponse> {
    await wait(MOCK_DELAY_MS);
    return clone(restExamples['R-15'].response);
  },

  async joinSession(req: JoinRequest, userToken: string | null): Promise<JoinResponse> {
    await wait(MOCK_DELAY_MS);
    const err = MOCK_ERRORS[req.qrToken];
    if (err) throw err;
    // 로그인한 운전자가 자기가 미리 연 세션에 들어가면 LEADER (fixtures R-06_member)
    const reserved = userToken ? loadReserved() : null;
    if (reserved && reserved.session.qr.url.endsWith(`/j/${req.qrToken}`)) {
      const leader: Participant = {
        participantId: 'par_00',
        name: req.name,
        role: 'LEADER',
        carId: reserved.session.hostCarId,
        isMember: true,
        online: true,
      };
      reserved.session.participants = [...reserved.session.participants.filter((p) => p.participantId !== 'par_00'), leader];
      reserved.you = { actorType: 'PARTICIPANT', carId: leader.carId, participantId: leader.participantId, role: 'LEADER' };
      saveReserved(reserved);
      return { guestToken: LEADER_TOKEN, participantId: leader.participantId, session: clone(reserved.session) };
    }
    return clone(restExamples['R-06'].response) as unknown as JoinResponse;
  },

  async getSession(_sessionId: string, _token: string): Promise<SessionResponse> {
    await wait(MOCK_DELAY_MS);
    return clone(restExamples['R-07'].response) as unknown as SessionResponse;
  },

  async sendMessage(_sessionId: string, _token: string, req: SendMessageRequest): Promise<MessageResponse> {
    await wait(MOCK_DELAY_MS);
    if (req.urgent) {
      const elapsedSec = (Date.now() - lastUrgentAt) / 1000;
      if (elapsedSec < MOCK_URGENT_LIMIT_SEC) {
        const retryAfterSec = Math.ceil(MOCK_URGENT_LIMIT_SEC - elapsedSec);
        throw new ApiError(429, 'URGENT_RATE_LIMITED', '긴급 메시지 제한', { retryAfterSec });
      }
      lastUrgentAt = Date.now();
    }
    // fixtures R-20 응답 모양에 요청 값을 채운다. 보낸 사람은 fixtures의 나(par_01 민지).
    const base = clone(restExamples['R-20'].response.message) as unknown as Message;
    msgCount += 1;
    const message: Message = {
      ...base,
      messageId: `msg_mock_${msgCount}`,
      kind: req.kind,
      text: req.text,
      urgent: req.urgent,
      target: req.target,
      createdAt: isoNow(),
    };
    setTimeout(() => pushEvent('message.created', { message, delivery: { mode: 'DISPLAY' } }), 0);
    return { message: clone(message) };
  },

  async searchPlaces(_sessionId: string, _token: string, query: string): Promise<PlacesResponse> {
    await wait(MOCK_DELAY_MS);
    const q = query.trim();
    return { places: clone(q ? PLACES.filter((p) => p.name.includes(q)) : PLACES) };
  },

  async addWaypoint(_sessionId: string, _token: string, req: AddWaypointRequest): Promise<AddWaypointResponse> {
    await wait(MOCK_DELAY_MS);
    const { session, you } = SNAPSHOT;
    const me = session.participants.find((p) => p.participantId === you.participantId);
    const addedBy = { participantId: you.participantId, name: me?.name ?? '' };
    const newWaypoint = (carId: string): Waypoint => {
      wptCount += 1;
      return { waypointId: `wpt_mock_${wptCount}`, carId, place: clone(req.place), addedBy, createdAt: isoNow() };
    };

    const waypoint = newWaypoint(you.carId);
    let proposal: Proposal | null = null;
    if (req.scope === 'PROPOSE_OTHERS') {
      prpCount += 1;
      const others = session.cars.filter((c) => c.carId !== you.carId);
      proposal = {
        proposalId: `prp_mock_${prpCount}`,
        place: clone(req.place),
        fromCarId: you.carId,
        proposedBy: addedBy,
        responses: others.map((c) => ({ carId: c.carId, status: 'PENDING' })),
        expiresAt: new Date(Date.now() + MOCK_PROPOSAL_TIMEOUT_SEC * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
      };
    }

    const p = proposal;
    setTimeout(() => {
      pushEvent('waypoint.added', { waypoint, byUndo: false });
      if (!p) return;
      pushEvent('waypoint.proposed', { proposal: p });
      setTimeout(() => {
        for (const r of p.responses) {
          pushEvent('waypoint.proposal_resolved', { proposalId: p.proposalId, carId: r.carId, status: 'ACCEPTED' });
          pushEvent('waypoint.added', { waypoint: newWaypoint(r.carId), byUndo: false });
        }
      }, MOCK_PROPOSAL_ACCEPT_MS);
    }, 0);
    return { waypoint: clone(waypoint), proposal: clone(proposal) };
  },

  async removeWaypoint(_sessionId: string, _token: string, waypointId: string): Promise<void> {
    await wait(MOCK_DELAY_MS);
    setTimeout(() => pushEvent('waypoint.removed', { waypointId, carId: SNAPSHOT.you.carId, byUndo: false }), 0);
  },

  // R-40 목업: Spotify 로그인 없이 바로 연결된 것으로 하고 redirect로 돌아간다(BE 목업과 같은 동작).
  spotifyConnectUrl(_sessionId: string, _token: string, redirect: string): string {
    music = { connected: true, locked: false, nowPlaying: clone(playlist[playIndex]) };
    return redirect;
  },

  async getMusic(_sessionId: string, _token: string): Promise<MusicResponse> {
    await wait(MOCK_DELAY_MS);
    return { music: clone(music) };
  },

  async controlMusic(_sessionId: string, _token: string, action: MusicAction): Promise<void> {
    await wait(MOCK_DELAY_MS);
    if (!music.connected) throw new ApiError(409, 'MUSIC_NOT_CONNECTED', 'Spotify 미연결', null);
    if (music.locked) throw new ApiError(423, 'MUSIC_LOCKED', '운전자가 음악을 잠금', null);
    if (action === 'NEXT' || action === 'PREVIOUS') {
      if (Date.now() - lastSkipAt < MOCK_CONTROL_LIMIT_MS) {
        throw new ApiError(429, 'CONTROL_RATE_LIMITED', '음악 다음 곡 연타 제한', null);
      }
      lastSkipAt = Date.now();
      playIndex = (playIndex + (action === 'NEXT' ? 1 : playlist.length - 1)) % playlist.length;
      music = { ...music, nowPlaying: { ...playlist[playIndex], isPlaying: true } };
    } else if (music.nowPlaying) {
      music = { ...music, nowPlaying: { ...music.nowPlaying, isPlaying: action === 'PLAY' } };
    }
    setTimeout(pushMusic, 0); // 서버는 Spotify 조회 후 곡이 바뀌면 music.now_playing을 보낸다
  },

  async searchTracks(_sessionId: string, _token: string, q: string): Promise<TracksResponse> {
    await wait(MOCK_DELAY_MS);
    const k = q.trim().toLowerCase();
    const found = TRACKS.filter((t) => !k || `${t.title} ${t.artist}`.toLowerCase().includes(k));
    return { tracks: clone(found.slice(0, 10)) };
  },

  async queueTrack(_sessionId: string, _token: string, req: QueueRequest): Promise<void> {
    await wait(MOCK_DELAY_MS);
    if (!music.connected) throw new ApiError(409, 'MUSIC_NOT_CONNECTED', 'Spotify 미연결', null);
    if (music.locked) throw new ApiError(423, 'MUSIC_LOCKED', '운전자가 음악을 잠금', null);
    playlist.splice(playIndex + 1, 0, { title: req.title, artist: req.artist, albumImageUrl: null, isPlaying: true });
    const me = SNAPSHOT.session.participants.find((p) => p.participantId === SNAPSHOT.you.participantId);
    setTimeout(() => pushEvent('music.queue_added', { title: req.title, artist: req.artist, addedBy: { name: me?.name ?? '' } }), 0);
  },

  connectSession,
};
