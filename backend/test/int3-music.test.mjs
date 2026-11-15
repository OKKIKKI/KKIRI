// 통합 3회차: 음악 R-40(목업)·R-42~R-46 (서버: MUSIC_CONTROL_LIMIT_SEC=1 PAUSE_TIMEOUT_SEC=5)
import { readFileSync } from 'node:fs';
const repo = process.argv[2];
const API = 'http://localhost:8080';
const WS = 'ws://localhost:8080';
const WEB = 'http://localhost:5173';
const rest = JSON.parse(readFileSync(`${repo}/docs/fixtures/rest_examples.json`, 'utf8'));
const wsFx = JSON.parse(readFileSync(`${repo}/docs/fixtures/ws_events.json`, 'utf8'));
let fails = 0;
function shape(a, b, path = '$', out = []) {
  if (b === null || a === null) return out;
  if (Array.isArray(b)) { if (!Array.isArray(a)) out.push(`${path}: 배열이어야 함`); else if (a.length && b.length) shape(a[0], b[0], path + '[0]', out); return out; }
  if (typeof b === 'object') {
    if (typeof a !== 'object' || Array.isArray(a)) { out.push(`${path}: 객체여야 함`); return out; }
    for (const k of Object.keys(b)) if (!(k in a)) out.push(`${path}.${k}: 빠짐`);
    for (const k of Object.keys(a)) if (!(k in b)) out.push(`${path}.${k}: 명세에 없는 필드`);
    for (const k of Object.keys(b)) if (k in a) shape(a[k], b[k], `${path}.${k}`, out);
    return out;
  }
  if (typeof a !== typeof b) out.push(`${path}: 타입 ${typeof a} (기대 ${typeof b})`);
  return out;
}
const check = (n, a, f) => { const d = shape(a, f); if (d.length) { fails++; console.log(`✗ ${n}\n   ${d.join('\n   ')}`); } else console.log(`✓ ${n}`); };
const expect = (n, c, info = '') => { if (!c) { fails++; console.log(`✗ ${n} ${info}`); } else console.log(`✓ ${n}`); };
async function call(method, path, body, token) {
  const r = await fetch(API + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}
function openWs(sid, token) {
  const ws = new WebSocket(`${WS}/v1/ws?sessionId=${sid}&token=${token}`);
  const events = [];
  ws.onmessage = (e) => events.push(JSON.parse(e.data));
  return new Promise((res, rej) => { ws.onopen = () => res({ ws, events }); ws.onerror = rej; });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const evs = (c, t) => c.events.filter((e) => e.type === t);
const fx = (t) => wsFx.find((e) => e.type === t);
const code = (r) => r.body?.error?.code;
const start = (P, tok, redirect = `${WEB}/s/x/music/connect`) => fetch(`${API}${P}/music/spotify/start?redirect=${encodeURIComponent(redirect)}`, { redirect: 'manual', headers: tok ? { Authorization: `Bearer ${tok}` } : {} });

const A = (await call('POST', '/v1/cars/register', { deviceId: 'm3-A', displayName: 'A차', driverName: '엄마' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'm3-B', displayName: 'B차', driverName: '현우' })).body;
const ses = (await call('POST', '/v1/sessions', { name: '등교', destination: null }, A.carToken)).body.session;
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, B.carToken);
const qr = ses.qr.url.split('/j/')[1];
const G = (await call('POST', '/v1/sessions/join', { qrToken: qr, name: '지호' })).body;
const G2 = (await call('POST', '/v1/sessions/join', { qrToken: qr, name: '민지' })).body;
const sid = ses.sessionId, P = `/v1/sessions/${sid}`;
const wG = await openWs(sid, G.guestToken), wA = await openWs(sid, A.carToken);
await wait(100);

// 연결 전
const m0 = await call('GET', `${P}/music`, undefined, G.guestToken);
check('R-42 응답', m0.body, rest['R-42'].response);
expect('연결 전 {connected:false, locked:false, nowPlaying:null}', JSON.stringify(m0.body.music) === '{"connected":false,"locked":false,"nowPlaying":null}');
expect('연결 전 R-43 → 409 MUSIC_NOT_CONNECTED', code(await call('POST', `${P}/music/control`, { action: 'NEXT' }, G.guestToken)) === 'MUSIC_NOT_CONNECTED');
expect('연결 전 R-44 → MUSIC_NOT_CONNECTED', code(await call('GET', `${P}/music/search?q=a`, undefined, G.guestToken)) === 'MUSIC_NOT_CONNECTED');

// R-40
expect('R-40 차량 토큰 → FORBIDDEN', (await (await start(P, A.carToken)).json()).error.code === 'FORBIDDEN');
expect('R-40 다른 사이트 redirect → VALIDATION_ERROR', (await (await start(P, G.guestToken, 'https://evil.example')).json()).error.code === 'VALIDATION_ERROR');
expect('R-40 토큰 없음 → UNAUTHORIZED', (await (await start(P, null)).json()).error.code === 'UNAUTHORIZED');
const s1 = await start(P, G.guestToken);
expect('R-40 (호스트 차 참가자) → 302 redirect 그대로', s1.status === 302 && s1.headers.get('location') === `${WEB}/s/x/music/connect`, `${s1.status} ${s1.headers.get('location')}`);
await wait(150);
const np0 = evs(wG, 'music.now_playing')[0];
check('music.now_playing 이벤트', np0, fx('music.now_playing'));
expect('연결 → connected=true, 첫 곡 재생 중', np0.payload.music.connected === true && np0.payload.music.nowPlaying?.isPlaying === true);
const m1 = await call('GET', `${P}/music`, undefined, A.carToken);
expect('R-42 차량도 조회 가능, connected=true', m1.body.music.connected === true);
expect('스냅샷 music에도 반영', (await call('GET', P, undefined, G.guestToken)).body.session.music.connected === true);
expect('R-40 다시 → 302(그대로 연결 유지)', (await start(P, G.guestToken)).status === 302);

// 명세 v1.5: R-40은 쿼리 token=도 받는다(브라우저 주소 이동). 헤더가 있으면 헤더 우선
const qs = (tok, redirect = `${WEB}/s/x/music/connect`) => fetch(`${API}${P}/music/spotify/start?redirect=${encodeURIComponent(redirect)}&token=${encodeURIComponent(tok)}`, { redirect: 'manual' });
const qt1 = await qs(G2.guestToken);
expect('R-40 쿼리 token(헤더 없음) → 302', qt1.status === 302 && qt1.headers.get('location') === `${WEB}/s/x/music/connect`, String(qt1.status));
expect('R-40 잘못된 쿼리 token → 401 UNAUTHORIZED', (await (await qs('gt_bad')).json()).error.code === 'UNAUTHORIZED');
expect('R-40 쿼리 token=차량 토큰 → FORBIDDEN', (await (await qs(A.carToken)).json()).error.code === 'FORBIDDEN');
const both = await fetch(`${API}${P}/music/spotify/start?redirect=${encodeURIComponent(`${WEB}/x`)}&token=gt_bad`, { redirect: 'manual', headers: { Authorization: `Bearer ${G.guestToken}` } });
expect('헤더와 쿼리가 둘 다 있으면 헤더 우선 → 302', both.status === 302);
expect('다른 API(R-42)는 쿼리 token을 받지 않음 → UNAUTHORIZED', (await (await fetch(`${API}${P}/music?token=${encodeURIComponent(G.guestToken)}`)).json()).error.code === 'UNAUTHORIZED');

// R-43
const c1 = await call('POST', `${P}/music/control`, { action: 'NEXT' }, G.guestToken);
expect('R-43 NEXT → 204', c1.status === 204 && c1.body === null);
await wait(100);
expect('NEXT → now_playing 곡 바뀜', evs(wG, 'music.now_playing').at(-1).payload.music.nowPlaying.title !== np0.payload.music.nowPlaying.title);
const c2 = await call('POST', `${P}/music/control`, { action: 'NEXT' }, G.guestToken);
expect('같은 참가자 바로 NEXT → 429 CONTROL_RATE_LIMITED', c2.status === 429 && code(c2) === 'CONTROL_RATE_LIMITED');
expect('PAUSE는 연타 제한 없음 → 204', (await call('POST', `${P}/music/control`, { action: 'PAUSE' }, G.guestToken)).status === 204);
await wait(100);
expect('PAUSE → isPlaying=false 이벤트', evs(wG, 'music.now_playing').at(-1).payload.music.nowPlaying.isPlaying === false);
expect('다른 참가자 NEXT는 제한 안 받음', (await call('POST', `${P}/music/control`, { action: 'PREVIOUS' }, G2.guestToken)).status === 204);
await wait(1100);
expect('제한 시간 뒤 NEXT → 204', (await call('POST', `${P}/music/control`, { action: 'NEXT' }, G.guestToken)).status === 204);
expect('action 틀림 → VALIDATION_ERROR', code(await call('POST', `${P}/music/control`, { action: 'STOP' }, G.guestToken)) === 'VALIDATION_ERROR');
expect('R-43 차량 → FORBIDDEN', code(await call('POST', `${P}/music/control`, { action: 'PLAY' }, A.carToken)) === 'FORBIDDEN');

// R-44
const se = await call('GET', `${P}/music/search?q=${encodeURIComponent('아이유')}`, undefined, G.guestToken);
check('R-44 응답', se.body, rest['R-44'].response);
expect('R-44 "아이유" → 가수/제목 포함, 10개 이하', se.body.tracks.length > 0 && se.body.tracks.length <= 10 && se.body.tracks.every((t) => t.artist.includes('아이유') || t.title.includes('아이유')));
expect('R-44 q 없음 → VALIDATION_ERROR', code(await call('GET', `${P}/music/search`, undefined, G.guestToken)) === 'VALIDATION_ERROR');

// R-45
const pick = se.body.tracks.at(-1);
const q1 = await call('POST', `${P}/music/queue`, { uri: pick.uri, title: pick.title, artist: pick.artist }, G.guestToken);
expect('R-45 → 204', q1.status === 204);
await wait(100);
const qa = evs(wA, 'music.queue_added')[0];
check('music.queue_added 이벤트', qa, fx('music.queue_added'));
expect('queue_added: 곡·넣은 사람(지호)', qa.payload.title === pick.title && qa.payload.addedBy.name === '지호');
await wait(1100);
await call('POST', `${P}/music/control`, { action: 'NEXT' }, G.guestToken);
await wait(100);
expect('NEXT → 넣은 곡이 재생', evs(wG, 'music.now_playing').at(-1).payload.music.nowPlaying.title === pick.title);
expect('R-45 필드 빠짐 → VALIDATION_ERROR', code(await call('POST', `${P}/music/queue`, { uri: 'x', title: 't' }, G.guestToken)) === 'VALIDATION_ERROR');

// R-46
expect('R-46 공동호스트 차 → FORBIDDEN', code(await call('PUT', `${P}/music/lock`, { locked: true }, B.carToken)) === 'FORBIDDEN');
expect('R-46 웹 → FORBIDDEN', code(await call('PUT', `${P}/music/lock`, { locked: true }, G.guestToken)) === 'FORBIDDEN');
const l1 = await call('PUT', `${P}/music/lock`, { locked: true }, A.carToken);
expect('R-46 호스트 차 잠금 → 204', l1.status === 204);
await wait(100);
check('music.lock_changed 이벤트', evs(wG, 'music.lock_changed')[0], fx('music.lock_changed'));
await wait(1100);
const lk = await call('POST', `${P}/music/control`, { action: 'NEXT' }, G.guestToken);
expect('잠금 중 R-43 → 423 MUSIC_LOCKED', lk.status === 423 && code(lk) === 'MUSIC_LOCKED');
expect('잠금 중 R-45 → MUSIC_LOCKED', code(await call('POST', `${P}/music/queue`, { uri: 'u', title: 't', artist: 'a' }, G.guestToken)) === 'MUSIC_LOCKED');
expect('잠금 중 R-44 검색은 가능', (await call('GET', `${P}/music/search?q=a`, undefined, G.guestToken)).status === 200);
expect('스냅샷 music.locked=true', (await call('GET', P, undefined, G.guestToken)).body.session.music.locked === true);
await call('PUT', `${P}/music/lock`, { locked: false }, A.carToken);
expect('잠금 해제 뒤 R-43 → 204', (await call('POST', `${P}/music/control`, { action: 'PLAY' }, G.guestToken)).status === 204);
expect('locked 없음 → VALIDATION_ERROR', code(await call('PUT', `${P}/music/lock`, {}, A.carToken)) === 'VALIDATION_ERROR');

// Q-006: 음악 조작은 입력(일시정지 중 연장)
await call('POST', `${P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, A.carToken);
await wait(1000);
const n0 = evs(wG, 'session.state_changed').length;
await call('POST', `${P}/music/control`, { action: 'PAUSE' }, G.guestToken);
await wait(100);
expect('일시정지 중 R-43 → state_changed 재전송(연장)', evs(wG, 'session.state_changed').length === n0 + 1);
await call('GET', `${P}/music`, undefined, G.guestToken);
await wait(100);
expect('R-42 조회는 연장 안 함', evs(wG, 'session.state_changed').length === n0 + 1);

// summary.trackCount
const titles = evs(wG, 'music.now_playing').map((e) => `${e.payload.music.nowPlaying?.title}|${e.payload.music.nowPlaying?.artist}`);
const played = titles.filter((t, i) => i === 0 || t !== titles[i - 1]).length;
await call('POST', `${P}/end`, undefined, A.carToken);
await wait(150);
const ended = evs(wG, 'session.ended')[0];
expect(`summary.trackCount = 재생된 곡 수(${played})`, ended?.payload.summary.trackCount === played, JSON.stringify(ended?.payload.summary));
expect('종료 뒤 R-42 → SESSION_ENDED', code(await call('GET', `${P}/music`, undefined, A.carToken)) === 'SESSION_ENDED');

for (const c of [wG, wA]) c.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
