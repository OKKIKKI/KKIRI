import type { Envelope, EventPayloads, KnownEnvelope, Message, Session, Summary, EndReason, You } from '../api';

// 화면 상태는 서버 스냅샷과 이벤트로만 만든다(docs/scope/WEB.md 6절).
// session.snapshot → 전체 교체, 나머지 이벤트 → 부분 갱신, 모르는 이벤트 → 무시(명세 1.1, 4.1).

export interface SessionViewState {
  session: Session | null;
  you: You | null;
  /** 스냅샷에는 메시지가 없어 이벤트로 받은 것만 쌓는다(웹은 항상 DISPLAY). */
  messages: Message[];
  ended: { reason: EndReason; summary: Summary } | null;
  lastSeq: number;
  /** 마지막으로 받은 music.queue_added(토스트용). seq로 새 이벤트인지 구분한다. 세션 상태는 바꾸지 않는다. */
  lastQueueAdded: { seq: number; title: string; artist: string; addedByName: string } | null;
}

export const initialSessionState: SessionViewState = {
  session: null,
  you: null,
  messages: [],
  ended: null,
  lastSeq: 0,
  lastQueueAdded: null,
};

const KNOWN_TYPES: ReadonlySet<string> = new Set<keyof EventPayloads>([
  'session.snapshot',
  'session.state_changed',
  'session.ended',
  'participant.joined',
  'participant.left',
  'car.joined',
  'car.status_changed',
  'message.created',
  'mute.changed',
  'waypoint.added',
  'waypoint.removed',
  'waypoint.proposed',
  'waypoint.proposal_resolved',
  'music.now_playing',
  'music.queue_added',
  'music.lock_changed',
]);

function isKnown(env: Envelope): env is KnownEnvelope {
  return KNOWN_TYPES.has(env.type);
}

/** id가 같은 항목은 교체, 없으면 뒤에 붙인다. */
function upsert<T>(list: T[], item: T, sameId: (a: T) => boolean): T[] {
  return list.some(sameId) ? list.map((x) => (sameId(x) ? item : x)) : [...list, item];
}

export function sessionReducer(state: SessionViewState, env: Envelope): SessionViewState {
  if (!isKnown(env)) return state;

  if (env.type === 'session.snapshot') {
    // 재연결 때도 스냅샷이 다시 온다. 이벤트 재전송은 없다(4.1).
    return {
      ...state,
      session: env.payload.session,
      you: env.payload.you,
      lastSeq: env.seq,
    };
  }

  // 스냅샷 전 이벤트, 이미 반영한 seq는 무시한다.
  const s = state.session;
  if (!s || env.seq <= state.lastSeq) return state;
  const next = (session: Session, extra: Partial<SessionViewState> = {}): SessionViewState => ({
    ...state,
    ...extra,
    session,
    lastSeq: env.seq,
  });

  switch (env.type) {
    case 'session.state_changed': {
      const { state: st, pausedAt, expiresAt } = env.payload;
      return next({ ...s, state: st, pausedAt, expiresAt });
    }
    case 'session.ended':
      return next({ ...s, state: 'ENDED' }, { ended: env.payload });
    case 'participant.joined': {
      const p = env.payload.participant;
      return next({ ...s, participants: upsert(s.participants, p, (x) => x.participantId === p.participantId) });
    }
    case 'participant.left': {
      const id = env.payload.participant.participantId;
      return next({ ...s, participants: s.participants.filter((x) => x.participantId !== id) });
    }
    case 'car.joined': {
      const c = env.payload.car;
      return next({ ...s, cars: upsert(s.cars, c, (x) => x.carId === c.carId) });
    }
    case 'car.status_changed': {
      const { carId, status, driveState } = env.payload;
      return next({ ...s, cars: s.cars.map((c) => (c.carId === carId ? { ...c, status, driveState } : c)) });
    }
    case 'message.created': {
      const m = env.payload.message;
      return next(s, { messages: upsert(state.messages, m, (x) => x.messageId === m.messageId) });
    }
    case 'mute.changed':
      return next({ ...s, mutes: env.payload.mutes });
    case 'waypoint.added': {
      const w = env.payload.waypoint;
      return next({ ...s, waypoints: upsert(s.waypoints, w, (x) => x.waypointId === w.waypointId) });
    }
    case 'waypoint.removed': {
      const id = env.payload.waypointId;
      return next({ ...s, waypoints: s.waypoints.filter((x) => x.waypointId !== id) });
    }
    case 'waypoint.proposed': {
      const p = env.payload.proposal;
      return next({ ...s, proposals: upsert(s.proposals, p, (x) => x.proposalId === p.proposalId) });
    }
    case 'waypoint.proposal_resolved': {
      // 결과 표시를 위해 목록에 남겨 두고 해당 차의 응답만 바꾼다(스냅샷에는 PENDING만 온다).
      const { proposalId, carId, status } = env.payload;
      return next({
        ...s,
        proposals: s.proposals.map((p) =>
          p.proposalId !== proposalId
            ? p
            : { ...p, responses: upsert(p.responses, { carId, status }, (r) => r.carId === carId) },
        ),
      });
    }
    case 'music.now_playing':
      return next({ ...s, music: env.payload.music });
    case 'music.lock_changed':
      return next({ ...s, music: { ...s.music, locked: env.payload.locked } });
    case 'music.queue_added': {
      // 세션 상태는 바뀌지 않는다. 음악 탭이 토스트를 띄우도록 마지막 이벤트만 남긴다(WEB-05).
      const { title, artist, addedBy } = env.payload;
      return next(s, { lastQueueAdded: { seq: env.seq, title, artist, addedByName: addedBy.name } });
    }
  }
  return state;
}
