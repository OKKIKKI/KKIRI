// 통합 3회차: R-13 내 차, R-14 예약 세션, 예약 흐름(R-06 리더·R-31·R-03·R-04·R-09), 예약 미연결 타이머
// 서버: RESERVE_ATTACH_TIMEOUT_SEC=3 로 "새로" 띄운 뒤 실행(dev-0001 미등록 상태부터 확인)
import { readFileSync } from 'node:fs';
const repo = process.argv[2];
const API = 'http://localhost:8080';
const WS = 'ws://localhost:8080';
const WEB = 'http://localhost:5173';
const rest = JSON.parse(readFileSync(`${repo}/docs/fixtures/rest_examples.json`, 'utf8'));
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
const place = { placeId: 'plc_01', name: '카페 오롯', address: '강원 강릉시 창해로 14', lat: 37.773, lng: 128.947, distanceKm: 1.2 };

const loc = (await fetch(`${API}/v1/auth/kakao/start?redirect=${encodeURIComponent(`${WEB}/me`)}`, { redirect: 'manual' })).headers.get('location');
const U = decodeURIComponent(new URL(loc).hash.slice('#userToken='.length));

// R-13
const c0 = await call('GET', '/v1/me/cars', undefined, U);
expect('R-13 시드 기기(dev-0001)가 아직 R-01 전 → { cars: [] }', JSON.stringify(c0.body) === '{"cars":[]}', JSON.stringify(c0.body));
const A = (await call('POST', '/v1/cars/register', { deviceId: 'dev-0001', displayName: '아이오닉', driverName: '드라이버A' })).body;
const X = (await call('POST', '/v1/cars/register', { deviceId: 'not-in-seed', displayName: 'X차', driverName: '남' })).body;
const c1 = await call('GET', '/v1/me/cars', undefined, U);
check('R-13 응답', c1.body, rest['R-13'].response);
expect('R-13 등록 뒤 → 내 차 1대(아이오닉), 시드에 없는 기기는 안 나옴', JSON.stringify(c1.body) === JSON.stringify({ cars: [{ carId: A.carId, displayName: '아이오닉' }] }), JSON.stringify(c1.body));
expect('R-13 carToken → FORBIDDEN', code(await call('GET', '/v1/me/cars', undefined, A.carToken)) === 'FORBIDDEN');

// R-14
const r1 = await call('POST', '/v1/sessions/reserve', { carId: A.carId, name: '강릉 여행', destination: null }, U);
check('R-14 응답', r1.body, rest['R-14'].response);
const s = r1.body.session;
expect('R-14 RESERVED, 계정 세션, 호스트=내 차(DISCONNECTED), 참가자 0', s.state === 'RESERVED' && s.isAccountSession === true && s.hostCarId === A.carId && s.cars.length === 1 && s.cars[0].status === 'DISCONNECTED' && s.participants.length === 0 && s.expiresAt === null);
expect('R-14 내 차가 아닌 carId → FORBIDDEN', code(await call('POST', '/v1/sessions/reserve', { carId: X.carId, name: 'x', destination: null }, U)) === 'FORBIDDEN');
expect('R-14 carId 없음 → VALIDATION_ERROR', code(await call('POST', '/v1/sessions/reserve', { name: 'x', destination: null }, U)) === 'VALIDATION_ERROR');
expect('R-14 destination.distanceKm 없음 → VALIDATION_ERROR', code(await call('POST', '/v1/sessions/reserve', { carId: A.carId, name: 'x', destination: { ...place, distanceKm: undefined } }, U)) === 'VALIDATION_ERROR');
expect('R-14 토큰 없음 → UNAUTHORIZED', code(await call('POST', '/v1/sessions/reserve', { carId: A.carId, name: 'x', destination: null })) === 'UNAUTHORIZED');
expect('R-14 carToken → FORBIDDEN', code(await call('POST', '/v1/sessions/reserve', { carId: A.carId, name: 'x', destination: null }, A.carToken)) === 'FORBIDDEN');

// 리더 입장(R-06 userToken) + 탑승 전 경유지(R-31) + 크루
const qr = s.qr.url.split('/j/')[1];
const L = await fetch(`${API}/v1/sessions/join`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${U}` }, body: JSON.stringify({ qrToken: qr, name: '드라이버A' }) }).then((r) => r.json());
const lp = L.session.participants.find((p) => p.participantId === L.participantId);
expect('예약 세션에 userToken으로 R-06 → LEADER, isMember=true, 호스트 차 배정', lp.role === 'LEADER' && lp.isMember === true && lp.carId === A.carId);
const C = (await call('POST', '/v1/sessions/join', { qrToken: qr, name: '민지' })).body;
expect('비회원은 CREW', C.session.participants.find((p) => p.participantId === C.participantId).role === 'CREW');
const P = `/v1/sessions/${s.sessionId}`;
expect('RESERVED에서 R-31(탑승 전 경유지) → 200', (await call('POST', `${P}/waypoints`, { place, scope: 'MY_CAR' }, C.guestToken)).status === 200);
const wL = await openWs(s.sessionId, L.guestToken);
await wait(100);

// R-03 → R-04
const p3 = await call('GET', '/v1/cars/me/pending-session', undefined, A.carToken);
expect('R-03(호스트 차) → 예약 세션', p3.body.session?.sessionId === s.sessionId && p3.body.session.state === 'RESERVED');
expect('R-03(다른 차) → null', (await call('GET', '/v1/cars/me/pending-session', undefined, X.carToken)).body.session === null);
const B = (await call('POST', '/v1/cars/register', { deviceId: 'b-dev', displayName: 'B차', driverName: '현우' })).body;
await call('POST', '/v1/sessions/join-by-code', { joinCode: s.joinCode }, B.carToken);
expect('RESERVED에 공동호스트 R-04 → FORBIDDEN(명세 v1.5)', code(await call('POST', `${P}/attach`, undefined, B.carToken)) === 'FORBIDDEN');
const a4 = await call('POST', `${P}/attach`, undefined, A.carToken);
expect('R-04(호스트 차) → ACTIVE, 담아 둔 경유지 그대로', a4.body.session.state === 'ACTIVE' && a4.body.session.waypoints.length === 1 && a4.body.session.waypoints[0].carId === A.carId);
await wait(150);
expect('session.state_changed ACTIVE + car.status_changed CONNECTED', evs(wL, 'session.state_changed').at(-1)?.payload.state === 'ACTIVE' && evs(wL, 'car.status_changed').some((e) => e.payload.carId === A.carId && e.payload.status === 'CONNECTED'));
await wait(3500);
expect('붙은 뒤에는 예약 미연결 타이머 안 울림(RESERVE_ATTACH_TIMEOUT_SEC 지나도 유지)', (await call('GET', P, undefined, A.carToken)).status === 200 && evs(wL, 'session.ended').length === 0);

// 리더 R-09
const e9 = await call('POST', `${P}/end`, undefined, L.guestToken);
await wait(150);
expect('리더(guestToken, LEADER) R-09 → 204, HOST_ENDED', e9.status === 204 && evs(wL, 'session.ended')[0]?.payload.reason === 'HOST_ENDED');

// 예약 미연결 → RESERVE_TIMEOUT
const r2 = (await call('POST', '/v1/sessions/reserve', { carId: A.carId, name: '안 붙는 예약', destination: place }, U)).body.session;
const L2 = await fetch(`${API}/v1/sessions/join`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${U}` }, body: JSON.stringify({ qrToken: r2.qr.url.split('/j/')[1], name: '드라이버A' }) }).then((r) => r.json());
const wL2 = await openWs(r2.sessionId, L2.guestToken);
await wait(1500);
await call('POST', `/v1/sessions/${r2.sessionId}/waypoints`, { place, scope: 'MY_CAR' }, L2.guestToken); // 입력이 있어도 예약 마감은 안 늘어남
await wait(2200);
const e2 = evs(wL2, 'session.ended')[0];
expect('R-04 없이 RESERVE_ATTACH_TIMEOUT_SEC → session.ended RESERVE_TIMEOUT(입력으로 연장 안 됨)', e2?.payload.reason === 'RESERVE_TIMEOUT', JSON.stringify(e2?.payload));
expect('종료 뒤 R-03 → null', (await call('GET', '/v1/cars/me/pending-session', undefined, A.carToken)).body.session === null);

for (const c of [wL, wL2]) c.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
