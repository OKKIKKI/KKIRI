/**
 * 음악 어댑터 (D-09): Spotify 호출은 전부 이 뒤에 둔다.
 * - MusicConnector: 세션을 음악 서비스에 "연결"하는 방법(R-40 시작, R-41 콜백). 카카오 LoginProvider와 같은 모양.
 * - MusicProvider: 연결된 세션 하나의 재생 조회·제어·검색·대기열.
 * D-09(Spotify 앱·계정)·Q-BE-002가 OPEN이라 1차는 목업만 있다(고정 곡 10개, 메모리 대기열, R-40에서 바로 연결).
 * 실제 구현(SpotifyMusicConnector)은 같은 인터페이스로 만들고 createMusicConnector()만 바꾼다(범위 문서 BE 4절).
 */
import { config } from '../config.js';
import { SpotifyMusicConnector } from './spotify.js';

export interface NowPlaying {
  title: string;
  artist: string;
  albumImageUrl: string | null;
  isPlaying: boolean;
}

export interface Track {
  uri: string;
  title: string;
  artist: string;
  albumImageUrl: string | null;
}

export type MusicAction = 'NEXT' | 'PREVIOUS' | 'PAUSE' | 'PLAY';

export interface MusicProvider {
  /** 지금 재생 중인 곡. 없으면 null */
  nowPlaying(): Promise<NowPlaying | null>;
  /** 실패는 ApiError(MUSIC_NO_ACTIVE_DEVICE, MUSIC_PREMIUM_REQUIRED)로 던진다 */
  control(action: MusicAction): Promise<void>;
  /** 최대 10개 */
  search(q: string): Promise<Track[]>;
  queue(track: { uri: string; title: string; artist: string }): Promise<void>;
  /** 세션 종료 시: 토큰 등 연결 정보를 버린다 */
  dispose(): void;
}

// ── 목업 ─────────────────────────────────────────────────

/** 목업용 고정 곡 목록. uri는 가짜 값(spotify:track:mock_xx)이다. */
const MOCK_TRACKS: Track[] = [
  ['밤편지', '아이유'],
  ['Lose Yourself', 'Eminem'],
  ['봄날', '방탄소년단'],
  ['Hype Boy', 'NewJeans'],
  ['사건의 지평선', '윤하'],
  ['Dynamite', '방탄소년단'],
  ['좋은 날', '아이유'],
  ['Ditto', 'NewJeans'],
  ['Shape of You', 'Ed Sheeran'],
  ['한숨', '이하이'],
].map(([title, artist], i) => ({ uri: `spotify:track:mock_${String(i + 1).padStart(2, '0')}`, title, artist, albumImageUrl: null }));

class MockMusicProvider implements MusicProvider {
  private current: Track = MOCK_TRACKS[0];
  private index = 0;
  private playing = true;
  private readonly upNext: Track[] = [];

  async nowPlaying(): Promise<NowPlaying> {
    return { title: this.current.title, artist: this.current.artist, albumImageUrl: this.current.albumImageUrl, isPlaying: this.playing };
  }

  async control(action: MusicAction): Promise<void> {
    if (action === 'PAUSE') { this.playing = false; return; }
    if (action === 'PLAY') { this.playing = true; return; }
    if (action === 'NEXT') {
      const queued = this.upNext.shift();
      if (queued) this.current = queued;
      else { this.index = (this.index + 1) % MOCK_TRACKS.length; this.current = MOCK_TRACKS[this.index]; }
    } else {
      this.index = (this.index - 1 + MOCK_TRACKS.length) % MOCK_TRACKS.length;
      this.current = MOCK_TRACKS[this.index];
    }
    this.playing = true;
  }

  async search(q: string): Promise<Track[]> {
    const s = q.trim().toLowerCase();
    return MOCK_TRACKS.filter((t) => t.title.toLowerCase().includes(s) || t.artist.toLowerCase().includes(s)).slice(0, 10);
  }

  async queue(track: { uri: string; title: string; artist: string }): Promise<void> {
    this.upNext.push({ ...track, albumImageUrl: null });
  }

  dispose(): void {
    this.upNext.length = 0;
  }
}

// ── 연결 ─────────────────────────────────────────────────

/** R-40 결과. provider가 있으면 바로 연결된 것, null이면 외부 로그인(location) 뒤 R-41에서 연결된다 */
export interface MusicStartResult {
  location: string;
  provider: MusicProvider | null;
}

/** R-41 결과. 연결에 실패(사용자 취소·토큰 교환 실패)하면 provider = null이고 redirect로만 돌려보낸다 */
export interface MusicCallbackResult {
  sessionId: string;
  redirect: string;
  provider: MusicProvider | null;
}

export interface MusicConnector {
  /** R-40. sessionId·redirect를 기억해 두고 브라우저를 보낼 주소를 돌려준다 */
  start(sessionId: string, redirect: URL): Promise<MusicStartResult>;
  /** R-41. 외부 서비스가 돌려보낸 쿼리(code·state 또는 error·state)로 연결을 끝낸다. 없으면 R-41은 404 */
  callback?(query: URLSearchParams): Promise<MusicCallbackResult>;
  /**
   * 지금 재생 중인 곡을 서버가 다시 읽는 간격(ms, 명세 3.5 "서버가 주기적으로 조회"). null이면 주기 조회를 하지 않는다.
   * 목업은 곡이 R-43·R-45 직후에만 바뀌므로 그때만 읽는다(null).
   */
  readonly pollIntervalMs: number | null;
}

/** 목업 연결: R-40에서 바로 연결하고 redirect로 돌려보낸다(카카오 개발용 로그인과 같은 방식). R-41 없음 */
class MockMusicConnector implements MusicConnector {
  readonly pollIntervalMs = null;
  async start(_sessionId: string, redirect: URL): Promise<MusicStartResult> {
    return { location: redirect.toString(), provider: new MockMusicProvider() };
  }
}

/**
 * 음악 연결 방법을 고른다. SPOTIFY_CLIENT_ID·SPOTIFY_CLIENT_SECRET이 둘 다 있으면 실제 Spotify, 아니면 목업.
 * ⚠ 실제 Spotify는 Q-BE-002 승인 전 임시(BE 승인 2026-11-12). 키를 비우면 지금까지와 똑같이 목업으로 돈다.
 */
export function createMusicConnector(): MusicConnector {
  if (config.spotifyClientId && config.spotifyClientSecret && config.apiBase) {
    const c = new SpotifyMusicConnector({
      clientId: config.spotifyClientId,
      clientSecret: config.spotifyClientSecret,
      apiBase: config.apiBase,
      pollIntervalMs: config.musicPollSec * 1000,
    });
    const host = new URL(c.redirectUri);
    // Spotify Redirect URI 규칙(공식 문서): HTTPS 또는 loopback IP(127.0.0.1)만. LAN http 주소는 Spotify가 거절한다
    if (host.protocol !== 'https:' && host.hostname !== '127.0.0.1') {
      console.warn(`[spotify] ⚠ 콜백 주소 ${c.redirectUri} 는 Spotify가 받지 않습니다(HTTPS 또는 127.0.0.1만). KKIRI_API_BASE를 확인하세요`);
    }
    console.log(`[spotify] 실제 Spotify 사용. 대시보드 Redirect URI에 ${c.redirectUri} 가 그대로 등록돼 있어야 합니다. 조회 간격 ${config.musicPollSec}초`);
    return c;
  }
  return new MockMusicConnector();
}
