// 가짜 Spotify 서버 (확인 스크립트 전용). 실제 Spotify는 부르지 않는다.
// 요청·응답 모양은 Spotify for Developers 공식 문서(2026-11-12 원문 확인)에 있는 것만 쓴다.
//  - 토큰: POST /api/token (accounts.spotify.com), Content-Type application/x-www-form-urlencoded,
//          Authorization: Basic base64(client_id:client_secret). grant_type=authorization_code(code, redirect_uri) | refresh_token(refresh_token)
//          응답 access_token·token_type·expires_in(3600)·refresh_token·scope. 갱신 응답에는 refresh_token이 "없을 수도" 있다(그러면 기존 것 계속 사용)
//          갱신 실패 invalid_grant → 인가부터 다시. 인증 오류 형식 { error, error_description }
//  - 지금 재생: GET /v1/me/player/currently-playing → 200 { is_playing, item(null 가능){ name, uri, artists[].name, album.images[].url }, currently_playing_type }
//          문서에 "재생 없음" 응답 코드는 적혀 있지 않다 → 가짜는 200 + item:null 과 204 둘 다 낼 수 있게 한다(실제는 B단계에서 실계정으로 확인)
//  - 제어: POST /v1/me/player/next|previous, PUT /v1/me/player/pause|play, POST /v1/me/player/queue?uri=(필수) → 성공 204. Premium 전용
//  - 검색: GET /v1/search?q=&type=track&limit=(0~10, 기본 5) → { tracks: { items: [TrackObject] } }
//  - 오류 형식 { error: { status, message } }. 문서의 상태 코드 401·403·404·429. "기기 없음"·"Premium 아님"의 정확한 사유 값은 문서에 없다
//    → 가짜는 상태 코드(404 / 403)만 흉내 내고, BE의 오류 매핑(MUSIC_NO_ACTIVE_DEVICE·MUSIC_PREMIUM_REQUIRED)은 B단계 실계정 확인 후 정한다
import { createServer, type Server } from 'node:http';

export interface FakeTrack { name: string; artist: string; uri: string; image: string | null }

export interface FakeSpotify {
  base: string;
  /** 서버가 받은 요청(경로·메서드·헤더·본문) */
  seen: { method: string; path: string; query: URLSearchParams; headers: Record<string, string | string[] | undefined>; body: string }[];
  state: {
    clientId: string;
    clientSecret: string;
    /** 유효한 인가 코드(한 번 쓰면 사라짐) */
    codes: Set<string>;
    accessToken: string;
    refreshToken: string;
    /** 갱신 응답에 refresh_token을 넣을지 */
    rotateRefresh: boolean;
    /** 다음 API 호출을 이 상태 코드로 실패시킨다(한 번). 예: 401(만료), 404(기기 없음), 403(Premium 아님), 429 */
    failNext: number | null;
    /** 재생 없음일 때 204로 답할지(false면 200 + item:null) */
    emptyAs204: boolean;
    playing: boolean;
    current: FakeTrack | null;
    queue: FakeTrack[];
    catalog: FakeTrack[];
  };
  close(): Promise<void>;
}

const json = (res: import('node:http').ServerResponse, status: number, body: unknown) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};
const apiError = (res: import('node:http').ServerResponse, status: number, message: string) => json(res, status, { error: { status, message } });
const trackObject = (t: FakeTrack) => ({ name: t.name, uri: t.uri, artists: [{ name: t.artist }], album: { images: t.image ? [{ url: t.image, height: 640, width: 640 }] : [] } });

export async function startFakeSpotify(port = 9098): Promise<FakeSpotify> {
  const catalog: FakeTrack[] = [
    { name: '밤편지', artist: '아이유', uri: 'spotify:track:fake01', image: 'https://i.example/1.jpg' },
    { name: 'Hype Boy', artist: 'NewJeans', uri: 'spotify:track:fake02', image: null },
    { name: 'Ditto', artist: 'NewJeans', uri: 'spotify:track:fake03', image: 'https://i.example/3.jpg' },
  ];
  const fake: FakeSpotify = {
    base: `http://127.0.0.1:${port}`,
    seen: [],
    state: {
      clientId: 'CLIENT_ID_TEST', clientSecret: 'CLIENT_SECRET_TEST', codes: new Set(['CODE_OK']),
      accessToken: 'AT_1', refreshToken: 'RT_1', rotateRefresh: false, failNext: null, emptyAs204: false,
      playing: true, current: catalog[0], queue: [], catalog,
    },
    close: () => new Promise((r) => server.close(() => r())),
  };
  const s = fake.state;
  let tokenSeq = 1;

  const server: Server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    const body = Buffer.concat(chunks).toString();
    const url = new URL(req.url ?? '/', 'http://x');
    fake.seen.push({ method: req.method ?? '', path: url.pathname, query: url.searchParams, headers: req.headers, body });

    // ── 토큰 ──
    if (req.method === 'POST' && url.pathname === '/api/token') {
      const basic = 'Basic ' + Buffer.from(`${s.clientId}:${s.clientSecret}`).toString('base64');
      if (req.headers.authorization !== basic) return json(res, 400, { error: 'invalid_client', error_description: 'Invalid client' });
      if (!String(req.headers['content-type'] ?? '').startsWith('application/x-www-form-urlencoded')) return json(res, 400, { error: 'invalid_request', error_description: 'content type' });
      const f = new URLSearchParams(body);
      const issue = (withRefresh: boolean) => {
        s.accessToken = `AT_${++tokenSeq}`;
        if (withRefresh) s.refreshToken = `RT_${tokenSeq}`;
        const out: Record<string, unknown> = { access_token: s.accessToken, token_type: 'Bearer', expires_in: 3600, scope: 'user-read-currently-playing user-modify-playback-state' };
        if (withRefresh) out.refresh_token = s.refreshToken;
        return json(res, 200, out);
      };
      if (f.get('grant_type') === 'authorization_code') {
        const code = f.get('code') ?? '';
        if (!s.codes.delete(code) || !f.get('redirect_uri')) return json(res, 400, { error: 'invalid_grant', error_description: 'Invalid authorization code' });
        return issue(true);
      }
      if (f.get('grant_type') === 'refresh_token') {
        if (f.get('refresh_token') !== s.refreshToken) return json(res, 400, { error: 'invalid_grant', error_description: 'Invalid refresh token' });
        return issue(s.rotateRefresh);
      }
      return json(res, 400, { error: 'unsupported_grant_type', error_description: 'grant_type' });
    }

    // ── Web API (/v1/...) ──
    if (!url.pathname.startsWith('/v1/')) { res.writeHead(404); res.end(); return; }
    if (req.headers.authorization !== `Bearer ${s.accessToken}`) return apiError(res, 401, 'The access token expired');
    if (s.failNext !== null) {
      const st = s.failNext; s.failNext = null;
      return apiError(res, st, st === 404 ? 'Player command failed: No active device found' : st === 403 ? 'Player command failed: Premium required' : 'error');
    }
    const p = url.pathname;
    if (req.method === 'GET' && p === '/v1/me/player/currently-playing') {
      if (!s.current && s.emptyAs204) { res.writeHead(204); res.end(); return; }
      return json(res, 200, { is_playing: s.current ? s.playing : false, item: s.current ? trackObject(s.current) : null, currently_playing_type: s.current ? 'track' : 'unknown' });
    }
    const done = () => { res.writeHead(204); res.end(); };
    if (req.method === 'POST' && p === '/v1/me/player/next') { s.current = s.queue.shift() ?? s.catalog[(s.catalog.indexOf(s.current!) + 1) % s.catalog.length]; s.playing = true; return done(); }
    if (req.method === 'POST' && p === '/v1/me/player/previous') { s.current = s.catalog[(Math.max(0, s.catalog.indexOf(s.current!)) - 1 + s.catalog.length) % s.catalog.length]; s.playing = true; return done(); }
    if (req.method === 'PUT' && p === '/v1/me/player/pause') { s.playing = false; return done(); }
    if (req.method === 'PUT' && p === '/v1/me/player/play') { s.playing = true; return done(); }
    if (req.method === 'POST' && p === '/v1/me/player/queue') {
      const uri = url.searchParams.get('uri');
      const t = s.catalog.find((x) => x.uri === uri);
      if (!t) return apiError(res, 400, 'Missing or invalid uri');
      s.queue.push(t);
      return done();
    }
    if (req.method === 'GET' && p === '/v1/search') {
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const limit = Number(url.searchParams.get('limit') ?? '5');
      if (!q || url.searchParams.get('type') !== 'track' || !Number.isInteger(limit) || limit < 0 || limit > 10) return apiError(res, 400, 'Invalid request');
      const items = s.catalog.filter((t) => t.name.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q)).slice(0, limit).map(trackObject);
      return json(res, 200, { tracks: { href: '', limit, next: null, offset: 0, previous: null, total: items.length, items } });
    }
    apiError(res, 404, 'Service not found');
  });
  await new Promise<void>((r) => server.listen(port, '127.0.0.1', r));
  return fake;
}
