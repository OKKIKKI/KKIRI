// 음악 연결 흐름(R-40 → R-41 → 주기 조회 → 세션 종료) 확인. 실제 Spotify는 부르지 않는다.
// 1) 기본 연결 방법(목업)이 지금 동작(R-40에서 바로 연결, R-41 없음, 주기 조회 없음)을 그대로 지키는지
// 2) "외부 로그인 → 콜백"형 연결 방법(실제 Spotify 자리)을 끼웠을 때 store/music의 흐름이 맞는지(시험용 연결 방법)
// 3) 가짜 Spotify 서버(test/fake-spotify.ts)가 공식 문서 모양대로 답하는지 — B단계(실제 구현) 확인용 바탕
// 실행: run.mjs가 cwd=backend, 공통 환경변수로 `node --import tsx test/music-connector.test.ts`
import type { MusicCallbackResult, MusicConnector, MusicProvider, MusicStartResult, NowPlaying } from '../src/adapters/music.ts';
import { finishMusicCallback, musicConnector, providerOf, refreshNowPlaying, setMusicConnectorForTest, startMusic } from '../src/store/music.ts';
import { createSession, removeSession } from '../src/store/sessions.ts';
import { startFakeSpotify } from './fake-spotify.ts';

let fails = 0;
const expect = (n: string, c: boolean, info = '') => { if (!c) { fails++; console.log(`✗ ${n} ${info}`); } else console.log(`✓ ${n}`); };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const WEB = new URL('http://localhost:5173/s/ses_x/music');
const newRec = () => createSession({ name: '음악', destination: null, hostCarId: 'car_t', state: 'ACTIVE', isAccountSession: false, ownerUserId: null, hostStatus: 'CONNECTED' });

// ── 1. 기본(목업) ──
{
  const c = musicConnector();
  expect('기본 연결 방법: 주기 조회 없음, R-41 콜백 없음', c.pollIntervalMs === null && c.callback === undefined);
  const rec = newRec();
  const loc = await startMusic(rec, WEB);
  expect('목업 R-40: 바로 연결 + redirect 그대로', loc === WEB.toString() && rec.music.connected && rec.musicProvider !== null && rec.musicPoll === null);
  expect('목업 R-40: 지금 곡 채움·trackCount 1', rec.music.nowPlaying?.title === '밤편지' && rec.trackCount === 1);
  const before = rec.musicProvider;
  expect('이미 연결 → R-40은 redirect만', (await startMusic(rec, WEB)) === WEB.toString() && rec.musicProvider === before);
  let nf = '';
  try { await finishMusicCallback(new URLSearchParams({ code: 'x', state: 'y' })); } catch (e: any) { nf = e.code; }
  expect('목업 R-41 → NOT_FOUND(404)', nf === 'NOT_FOUND', nf);
  removeSession(rec);
}

// ── 2. 외부 로그인형 연결 방법(시험용) ──
class StubProvider implements MusicProvider {
  np: NowPlaying | null = { title: 'A', artist: 'X', albumImageUrl: null, isPlaying: true };
  fail = false; disposed = false; calls = 0;
  async nowPlaying() { this.calls++; if (this.fail) throw new Error('조회 실패(시험)'); return this.np; }
  async control() {}
  async search() { return []; }
  async queue() {}
  dispose() { this.disposed = true; }
}
class StubConnector implements MusicConnector {
  readonly pollIntervalMs = 40;
  pending = new Map<string, { sessionId: string; redirect: string }>();
  next: StubProvider | null = null;
  async start(sessionId: string, redirect: URL): Promise<MusicStartResult> {
    const state = `st_${this.pending.size + 1}`;
    this.pending.set(state, { sessionId, redirect: redirect.toString() });
    return { location: `https://accounts.example/authorize?state=${state}`, provider: null };
  }
  async callback(q: URLSearchParams): Promise<MusicCallbackResult> {
    const p = this.pending.get(q.get('state') ?? '');
    if (!p) throw Object.assign(new Error('state'), { code: 'VALIDATION_ERROR' });
    this.pending.delete(q.get('state')!);
    if (q.get('error')) return { ...p, provider: null };
    const prov = new StubProvider(); this.next = prov;
    return { ...p, provider: prov };
  }
}
{
  const stub = new StubConnector();
  setMusicConnectorForTest(stub);
  const rec = newRec();
  const loc = await startMusic(rec, WEB);
  expect('R-40: 외부 로그인 주소로, 아직 연결 안 됨', loc.startsWith('https://accounts.example/authorize?state=') && !rec.music.connected && rec.musicProvider === null);
  let nc = ''; try { providerOf(rec); } catch (e: any) { nc = e.code; }
  expect('콜백 전 R-43~45 → MUSIC_NOT_CONNECTED', nc === 'MUSIC_NOT_CONNECTED', nc);

  // 사용자가 취소
  const st1 = new URL(loc).searchParams.get('state')!;
  const back0 = await finishMusicCallback(new URLSearchParams({ error: 'access_denied', state: st1 }));
  expect('R-41 취소 → redirect로, 연결 없음', back0 === WEB.toString() && !rec.music.connected);

  // 성공
  const st2 = new URL(await startMusic(rec, WEB)).searchParams.get('state')!;
  const seq0 = rec.seq;
  const back = await finishMusicCallback(new URLSearchParams({ code: 'ok', state: st2 }));
  const prov = stub.next!;
  expect('R-41 성공 → redirect로, 연결됨·지금 곡·now_playing 1번', back === WEB.toString() && rec.music.connected && rec.musicProvider === prov && rec.music.nowPlaying?.title === 'A' && rec.seq === seq0 + 1 && rec.trackCount === 1);
  expect('주기 조회 시작', rec.musicPoll !== null);

  // 바뀐 게 없으면 이벤트 없음
  const seq1 = rec.seq; const c1 = prov.calls;
  await sleep(130);
  expect('주기 조회: 계속 읽음, 안 바뀌면 이벤트 없음', prov.calls > c1 && rec.seq === seq1, `calls ${c1}→${prov.calls} seq ${seq1}→${rec.seq}`);

  // 다른 곡 → 이벤트 + trackCount
  prov.np = { title: 'B', artist: 'Y', albumImageUrl: null, isPlaying: true };
  await sleep(100);
  expect('곡이 바뀌면 now_playing 1번·trackCount +1', rec.music.nowPlaying?.title === 'B' && rec.seq === seq1 + 1 && rec.trackCount === 2, `seq ${rec.seq} tc ${rec.trackCount}`);

  // 일시정지만 바뀜 → 이벤트, trackCount 그대로
  prov.np = { ...prov.np!, isPlaying: false };
  await sleep(100);
  expect('재생 여부만 바뀌면 이벤트, trackCount 그대로', rec.music.nowPlaying?.isPlaying === false && rec.seq === seq1 + 2 && rec.trackCount === 2);

  // 조회 실패 → 서버는 계속, 다음 주기에 회복
  prov.fail = true; await sleep(100); prov.fail = false;
  prov.np = { title: 'C', artist: 'Z', albumImageUrl: null, isPlaying: true };
  await sleep(100);
  expect('조회 실패해도 멈추지 않고 다음 주기에 반영', rec.music.nowPlaying?.title === 'C' && rec.trackCount === 3);

  // 이미 연결된 세션에 콜백이 또 오면 새 연결은 버림
  const st3 = new URL((await stub.start(rec.sessionId, WEB)).location).searchParams.get('state')!;
  await finishMusicCallback(new URLSearchParams({ code: 'ok', state: st3 }));
  expect('이미 연결된 세션의 두 번째 콜백 → 새 연결 dispose, 기존 유지', stub.next!.disposed && rec.musicProvider === prov);

  // 세션 종료 → 주기 조회 멈춤·연결 dispose
  removeSession(rec);
  const c2 = prov.calls;
  await sleep(100);
  expect('세션 종료 → 주기 조회 취소·dispose', rec.musicPoll === null && rec.musicProvider === null && prov.disposed && prov.calls === c2);
  await refreshNowPlaying(rec); // 끝난 세션에서 불러도 아무 일 없음

  // 콜백 전에 세션이 끝남 → 새 연결 dispose
  const rec3 = newRec();
  const st4 = new URL(await startMusic(rec3, WEB)).searchParams.get('state')!;
  removeSession(rec3);
  const back4 = await finishMusicCallback(new URLSearchParams({ code: 'ok', state: st4 }));
  expect('콜백 전에 세션 종료 → redirect로, 새 연결 dispose', back4 === WEB.toString() && stub.next!.disposed && rec3.musicProvider === null);
}

// ── 3. 가짜 Spotify 서버 모양 ──
{
  const f = await startFakeSpotify();
  const basic = 'Basic ' + Buffer.from('CLIENT_ID_TEST:CLIENT_SECRET_TEST').toString('base64');
  const form = (o: Record<string, string>) => ({ method: 'POST', headers: { Authorization: basic, 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(o).toString() });
  let r = await fetch(`${f.base}/api/token`, form({ grant_type: 'authorization_code', code: 'CODE_OK', redirect_uri: 'https://api.example/v1/music/spotify/callback' }));
  let j: any = await r.json();
  expect('가짜: 코드 → access_token·refresh_token·expires_in', r.status === 200 && j.access_token && j.refresh_token === f.state.refreshToken && j.expires_in === 3600);
  r = await fetch(`${f.base}/api/token`, form({ grant_type: 'authorization_code', code: 'CODE_OK', redirect_uri: 'x' }));
  expect('가짜: 같은 코드 재사용 → 400 invalid_grant', r.status === 400 && (await r.json()).error === 'invalid_grant');
  r = await fetch(`${f.base}/api/token`, form({ grant_type: 'refresh_token', refresh_token: f.state.refreshToken }));
  j = await r.json();
  expect('가짜: 갱신 응답에 refresh_token 없을 수 있음(문서)', r.status === 200 && j.access_token === f.state.accessToken && !('refresh_token' in j));
  const H = { Authorization: `Bearer ${f.state.accessToken}` };
  r = await fetch(`${f.base}/v1/me/player/currently-playing`, { headers: H }); j = await r.json();
  expect('가짜: 지금 재생 모양(is_playing·item.name·artists[].name·album.images[].url)', j.is_playing === true && j.item.name === '밤편지' && j.item.artists[0].name === '아이유' && j.item.album.images[0].url);
  r = await fetch(`${f.base}/v1/me/player/queue?uri=spotify:track:fake02`, { method: 'POST', headers: H });
  const r2 = await fetch(`${f.base}/v1/me/player/next`, { method: 'POST', headers: H });
  r = await fetch(`${f.base}/v1/me/player/currently-playing`, { headers: H }); j = await r.json();
  expect('가짜: 대기열 204 → 다음 곡 204 → 넣은 곡 재생', r2.status === 204 && j.item.name === 'Hype Boy');
  r = await fetch(`${f.base}/v1/search?q=newjeans&type=track&limit=10`, { headers: H }); j = await r.json();
  expect('가짜: 검색 tracks.items[]', r.status === 200 && j.tracks.items.length === 2 && j.tracks.items[0].uri.startsWith('spotify:track:'));
  r = await fetch(`${f.base}/v1/search?q=a&type=track&limit=11`, { headers: H });
  expect('가짜: 검색 limit 11 → 400', r.status === 400);
  f.state.failNext = 404;
  r = await fetch(`${f.base}/v1/me/player/next`, { method: 'POST', headers: H }); j = await r.json();
  expect('가짜: 오류 형식 { error: { status, message } }', r.status === 404 && j.error.status === 404 && typeof j.error.message === 'string');
  r = await fetch(`${f.base}/v1/me/player/currently-playing`, { headers: { Authorization: 'Bearer old' } });
  expect('가짜: 만료 토큰 → 401', r.status === 401);
  await f.close();
}

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
