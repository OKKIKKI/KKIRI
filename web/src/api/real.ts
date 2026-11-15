import { API_BASE, WS_BASE, request } from './client';
import type { ConnectionHandlers, KkiriApi, SessionConnection } from './KkiriApi';
import type {
  AddWaypointRequest,
  AddWaypointResponse,
  FriendCarsResponse,
  MeResponse,
  MusicAction,
  MusicResponse,
  MyCarsResponse,
  QueueRequest,
  ReserveRequest,
  TracksResponse,
  PlacesResponse,
  Envelope,
  JoinRequest,
  JoinResponse,
  MessageResponse,
  SendMessageRequest,
  SessionResponse,
} from './types';

// 명세 4.1에 정해진 값(환경변수 대상 아님): ping 30초, 재연결 1초부터 두 배씩 최대 10초.
const PING_INTERVAL_MS = 30_000;
const RECONNECT_BASE_MS = 1_000;
const RECONNECT_MAX_MS = 10_000;

function isEnvelope(v: unknown): v is Envelope {
  const e = v as Envelope | null;
  return typeof e === 'object' && e !== null && typeof e.type === 'string' && typeof e.seq === 'number';
}

function connectSession(sessionId: string, token: string, h: ConnectionHandlers): SessionConnection {
  let socket: WebSocket | null = null;
  let attempt = 0;
  let stopped = false;
  let pingTimer: ReturnType<typeof setInterval> | undefined;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;

  const open = () => {
    if (stopped) return;
    h.onStatus?.('connecting');
    const url = `${WS_BASE}/v1/ws?sessionId=${encodeURIComponent(sessionId)}&token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(url);
    socket = ws;

    ws.onopen = () => {
      attempt = 0;
      h.onStatus?.('open');
      // 클라이언트 → 서버는 ping만 보낸다. 상태 변경은 전부 REST(명세 4.1).
      pingTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ping' }));
      }, PING_INTERVAL_MS);
    };

    ws.onmessage = (ev: MessageEvent) => {
      let data: unknown;
      try {
        data = JSON.parse(String(ev.data));
      } catch {
        console.warn('[ws] JSON이 아닌 메시지를 무시함');
        return;
      }
      if (isEnvelope(data)) h.onEnvelope(data);
    };

    ws.onclose = () => {
      clearInterval(pingTimer);
      socket = null;
      if (stopped) return;
      h.onStatus?.('closed');
      void scheduleReconnect();
    };
  };

  const scheduleReconnect = async () => {
    let ok = true;
    try {
      ok = await h.canReconnect();
    } catch (e) {
      console.warn('[ws] 재연결 전 확인 실패, 재연결은 계속함', e);
    }
    if (stopped) return;
    if (!ok) {
      stopped = true;
      return;
    }
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** attempt, RECONNECT_MAX_MS);
    attempt += 1;
    retryTimer = setTimeout(open, delay);
  };

  open();

  return {
    close() {
      stopped = true;
      clearTimeout(retryTimer);
      clearInterval(pingTimer);
      socket?.close(1000);
      socket = null;
    },
  };
}

export const realApi: KkiriApi = {
  kakaoLoginUrl: (redirect: string) => `${API_BASE}/v1/auth/kakao/start?redirect=${encodeURIComponent(redirect)}`,

  getMe: (userToken: string) => request<MeResponse>('GET', '/v1/me', { token: userToken }),

  getMyCars: (userToken: string) => request<MyCarsResponse>('GET', '/v1/me/cars', { token: userToken }),

  reserveSession: (userToken: string, req: ReserveRequest) =>
    request<SessionResponse>('POST', '/v1/sessions/reserve', { body: req, token: userToken }),

  getFriendCars: (userToken: string) =>
    request<FriendCarsResponse>('GET', '/v1/me/friend-cars', { token: userToken }),

  joinSession: (req: JoinRequest, userToken: string | null) =>
    request<JoinResponse>('POST', '/v1/sessions/join', { body: req, token: userToken }),

  getSession: (sessionId: string, token: string) =>
    request<SessionResponse>('GET', `/v1/sessions/${encodeURIComponent(sessionId)}`, { token }),

  sendMessage: (sessionId: string, token: string, req: SendMessageRequest) =>
    request<MessageResponse>('POST', `/v1/sessions/${encodeURIComponent(sessionId)}/messages`, { body: req, token }),

  searchPlaces: (sessionId: string, token: string, query: string) =>
    request<PlacesResponse>(
      'GET',
      `/v1/sessions/${encodeURIComponent(sessionId)}/places?query=${encodeURIComponent(query)}`,
      { token },
    ),

  addWaypoint: (sessionId: string, token: string, req: AddWaypointRequest) =>
    request<AddWaypointResponse>('POST', `/v1/sessions/${encodeURIComponent(sessionId)}/waypoints`, {
      body: req,
      token,
    }),

  removeWaypoint: async (sessionId: string, token: string, waypointId: string) => {
    // 응답 본문은 명세에 없다(쓰지 않는다).
    await request<unknown>(
      'DELETE',
      `/v1/sessions/${encodeURIComponent(sessionId)}/waypoints/${encodeURIComponent(waypointId)}`,
      { token },
    );
  },

  // R-40: 주소 이동이라 헤더를 못 실으므로 쿼리 token=<guestToken>으로 보낸다(명세 v1.5 3.5, Q-015).
  spotifyConnectUrl: (sessionId: string, token: string, redirect: string) =>
    `${API_BASE}/v1/sessions/${encodeURIComponent(sessionId)}/music/spotify/start` +
    `?redirect=${encodeURIComponent(redirect)}&token=${encodeURIComponent(token)}`,

  getMusic: (sessionId: string, token: string) =>
    request<MusicResponse>('GET', `/v1/sessions/${encodeURIComponent(sessionId)}/music`, { token }),

  controlMusic: async (sessionId: string, token: string, action: MusicAction) => {
    await request<unknown>('POST', `/v1/sessions/${encodeURIComponent(sessionId)}/music/control`, {
      body: { action },
      token,
    });
  },

  searchTracks: (sessionId: string, token: string, q: string) =>
    request<TracksResponse>(
      'GET',
      `/v1/sessions/${encodeURIComponent(sessionId)}/music/search?q=${encodeURIComponent(q)}`,
      { token },
    ),

  queueTrack: async (sessionId: string, token: string, req: QueueRequest) => {
    await request<unknown>('POST', `/v1/sessions/${encodeURIComponent(sessionId)}/music/queue`, {
      body: req,
      token,
    });
  },

  connectSession,
};
