/**
 * 실제 Spotify 연결 (D-09, MUS-01~04·06). ⚠ Q-BE-002 승인 전 임시 구현(BE 승인 2026-11-12). 환경변수 이름도 임시.
 *
 * 근거: Spotify for Developers 공식 문서(2026-11-12 원문 확인. REQUESTS Q-BE-002 근거란, test/fake-spotify.ts 머리 주석)
 *  - 인가: GET https://accounts.spotify.com/authorize ?client_id&response_type=code&redirect_uri&state&scope
 *    콜백 쿼리 code·state 또는 error(access_denied)·state. redirect_uri는 대시보드 등록값과 정확히 같아야 하고 HTTPS 또는 127.0.0.1만
 *  - 토큰: POST https://accounts.spotify.com/api/token, application/x-www-form-urlencoded, Authorization: Basic base64(id:secret)
 *    grant_type=authorization_code(code, redirect_uri) → access_token·expires_in·refresh_token
 *    grant_type=refresh_token(refresh_token) → 새 access_token. refresh_token이 없을 수 있음(그러면 기존 것 유지). invalid_grant면 다시 연결
 *  - API(https://api.spotify.com/v1, Bearer): GET /me/player/currently-playing, POST /me/player/next|previous,
 *    PUT /me/player/pause|play, POST /me/player/queue?uri=, GET /search?q&type=track&limit(0~10). 제어는 204, Premium 전용
 *  - 권한(scope): user-read-currently-playing, user-modify-playback-state (검색은 별도 권한 없음)
 *
 * 오류 매핑(명세 1.4) — ⚠ 임시: Spotify 문서에 "기기 없음"·"Premium 아님"의 사유 값이 없어 상태 코드로만 나눈다. 실계정 결과로 확정
 *  - 404(제어) → MUSIC_NO_ACTIVE_DEVICE, 403 → MUSIC_PREMIUM_REQUIRED
 *  - 401 → 토큰을 한 번 갱신하고 다시 보냄. 갱신도 안 되면 MUSIC_NOT_CONNECTED
 *  - 그 밖(429·5xx·연결 실패) → INTERNAL(로그에 상태 코드만)
 * 토큰·응답 본문은 로그에 남기지 않는다. 세션 종료 시 dispose()로 토큰을 버린다.
 */
import { randomBytes } from 'node:crypto';
import { ApiError, Errors } from '../errors.js';
import type { MusicAction, MusicCallbackResult, MusicConnector, MusicProvider, MusicStartResult, NowPlaying, Track } from './music.js';

export const SPOTIFY_SCOPES = 'user-read-currently-playing user-modify-playback-state';

export interface SpotifyOptions {
  clientId: string;
  clientSecret: string;
  /** 서버 외부 주소(KKIRI_API_BASE). 콜백 = {apiBase}/v1/music/spotify/callback */
  apiBase: string;
  pollIntervalMs: number;
  /** 테스트용으로만 바꾼다. 기본은 공식 문서의 주소 */
  accountsHost?: string;
  apiHost?: string;
  /** state 유효 시간(ms). 사람이 Spotify 화면에서 로그인할 시간 */
  stateTtlMs?: number;
  /** 요청 하나의 제한 시간(ms) */
  timeoutMs?: number;
}

interface Tokens {
  accessToken: string;
  refreshToken: string;
  /** 만료 시각(ms) */
  expiresAt: number;
}

class SpotifyHttp {
  constructor(readonly o: SpotifyOptions) {}
  get accounts() { return this.o.accountsHost ?? 'https://accounts.spotify.com'; }
  get api() { return this.o.apiHost ?? 'https://api.spotify.com/v1'; }
  get redirectUri() { return `${this.o.apiBase}/v1/music/spotify/callback`; }
  get signal() { return AbortSignal.timeout(this.o.timeoutMs ?? 8000); }

  /** 토큰 요청. 실패면 { error }(invalid_grant 등) */
  async token(form: Record<string, string>): Promise<{ ok: true; t: Tokens; refreshGiven: boolean } | { ok: false; error: string }> {
    const basic = Buffer.from(`${this.o.clientId}:${this.o.clientSecret}`).toString('base64');
    let r: Response;
    try {
      r = await fetch(`${this.accounts}/api/token`, {
        method: 'POST',
        headers: { Authorization: `Basic ${basic}`, 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(form).toString(),
        signal: this.signal,
      });
    } catch (e) {
      return { ok: false, error: `연결 실패(${(e as Error).name})` };
    }
    const j = (await r.json().catch(() => ({}))) as { access_token?: unknown; refresh_token?: unknown; expires_in?: unknown; error?: unknown };
    if (!r.ok || typeof j.access_token !== 'string') return { ok: false, error: `${r.status} ${typeof j.error === 'string' ? j.error : ''}`.trim() };
    const expiresIn = typeof j.expires_in === 'number' ? j.expires_in : 3600;
    return {
      ok: true,
      t: { accessToken: j.access_token, refreshToken: typeof j.refresh_token === 'string' ? j.refresh_token : '', expiresAt: Date.now() + expiresIn * 1000 },
      refreshGiven: typeof j.refresh_token === 'string',
    };
  }
}

/** 세션 하나의 Spotify 연결(토큰 보관). */
export class SpotifyMusicProvider implements MusicProvider {
  private tokens: Tokens | null;

  constructor(private readonly http: SpotifyHttp, tokens: Tokens) {
    this.tokens = tokens;
  }

  /** 만료 60초 전이면 미리 갱신. 갱신 응답에 refresh_token이 없으면 기존 것 유지(공식 문서) */
  private async refresh(): Promise<void> {
    if (!this.tokens) throw Errors.musicNotConnected();
    const res = await this.http.token({ grant_type: 'refresh_token', refresh_token: this.tokens.refreshToken });
    if (!res.ok) {
      console.warn(`[spotify] 토큰 갱신 실패: ${res.error}`);
      if (res.error.includes('invalid_grant')) this.tokens = null; // 다시 연결해야 함
      throw Errors.musicNotConnected();
    }
    this.tokens = { ...res.t, refreshToken: res.refreshGiven ? res.t.refreshToken : this.tokens.refreshToken };
  }

  /** API 한 번. 401이면 한 번 갱신 후 다시 보낸다. 응답(2xx)을 돌려주고, 아니면 명세 오류로 던진다 */
  private async call(method: string, path: string, query?: Record<string, string>): Promise<Response> {
    if (!this.tokens) throw Errors.musicNotConnected();
    if (this.tokens.expiresAt - Date.now() < 60_000) await this.refresh();
    const u = new URL(this.http.api + path);
    for (const [k, v] of Object.entries(query ?? {})) u.searchParams.set(k, v);
    const send = () =>
      fetch(u, {
        method,
        headers: { Authorization: `Bearer ${this.tokens!.accessToken}` },
        body: method === 'GET' ? undefined : '', // 본문 없는 POST·PUT도 Content-Length: 0을 보낸다
        signal: this.http.signal,
      });
    let r: Response;
    try {
      r = await send();
      if (r.status === 401) {
        await r.body?.cancel();
        await this.refresh();
        r = await send();
      }
    } catch (e) {
      if (e instanceof ApiError) throw e;
      console.warn(`[spotify] ${method} ${path} 연결 실패: ${(e as Error).name}`);
      throw Errors.internal();
    }
    if (r.ok) return r;
    await r.body?.cancel();
    console.warn(`[spotify] ${method} ${path} → ${r.status}`);
    if (r.status === 401) throw Errors.musicNotConnected();
    if (r.status === 404) throw Errors.musicNoActiveDevice();
    if (r.status === 403) throw Errors.musicPremiumRequired();
    throw Errors.internal();
  }

  async nowPlaying(): Promise<NowPlaying | null> {
    const r = await this.call('GET', '/me/player/currently-playing');
    if (r.status === 204) return null; // 문서 미명시: 재생 없음일 때 204가 올 수 있어 둘 다 받는다
    const j = (await r.json().catch(() => null)) as {
      is_playing?: unknown;
      item?: { name?: unknown; artists?: { name?: unknown }[]; album?: { images?: { url?: unknown }[] } } | null;
    } | null;
    const item = j?.item;
    if (!item || typeof item.name !== 'string') return null;
    const artist = (item.artists ?? []).map((a) => a.name).filter((n): n is string => typeof n === 'string').join(', ');
    const img = item.album?.images?.[0]?.url;
    return { title: item.name, artist, albumImageUrl: typeof img === 'string' ? img : null, isPlaying: j?.is_playing === true };
  }

  async control(action: MusicAction): Promise<void> {
    const route: Record<MusicAction, [string, string]> = {
      NEXT: ['POST', '/me/player/next'],
      PREVIOUS: ['POST', '/me/player/previous'],
      PAUSE: ['PUT', '/me/player/pause'],
      PLAY: ['PUT', '/me/player/play'],
    };
    const [m, p] = route[action];
    const r = await this.call(m, p);
    await r.body?.cancel();
  }

  async search(q: string): Promise<Track[]> {
    const r = await this.call('GET', '/search', { q, type: 'track', limit: '10' });
    const j = (await r.json().catch(() => null)) as {
      tracks?: { items?: { name?: unknown; uri?: unknown; artists?: { name?: unknown }[]; album?: { images?: { url?: unknown }[] } }[] };
    } | null;
    return (j?.tracks?.items ?? [])
      .filter((t) => typeof t.name === 'string' && typeof t.uri === 'string')
      .slice(0, 10)
      .map((t) => {
        const img = t.album?.images?.[0]?.url;
        return {
          uri: t.uri as string,
          title: t.name as string,
          artist: (t.artists ?? []).map((a) => a.name).filter((n): n is string => typeof n === 'string').join(', '),
          albumImageUrl: typeof img === 'string' ? img : null,
        };
      });
  }

  async queue(track: { uri: string; title: string; artist: string }): Promise<void> {
    const r = await this.call('POST', '/me/player/queue', { uri: track.uri });
    await r.body?.cancel();
  }

  dispose(): void {
    this.tokens = null;
  }
}

/** R-40 → Spotify 로그인 → R-41 콜백으로 세션을 연결한다. */
export class SpotifyMusicConnector implements MusicConnector {
  readonly pollIntervalMs: number;
  private readonly http: SpotifyHttp;
  /** state → 연결할 세션·돌아갈 웹 주소. 한 번 쓰면 지운다 */
  private readonly pending = new Map<string, { sessionId: string; redirect: string; expiresAt: number }>();

  constructor(opts: SpotifyOptions) {
    this.http = new SpotifyHttp(opts);
    this.pollIntervalMs = opts.pollIntervalMs;
  }

  get redirectUri(): string {
    return this.http.redirectUri;
  }

  async start(sessionId: string, redirect: URL): Promise<MusicStartResult> {
    const now = Date.now();
    for (const [k, v] of this.pending) if (v.expiresAt <= now) this.pending.delete(k);
    const state = randomBytes(16).toString('base64url');
    this.pending.set(state, { sessionId, redirect: redirect.toString(), expiresAt: now + (this.http.o.stateTtlMs ?? 10 * 60 * 1000) });
    const u = new URL('/authorize', this.http.accounts);
    u.searchParams.set('client_id', this.http.o.clientId);
    u.searchParams.set('response_type', 'code');
    u.searchParams.set('redirect_uri', this.http.redirectUri);
    u.searchParams.set('state', state);
    u.searchParams.set('scope', SPOTIFY_SCOPES);
    return { location: u.toString(), provider: null };
  }

  async callback(query: URLSearchParams): Promise<MusicCallbackResult> {
    const state = query.get('state') ?? '';
    const p = this.pending.get(state);
    if (!p || p.expiresAt <= Date.now()) {
      this.pending.delete(state);
      throw Errors.validation('Spotify 연결 요청이 만료됐거나 올바르지 않아요. 다시 연결해 주세요');
    }
    this.pending.delete(state);
    const error = query.get('error');
    const code = query.get('code');
    if (error || !code) {
      console.warn(`[spotify] 연결 취소·실패: ${error ?? '코드 없음'}`);
      return { sessionId: p.sessionId, redirect: p.redirect, provider: null };
    }
    const res = await this.http.token({ grant_type: 'authorization_code', code, redirect_uri: this.http.redirectUri });
    if (!res.ok) {
      console.warn(`[spotify] 토큰 받기 실패: ${res.error}`);
      return { sessionId: p.sessionId, redirect: p.redirect, provider: null };
    }
    console.log(`[spotify] ${p.sessionId} 연결됨`);
    return { sessionId: p.sessionId, redirect: p.redirect, provider: new SpotifyMusicProvider(this.http, res.t) };
  }
}
