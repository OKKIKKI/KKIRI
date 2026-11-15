// 통합 3회차: R-03·R-04 다시 연결, 차량 끊김 → DISCONNECTED (서버: CAR_DISCONNECT_SEC=2 PAUSE_TIMEOUT_SEC=4)
import { readFileSync } from 'node:fs';
const repo = process.argv[2];
const API = 'http://localhost:8080';
const WS = 'ws://localhost:8080';
const rest = JSON.parse(readFileSync(`${repo}/docs/fixtures/rest_examples.json`, 'utf8'));
const snapFx = JSON.parse(readFileSync(`${repo}/docs/fixtures/session_snapshot.json`, 'utf8'));
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
const code = (r) => r.body?.error?.code;
const pending = async (tok) => (await call('GET', '/v1/cars/me/pending-session', undefined, tok)).body;

const A = (await call('POST', '/v1/cars/register', { deviceId: 'r-A', displayName: 'A차', driverName: '드라이버A' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'r-B', displayName: 'B차', driverName: '현우' })).body;
const X = (await call('POST', '/v1/cars/register', { deviceId: 'r-X', displayName: 'X차', driverName: '남' })).body;

// R-03 세션 없음
const p0 = await pending(A.carToken);
check('R-03 응답(null)', p0, rest['R-03'].response);
expect('R-03 세션 없음 → { session: null }', p0.session === null);
expect('R-03 토큰 없음 → UNAUTHORIZED', code(await call('GET', '/v1/cars/me/pending-session')) === 'UNAUTHORIZED');

const ses = (await call('POST', '/v1/sessions', { name: '강릉', destination: null }, A.carToken)).body.session;
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, B.carToken);
const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
const sid = ses.sessionId, P = `/v1/sessions/${sid}`;
const wG = await openWs(sid, G.guestToken);
await wait(100);
expect('ACTIVE·CONNECTED면 R-03 → null', (await pending(A.carToken)).session === null);

// 호스트 시동 OFF → PAUSED → R-03 → R-04
await call('POST', `${P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, A.carToken);
const p1 = await pending(A.carToken);
check('R-03 응답(Session)', p1, { session: snapFx });
expect('PAUSED 세션 → R-03이 돌려줌', p1.session?.sessionId === sid && p1.session.state === 'PAUSED');
const a1 = await call('POST', `${P}/attach`, undefined, A.carToken);
check('R-04 응답', a1.body, { session: snapFx });
expect('R-04 → ACTIVE, pausedAt·expiresAt null, 호스트 CONNECTED', a1.body.session.state === 'ACTIVE' && a1.body.session.pausedAt === null && a1.body.session.expiresAt === null && a1.body.session.cars.find((c) => c.carId === A.carId).status === 'CONNECTED');
await wait(150);
const sc = evs(wG, 'session.state_changed').at(-1);
expect('session.state_changed {ACTIVE, null, null}', sc && JSON.stringify(sc.payload) === JSON.stringify({ state: 'ACTIVE', pausedAt: null, expiresAt: null }), JSON.stringify(sc?.payload));
expect('car.status_changed (A CONNECTED)', evs(wG, 'car.status_changed').at(-1).payload.status === 'CONNECTED' && evs(wG, 'car.status_changed').at(-1).payload.carId === A.carId);
expect('R-04 다시 → 이벤트 없이 세션만', (await call('POST', `${P}/attach`, undefined, A.carToken)).status === 200);

// 공동호스트 OFF → R-03(B) → R-04(B): 세션 상태는 그대로
await call('POST', `${P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, B.carToken);
const pb = await pending(B.carToken);
expect('공동호스트가 꺼진 ACTIVE 세션 → R-03(B)이 돌려줌', pb.session?.sessionId === sid && pb.session.state === 'ACTIVE');
const before = evs(wG, 'session.state_changed').length;
const ab = await call('POST', `${P}/attach`, undefined, B.carToken);
await wait(150);
expect('R-04(B) → B CONNECTED, 세션 state_changed 없음', ab.body.session.cars.find((c) => c.carId === B.carId).status === 'CONNECTED' && evs(wG, 'session.state_changed').length === before);

// 오류
expect('R-04 웹 → FORBIDDEN', code(await call('POST', `${P}/attach`, undefined, G.guestToken)) === 'FORBIDDEN');
expect('R-04 소속 아닌 차 → FORBIDDEN', code(await call('POST', `${P}/attach`, undefined, X.carToken)) === 'FORBIDDEN');
expect('R-04 없는 세션 → SESSION_NOT_FOUND', code(await call('POST', '/v1/sessions/ses_none/attach', undefined, A.carToken)) === 'SESSION_NOT_FOUND');

// 재연결 뒤 종료 타이머가 취소됐는지 (PAUSE_TIMEOUT_SEC=4)
await wait(4500);
expect('ACTIVE로 돌아온 뒤 무입력 타이머 안 울림', (await call('GET', P, undefined, A.carToken)).status === 200 && evs(wG, 'session.ended').length === 0);

// 차량 끊김
const wA = await openWs(sid, A.carToken);
await wait(100);
wA.ws.close();
await wait(900);
const wA2 = await openWs(sid, A.carToken);   // 2초 안에 다시 붙음
await wait(1800);
expect('2초 안에 다시 붙으면 DISCONNECTED 안 보냄', !evs(wG, 'car.status_changed').some((e) => e.payload.status === 'DISCONNECTED'));
wA2.ws.close();
await wait(2600);
const dc = evs(wG, 'car.status_changed').at(-1);
expect('끊기고 CAR_DISCONNECT_SEC 지나면 A DISCONNECTED', dc?.payload.carId === A.carId && dc.payload.status === 'DISCONNECTED', JSON.stringify(dc?.payload));
expect('DISCONNECTED 차 → R-03이 세션 돌려줌', (await pending(A.carToken)).session?.sessionId === sid);
const wA3 = await openWs(sid, A.carToken);
await wait(150);
const rc = evs(wG, 'car.status_changed').at(-1);
expect('WS 다시 붙으면 A CONNECTED 이벤트', rc.payload.carId === A.carId && rc.payload.status === 'CONNECTED');
expect('다시 붙은 차의 스냅샷도 CONNECTED', wA3.events.find((e) => e.type === 'session.snapshot').payload.session.cars.find((c) => c.carId === A.carId).status === 'CONNECTED');

// 시동 OFF로 PAUSED인 차는 끊겨도 DISCONNECTED로 안 바꿈
const wB = await openWs(sid, B.carToken);
await call('POST', `${P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, B.carToken);
await wait(100);
wB.ws.close();
await wait(2600);
expect('PAUSED 차는 끊겨도 PAUSED 유지', (await call('GET', P, undefined, A.carToken)).body.session.cars.find((c) => c.carId === B.carId).status === 'PAUSED');

// 종료 뒤 R-03·R-04
await call('POST', `${P}/end`, undefined, A.carToken);
expect('종료 뒤 R-03 → null', (await pending(A.carToken)).session === null);
expect('종료 뒤 R-04 → SESSION_ENDED', code(await call('POST', `${P}/attach`, undefined, A.carToken)) === 'SESSION_ENDED');

for (const c of [wG, wA3]) c.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
