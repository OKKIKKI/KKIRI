// 통합 4회차(V2): R-47 내 차 모양(car look), car.look_changed, 스냅샷 Car.look
import { readFileSync } from 'node:fs';
const repo = process.argv[2];
const API = 'http://localhost:8080';
const WS = 'ws://localhost:8080';
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
  const events = []; let closed = false;
  ws.onmessage = (e) => events.push(JSON.parse(e.data));
  ws.onclose = () => { closed = true; };
  return new Promise((res, rej) => { ws.onopen = () => res({ ws, events, isClosed: () => closed }); ws.onerror = rej; });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const evs = (c, t) => c.events.filter((e) => e.type === t);
const fx = (t) => wsFx.find((e) => e.type === t);
const code = (r) => r.body?.error?.code;


async function setup(tag) {
  const A = (await call('POST', '/v1/cars/register', { deviceId: `${tag}-A`, displayName: 'A차', driverName: '드라이버A' })).body;
  const B = (await call('POST', '/v1/cars/register', { deviceId: `${tag}-B`, displayName: 'B차', driverName: '현우' })).body;
  const ses = (await call('POST', '/v1/sessions', { name: '강릉', destination: null }, A.carToken)).body.session;
  await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, B.carToken);
  const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
  return { A, B, G, ses, sid: ses.sessionId, P: `/v1/sessions/${ses.sessionId}` };
}
const s = await setup('lk');
expect('처음 스냅샷의 Car.look은 null', s.ses.cars.every((c) => c.look === null));
const wB = await openWs(s.sid, s.B.carToken);
const wG = await openWs(s.sid, s.G.guestToken);
await wait(100);
const req = rest['R-47'].request;
const r1 = await call('POST', `${s.P}/cars/me/look`, req, s.A.carToken);
expect('R-47 → 204', r1.status === 204 && r1.body === null);
await wait(150);
check('car.look_changed 이벤트 모양', evs(wB, 'car.look_changed')[0], fx('car.look_changed'));
expect('payload = A차 carId + 보낸 look', JSON.stringify(evs(wB, 'car.look_changed')[0].payload) === JSON.stringify({ carId: s.A.carId, look: req }));
expect('웹도 같은 이벤트를 받음(보내는 차 자신 포함 전체 연결)', evs(wG, 'car.look_changed').length === 1);
await call('POST', `${s.P}/cars/me/look`, req, s.A.carToken);
await wait(150);
expect('같은 값 다시 → 이벤트 없음', evs(wB, 'car.look_changed').length === 1);
await call('POST', `${s.P}/cars/me/look`, { ...req, steerStep: 2, color: null, hat: false }, s.A.carToken);
await wait(150);
expect('다른 값 → 이벤트 추가', evs(wB, 'car.look_changed').length === 2);
const snap = (await call('GET', s.P, undefined, s.G.guestToken)).body.session;
check('스냅샷 모양(R-07)', snap.cars[0], rest['R-07']?.response?.session?.cars?.[0] ?? snap.cars[0]);
expect('스냅샷 A.look = 마지막 값, B.look = null', JSON.stringify(snap.cars.find((c) => c.carId === s.A.carId).look) === JSON.stringify({ steerStep: 2, color: null, hat: false, balloon: false, character: 'bear', items: ['crown', 'ribbon'] }) && snap.cars.find((c) => c.carId === s.B.carId).look === null);
const bad = (b) => call('POST', `${s.P}/cars/me/look`, b, s.A.carToken);
expect('steerStep 3 → VALIDATION_ERROR', code(await bad({ ...req, steerStep: 3 })) === 'VALIDATION_ERROR');
expect('steerStep 소수 → VALIDATION_ERROR', code(await bad({ ...req, steerStep: 0.5 })) === 'VALIDATION_ERROR');
expect('color 소문자 → VALIDATION_ERROR', code(await bad({ ...req, color: '#f6c343' })) === 'VALIDATION_ERROR');
expect('color 없음(undefined) → VALIDATION_ERROR', code(await bad({ steerStep: 0, hat: false, balloon: false })) === 'VALIDATION_ERROR');
expect('hat 누락 → VALIDATION_ERROR', code(await bad({ steerStep: 0, color: null, balloon: false })) === 'VALIDATION_ERROR');
expect('character 대문자 → VALIDATION_ERROR', code(await bad({ ...req, character: 'Bear' })) === 'VALIDATION_ERROR');
expect('items 5개 → VALIDATION_ERROR', code(await bad({ ...req, items: ['a', 'b', 'c', 'd', 'e'] })) === 'VALIDATION_ERROR');
expect('items 중복 → VALIDATION_ERROR', code(await bad({ ...req, items: ['a', 'a'] })) === 'VALIDATION_ERROR');
expect('character·items 생략(이전 앱) → 204', (await bad({ steerStep: 2, color: null, hat: false, balloon: false })).status === 204);
expect('웹(guest) → FORBIDDEN', code(await call('POST', `${s.P}/cars/me/look`, req, s.G.guestToken)) === 'FORBIDDEN');
expect('토큰 없음 → UNAUTHORIZED', code(await call('POST', `${s.P}/cars/me/look`, req)) === 'UNAUTHORIZED');
await call('POST', `${s.P}/end`, undefined, s.A.carToken);
expect('끝난 세션 → SESSION_ENDED', code(await call('POST', `${s.P}/cars/me/look`, req, s.A.carToken)) === 'SESSION_ENDED');

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
