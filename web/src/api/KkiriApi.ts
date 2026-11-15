import type {
  AddWaypointRequest,
  AddWaypointResponse,
  Envelope,
  FriendCarsResponse,
  JoinRequest,
  JoinResponse,
  MeResponse,
  MessageResponse,
  MusicAction,
  MusicResponse,
  MyCarsResponse,
  PlacesResponse,
  QueueRequest,
  ReserveRequest,
  TracksResponse,
  SendMessageRequest,
  SessionResponse,
} from './types';

// 웹이 서버와 주고받는 모든 것의 입구. 실제(real.ts)·목업(mock.ts)이 같은 시그니처로 구현한다.
// 화면 코드는 이 인터페이스만 알고, 어느 구현인지 모른다(05_DEV_RULES 4절).

export type ConnectionStatus = 'connecting' | 'open' | 'closed';

export interface ConnectionHandlers {
  /** 봉투(4.2)를 그대로 넘긴다. 모르는 type도 넘기고, 무시는 받는 쪽이 한다. */
  onEnvelope(env: Envelope): void;
  onStatus?(status: ConnectionStatus): void;
  /** 끊겼을 때 다시 연결해도 되는지. false면 재연결을 멈춘다(끝난 세션 등). */
  canReconnect(): Promise<boolean>;
}

export interface SessionConnection {
  close(): void;
}

export interface KkiriApi {
  // ── 3.2 계정: 세션 밖 호출은 userToken(명세 1.3) ──
  /**
   * R-10 `GET /v1/auth/kakao/start?redirect=` 로 이동할 주소. 로그인이 끝나면 서버가 `{redirect}#userToken=...`으로 돌려보낸다.
   * D-10(카카오 앱 등록) OPEN 동안은 BE 개발용 로그인(DEV_LOGIN=1)이 같은 방식으로 돌려보낸다.
   */
  kakaoLoginUrl(redirect: string): string;
  /** R-12 `GET /v1/me` */
  getMe(userToken: string): Promise<MeResponse>;
  /** R-13 `GET /v1/me/cars` (1차 목업: 서버 시드) */
  getMyCars(userToken: string): Promise<MyCarsResponse>;
  /** R-14 `POST /v1/sessions/reserve` → RESERVED 세션. 이후 R-06을 userToken과 함께 불러 리더로 들어간다. */
  reserveSession(userToken: string, req: ReserveRequest): Promise<SessionResponse>;
  /** R-15 `GET /v1/me/friend-cars` (1차 목업: 고정 목록) */
  getFriendCars(userToken: string): Promise<FriendCarsResponse>;

  // ── 세션 ──
  /** R-06 `POST /v1/sessions/join`. userToken이 있으면 Authorization 헤더로 보낸다(명세 1.3). */
  joinSession(req: JoinRequest, userToken: string | null): Promise<JoinResponse>;
  /** R-07 `GET /v1/sessions/{sessionId}` (guestToken) */
  getSession(sessionId: string, token: string): Promise<SessionResponse>;
  /**
   * R-20 `POST /v1/sessions/{sessionId}/messages` (guestToken). 성공해도 목록은 바꾸지 않는다
   * (message.created 이벤트로 들어온다). 오류 `URGENT_RATE_LIMITED`(details.retryAfterSec).
   */
  sendMessage(sessionId: string, token: string, req: SendMessageRequest): Promise<MessageResponse>;
  /** R-30 `GET /v1/sessions/{sessionId}/places?query=` — 빈 검색어면 전체 목록 */
  searchPlaces(sessionId: string, token: string, query: string): Promise<PlacesResponse>;
  /**
   * R-31 `POST /v1/sessions/{sessionId}/waypoints`. 목록은 waypoint.added(+ waypoint.proposed) 이벤트로 바뀐다.
   * RESERVED 세션에서도 부를 수 있다(탑승 전 담기, ACC-04).
   */
  addWaypoint(sessionId: string, token: string, req: AddWaypointRequest): Promise<AddWaypointResponse>;
  /** R-32 `DELETE /v1/sessions/{sessionId}/waypoints/{waypointId}`. 목록은 waypoint.removed 이벤트로 바뀐다. */
  removeWaypoint(sessionId: string, token: string, waypointId: string): Promise<void>;
  // ── 3.5 음악 (D-09 OPEN: Spotify 실제 연동 전, BE도 MusicProvider 목업) ──
  /**
   * R-40 `GET /v1/sessions/{sessionId}/music/spotify/start?redirect=&token=` 로 이동할 주소.
   * 주소 이동이라 Authorization 헤더를 못 실어 guestToken을 쿼리 `token=`으로 싣는다(명세 v1.5, Q-015).
   */
  spotifyConnectUrl(sessionId: string, token: string, redirect: string): string;
  /** R-42 `GET /v1/sessions/{sessionId}/music` */
  getMusic(sessionId: string, token: string): Promise<MusicResponse>;
  /** R-43 `POST /v1/sessions/{sessionId}/music/control` → 204. 오류 CONTROL_RATE_LIMITED, MUSIC_LOCKED 등 */
  controlMusic(sessionId: string, token: string, action: MusicAction): Promise<void>;
  /** R-44 `GET /v1/sessions/{sessionId}/music/search?q=` (최대 10개) */
  searchTracks(sessionId: string, token: string, q: string): Promise<TracksResponse>;
  /** R-45 `POST /v1/sessions/{sessionId}/music/queue` → 204. 이벤트 music.queue_added */
  queueTrack(sessionId: string, token: string, req: QueueRequest): Promise<void>;

  /** 4.1 `GET {KKIRI_WS_BASE}/v1/ws?sessionId&token`. 끊기면 1·2·4…최대 10초 간격으로 다시 연결한다. */
  connectSession(sessionId: string, token: string, handlers: ConnectionHandlers): SessionConnection;
}
