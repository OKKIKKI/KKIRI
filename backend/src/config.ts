import 'dotenv/config';

function num(name: string): number {
  const raw = process.env[name];
  if (raw === undefined || raw === '' || Number.isNaN(Number(raw))) {
    throw new Error(`환경변수 ${name} 가 없거나 숫자가 아닙니다 (.env.example 참고)`);
  }
  return Number(raw);
}

/** 명세 v1.5에서 1.2 표에 추가된 값: .env에 없으면 명세 기본값을 쓴다(먼저 .env를 만든 팀원 실행이 깨지지 않게). */
function numOr(name: string, specDefault: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return specDefault;
  if (Number.isNaN(Number(raw))) throw new Error(`환경변수 ${name} 가 숫자가 아닙니다`);
  return Number(raw);
}

/** 선택 값: 없거나 빈 칸이면 null */
function optStr(name: string): string | null {
  const raw = process.env[name]?.trim();
  return raw ? raw : null;
}

function str(name: string): string {
  const raw = process.env[name];
  if (!raw) throw new Error(`환경변수 ${name} 가 없습니다 (.env.example 참고)`);
  return raw;
}

/** 명세 1.2절 환경변수. 코드에 값을 적지 않는다(D-17). */
export const config = {
  port: num('PORT'),
  webBase: str('KKIRI_WEB_BASE'),
  pauseTimeoutSec: num('PAUSE_TIMEOUT_SEC'),
  accountSessionTtlSec: num('ACCOUNT_SESSION_TTL_SEC'),
  reserveAttachTimeoutSec: num('RESERVE_ATTACH_TIMEOUT_SEC'),
  qrTtlSec: num('QR_TTL_SEC'),
  urgentLimitSec: num('URGENT_LIMIT_SEC'),
  proposalTimeoutSec: num('PROPOSAL_TIMEOUT_SEC'),
  devLogin: process.env.DEV_LOGIN === '1',
  /** 시연 방침(2026-11-15): 경유지를 세션의 모든 차에 수락 없이 바로 추가. 기본 켬, '0'이면 명세 원래 동작(제안) */
  waypointToAllCars: process.env.WAYPOINT_TO_ALL_CARS !== '0',
  /** 명세 4.1 "차량이 30초 이상 끊기면 DISCONNECTED". 명세 v1.5 1.2 */
  carDisconnectSec: numOr('CAR_DISCONNECT_SEC', 30),
  /** 명세 3.5 R-43 "같은 참가자의 NEXT/PREVIOUS는 3초에 1회". 명세 v1.5 1.2 */
  musicControlLimitSec: numOr('MUSIC_CONTROL_LIMIT_SEC', 3),
  /**
   * 카카오 로그인(명세 v1.8 1.2, D-10). 비밀값은 .env에만. 키가 없으면 카카오 로그인은 꺼지고 DEV_LOGIN만 쓴다.
   * kakaoRestApiKey가 있으면 apiBase(KKIRI_API_BASE)도 있어야 한다(콜백 주소 = {apiBase}/v1/auth/kakao/callback).
   */
  kakaoRestApiKey: optStr('KAKAO_REST_API_KEY'),
  kakaoClientSecret: optStr('KAKAO_CLIENT_SECRET'),
  /** 서버 자기 외부 주소(명세 v1.8: BE도 사용). 끝의 / 는 뗀다 */
  apiBase: optStr('KKIRI_API_BASE')?.replace(/\/+$/, '') ?? null,
  /**
   * Spotify(D-09). ⚠ 임시 이름: Q-BE-002 승인·명세 1.2 반영 전(BE 승인 2026-11-12, 확정되면 이름을 맞춘다).
   * ID·시크릿이 둘 다 있으면 실제 Spotify, 하나라도 없으면 목업 음악. 콜백 주소 = {apiBase}/v1/music/spotify/callback(R-41)
   */
  spotifyClientId: optStr('SPOTIFY_CLIENT_ID'),
  spotifyClientSecret: optStr('SPOTIFY_CLIENT_SECRET'),
  /** 지금 재생 중인 곡을 서버가 다시 읽는 간격(초, 명세 3.5 "서버가 주기적으로 조회"). 임시 이름, 없으면 5 */
  musicPollSec: numOr('MUSIC_POLL_SEC', 5),
} as const;

if (config.spotifyClientId && config.spotifyClientSecret && !config.apiBase) {
  throw new Error('SPOTIFY_CLIENT_ID를 쓰려면 KKIRI_API_BASE(서버 외부 주소)도 .env에 넣어야 합니다(콜백 주소를 만듦)');
}
if (config.musicPollSec <= 0) throw new Error('MUSIC_POLL_SEC는 0보다 커야 합니다');

if (config.kakaoRestApiKey && !config.apiBase) {
  throw new Error('KAKAO_REST_API_KEY를 쓰려면 KKIRI_API_BASE(서버 외부 주소, 예: http://localhost:8080)도 .env에 넣어야 합니다');
}
