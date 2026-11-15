// SpotifyMusicConnector·SpotifyMusicProvider를 가짜 Spotify 서버(test/fake-spotify.ts, 공식 문서 모양)로 확인한다. 실제 Spotify는 부르지 않는다.
// 실행: run.mjs가 cwd=backend, 공통 환경변수로 `node --import tsx test/spotify-provider.test.ts`
import { SpotifyMusicConnector, SpotifyMusicProvider } from '../src/adapters/spotify.ts';
import { finishMusicCallback, refreshNowPlayingSafe, setMusicConnectorForTest, startMusic, withMusic } from '../src/store/music.ts';
import { createSession, removeSession } from '../src/store/sessions.ts';
import { startFakeSpotify } from './fake-spotify.ts';

let fails = 0;
const expect = (n: string, c: boolean, info = '') => { if (!c) { fails++; console.log(`✗ ${n} ${info}`); } else console.log(`✓ ${n}`); };
const codeOf = async (p: Promise<unknown>) => { try { await p; return 'OK'; } catch (e: any) { return e.code ?? String(e); } };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const f = await startFakeSpotify(9097);
const API_BASE = 'http://127.0.0.1:8080';
const CB = `${API_BASE}/v1/music/spotify/callback`;
const WEB = new URL('http://localhost:5173/s/ses_x/music');
const mk = (ttl?: number) => new SpotifyMusicConnector({
  clientId: 'CLIENT_ID_TEST', clientSecret: 'CLIENT_SECRET_TEST', apiBase: API_BASE, pollIntervalMs: 40,
  accountsHost: f.base, apiHost: `${f.base}/v1`, stateTtlMs: ttl,
});
const stateOf = (loc: string) => new URL(loc).searchParams.get('state')!;

// ── R-40 인가 주소 ──
const c = mk();
const s1 = await c.start('ses_a', WEB);
const a = new URL(s1.location);
expect('R-40: {accounts}/authorize, 아직 연결 없음', a.origin + a.pathname === `${f.base}/authorize` && s1.provider === null);
expect('인가 주소: client_id·response_type=code·redirect_uri·scope 2개·state',
  a.searchParams.get('client_id') === 'CLIENT_ID_TEST' && a.searchParams.get('response_type') === 'code' && a.searchParams.get('redirect_uri') === CB &&
  a.searchParams.get('scope') === 'user-read-currently-playing user-modify-playback-state' && (a.searchParams.get('state') ?? '').length >= 16);

// ── R-41 콜백 ──
let r = await c.callback(new URLSearchParams({ code: 'CODE_OK', state: stateOf(s1.location) }));
const tok = f.seen.filter((x) => x.path === '/api/token').at(-1)!;
const form = new URLSearchParams(tok.body);
expect('토큰 요청: Basic(id:secret)·form·grant_type·code·redirect_uri(인가와 같음)',
  tok.headers.authorization === 'Basic ' + Buffer.from('CLIENT_ID_TEST:CLIENT_SECRET_TEST').toString('base64') &&
  String(tok.headers['content-type']).startsWith('application/x-www-form-urlencoded') &&
  form.get('grant_type') === 'authorization_code' && form.get('code') === 'CODE_OK' && form.get('redirect_uri') === CB);
expect('R-41 성공 → 세션·redirect·provider', r.sessionId === 'ses_a' && r.redirect === WEB.toString() && r.provider instanceof SpotifyMusicProvider);
const p = r.provider as SpotifyMusicProvider;
expect('같은 state 재사용 → VALIDATION_ERROR', (await codeOf(c.callback(new URLSearchParams({ code: 'CODE_OK', state: stateOf(s1.location) })))) === 'VALIDATION_ERROR');
expect('모르는 state → VALIDATION_ERROR', (await codeOf(c.callback(new URLSearchParams({ code: 'x', state: 'nope' })))) === 'VALIDATION_ERROR');
const cShort = mk(30); const sx = await cShort.start('ses_a', WEB); await sleep(60);
expect('만료된 state → VALIDATION_ERROR', (await codeOf(cShort.callback(new URLSearchParams({ code: 'x', state: stateOf(sx.location) })))) === 'VALIDATION_ERROR');
r = await c.callback(new URLSearchParams({ error: 'access_denied', state: stateOf((await c.start('ses_a', WEB)).location) }));
expect('사용자 취소(access_denied) → provider 없이 redirect', r.provider === null && r.redirect === WEB.toString());
r = await c.callback(new URLSearchParams({ code: 'BAD', state: stateOf((await c.start('ses_a', WEB)).location) }));
expect('코드 교환 실패(invalid_grant) → provider 없이 redirect', r.provider === null && r.redirect === WEB.toString());

// ── 조회·검색·제어 ──
let np = await p.nowPlaying();
expect('지금 재생 → title·artist·albumImageUrl·isPlaying', np?.title === '밤편지' && np.artist === '아이유' && np.albumImageUrl === 'https://i.example/1.jpg' && np.isPlaying === true, JSON.stringify(np));
const found = await p.search('newjeans');
const sq = f.seen.filter((x) => x.path === '/v1/search').at(-1)!;
expect('검색: type=track·limit=10, Track 모양', sq.query.get('type') === 'track' && sq.query.get('limit') === '10' && found.length === 2 && found[0].uri === 'spotify:track:fake02' && found[0].artist === 'NewJeans' && found[0].albumImageUrl === null);
await p.queue({ uri: 'spotify:track:fake02', title: 'Hype Boy', artist: 'NewJeans' });
const qq = f.seen.filter((x) => x.path === '/v1/me/player/queue').at(-1)!;
expect('대기열: POST ?uri=, 본문 없음(Content-Length 0)', qq.method === 'POST' && qq.query.get('uri') === 'spotify:track:fake02' && qq.headers['content-length'] === '0');
await p.control('NEXT');
np = await p.nowPlaying();
expect('NEXT → 대기열 곡 재생', np?.title === 'Hype Boy' && np.artist === 'NewJeans');
await p.control('PAUSE'); np = await p.nowPlaying();
const pz = f.seen.filter((x) => x.path === '/v1/me/player/pause').at(-1)!;
expect('PAUSE → PUT /me/player/pause, isPlaying=false', pz.method === 'PUT' && np?.isPlaying === false);
await p.control('PLAY'); await p.control('PREVIOUS'); np = await p.nowPlaying();
expect('PLAY·PREVIOUS', np?.isPlaying === true && np.title === '밤편지');

// ── 오류 매핑(임시) ──
f.state.failNext = 404; expect('404 → MUSIC_NO_ACTIVE_DEVICE', (await codeOf(p.control('NEXT'))) === 'MUSIC_NO_ACTIVE_DEVICE');
f.state.failNext = 403; expect('403 → MUSIC_PREMIUM_REQUIRED', (await codeOf(p.control('NEXT'))) === 'MUSIC_PREMIUM_REQUIRED');
f.state.failNext = 429; expect('429 → INTERNAL', (await codeOf(p.search('a'))) === 'INTERNAL');

// ── 재생 없음 ──
const keep = f.state.current; f.state.current = null;
expect('재생 없음(200, item=null) → null', (await p.nowPlaying()) === null);
f.state.emptyAs204 = true;
expect('재생 없음(204) → null', (await p.nowPlaying()) === null);
f.state.current = keep; f.state.emptyAs204 = false;

// ── 토큰 만료 → 갱신 ──
const oldRefresh = f.state.refreshToken;
f.state.accessToken = 'AT_rotated_by_server'; // 서버 쪽에서 토큰이 만료된 상황
np = await p.nowPlaying();
const rf = f.seen.filter((x) => x.path === '/api/token').at(-1)!;
expect('401 → refresh_token으로 갱신 후 다시 보내 성공', np?.title === '밤편지' && new URLSearchParams(rf.body).get('grant_type') === 'refresh_token' && new URLSearchParams(rf.body).get('refresh_token') === oldRefresh);
f.state.accessToken = 'AT_rotated_again';
np = await p.nowPlaying();
expect('갱신 응답에 refresh_token이 없어도 기존 것으로 다시 갱신', np?.title === '밤편지' && new URLSearchParams(f.seen.filter((x) => x.path === '/api/token').at(-1)!.body).get('refresh_token') === oldRefresh);
f.state.refreshToken = 'RT_revoked'; f.state.accessToken = 'AT_dead';
expect('갱신도 실패(invalid_grant) → MUSIC_NOT_CONNECTED', (await codeOf(p.nowPlaying())) === 'MUSIC_NOT_CONNECTED');
expect('그 뒤로도 MUSIC_NOT_CONNECTED(다시 연결 필요)', (await codeOf(p.control('NEXT'))) === 'MUSIC_NOT_CONNECTED');

// ── dispose ──
f.state.refreshToken = oldRefresh; f.state.codes.add('CODE_2');
const p2 = (await c.callback(new URLSearchParams({ code: 'CODE_2', state: stateOf((await c.start('ses_a', WEB)).location) }))).provider!;
p2.dispose();
expect('dispose 뒤 → MUSIC_NOT_CONNECTED(토큰 버림)', (await codeOf(p2.nowPlaying())) === 'MUSIC_NOT_CONNECTED');

// ── 서버 흐름: R-40 → R-41 → 주기 조회 → 곡 바뀜 이벤트 ──
f.state.codes.add('CODE_3');
const conn = mk();
setMusicConnectorForTest(conn);
const rec = createSession({ name: '음악', destination: null, hostCarId: 'car_t', state: 'ACTIVE', isAccountSession: false, ownerUserId: null, hostStatus: 'CONNECTED' });
const loc = await startMusic(rec, WEB);
const back = await finishMusicCallback(new URLSearchParams({ code: 'CODE_3', state: stateOf(loc) }));
expect('흐름: 연결됨·지금 곡·주기 조회 시작', back === WEB.toString() && rec.music.connected && rec.music.nowPlaying?.title === '밤편지' && rec.musicPoll !== null);
const seq0 = rec.seq;
f.state.current = f.state.catalog[2];
await sleep(150);
expect('흐름: 폰에서 곡이 바뀌면 주기 조회로 now_playing·trackCount', rec.music.nowPlaying?.title === 'Ditto' && rec.seq === seq0 + 1 && rec.trackCount === 2, `seq ${seq0}→${rec.seq} tc ${rec.trackCount}`);
removeSession(rec);
expect('흐름: 세션 종료 → 조회 멈춤·연결 버림', rec.musicPoll === null && rec.musicProvider === null);

// ── 실제 계정에서 생길 상황(조회 실패·연결 끊김) ──
const newRec = () => createSession({ name: '음악2', destination: null, hostCarId: 'car_t', state: 'ACTIVE', isAccountSession: false, ownerUserId: null, hostStatus: 'CONNECTED' });
{
  // 1. 로그인 직후 재생 기기가 없어 첫 조회가 실패 → 그래도 연결·웹으로 돌아감
  f.state.codes.add('CODE_4');
  const rec2 = newRec();
  const loc2 = await startMusic(rec2, WEB);
  const s0 = rec2.seq;
  f.state.failNext = 404;
  const back2 = await finishMusicCallback(new URLSearchParams({ code: 'CODE_4', state: stateOf(loc2) }));
  expect('[1] 첫 조회 실패(기기 없음)해도 R-41 → 웹 redirect, 연결됨, now_playing(connected) 알림', back2 === WEB.toString() && rec2.music.connected && rec2.musicProvider !== null && rec2.seq === s0 + 1 && rec2.musicPoll !== null);
  await sleep(120);
  expect('[1] 다음 주기 조회에서 곡이 채워짐', rec2.music.nowPlaying?.title === 'Ditto', JSON.stringify(rec2.music.nowPlaying));

  // 2. R-42 응답 전 조회 실패 → 던지지 않고 마지막 상태 유지
  f.state.failNext = 404;
  const err2 = await codeOf(refreshNowPlayingSafe(rec2));
  expect('[2] 조회 실패해도 오류 없이 마지막 상태 유지(R-42)', err2 === 'OK' && rec2.music.connected && rec2.music.nowPlaying?.title === 'Ditto');

  // 3. 제어 성공 직후 곡 다시 읽기 실패 → 제어는 성공으로
  const r3 = await codeOf((async () => { await withMusic(rec2, (pp) => pp.control('PAUSE')); f.state.failNext = 404; await refreshNowPlayingSafe(rec2); })());
  expect('[3] 제어 성공 + 곡 읽기 실패 → 성공(R-43 204)', r3 === 'OK' && f.state.playing === false);
  f.state.playing = true;

  // 제어 자체가 기기 없음이면 그 오류는 그대로(연결은 유지)
  f.state.failNext = 404;
  expect('[3] 제어 실패(기기 없음) → MUSIC_NO_ACTIVE_DEVICE, 연결 유지', (await codeOf(withMusic(rec2, (pp) => pp.control('NEXT')))) === 'MUSIC_NO_ACTIVE_DEVICE' && rec2.music.connected);

  // 4. 토큰 갱신 실패 → connected=false 알림, 다시 연결 가능
  const s4 = rec2.seq;
  f.state.refreshToken = 'RT_revoked_2'; f.state.accessToken = 'AT_dead_2';
  await sleep(120);
  expect('[4] 주기 조회 중 갱신 실패 → connected=false·연결 버림·조회 멈춤·now_playing 알림',
    !rec2.music.connected && rec2.musicProvider === null && rec2.musicPoll === null && rec2.music.nowPlaying === null && rec2.seq === s4 + 1, `seq ${s4}→${rec2.seq}`);
  expect('[4] 그 뒤 R-43~45 → MUSIC_NOT_CONNECTED', (await codeOf(withMusic(rec2, (pp) => pp.control('NEXT')))) === 'MUSIC_NOT_CONNECTED');
  const again = await startMusic(rec2, WEB);
  expect('[4] 다시 R-40 → Spotify 로그인 주소(재연결 가능)', again.startsWith(`${f.base}/authorize?`));
  removeSession(rec2);

  // 4'. 제어 중에 끊김이 드러나도 같은 정리
  f.state.refreshToken = 'RT_ok_5'; f.state.codes.add('CODE_5');
  const rec5 = newRec();
  await finishMusicCallback(new URLSearchParams({ code: 'CODE_5', state: stateOf(await startMusic(rec5, WEB)) }));
  f.state.refreshToken = 'RT_revoked_5'; f.state.accessToken = 'AT_dead_5';
  const e5 = await codeOf(withMusic(rec5, (pp) => pp.search('a')));
  expect("[4'] 검색 중 갱신 실패 → MUSIC_NOT_CONNECTED + connected=false", e5 === 'MUSIC_NOT_CONNECTED' && !rec5.music.connected && rec5.musicPoll === null);
  removeSession(rec5);
}

await f.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
