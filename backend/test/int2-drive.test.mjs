// 통합 2회차: R-08 주행 상태·일시정지·무입력 종료, R-09 종료·파기 (서버를 PAUSE_TIMEOUT_SEC=3으로 실행)
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

// ── 1. R-08 기본 ──
const s1 = await setup('d1');
const wG = await openWs(s1.sid, s1.G.guestToken);
await wait(100);
const r1 = await call('POST', `${s1.P}/car-state`, rest['R-08'].request, s1.A.carToken);
expect('R-08 → 204', r1.status === 204 && r1.body === null);
await wait(150);
check('car.status_changed 이벤트', evs(wG, 'car.status_changed')[0], fx('car.status_changed'));
expect('A: CONNECTED·DRIVING', JSON.stringify(evs(wG, 'car.status_changed')[0].payload) === JSON.stringify({ carId: s1.A.carId, status: 'CONNECTED', driveState: 'DRIVING' }));
await call('POST', `${s1.P}/car-state`, rest['R-08'].request, s1.A.carToken);
await wait(150);
expect('같은 값 다시 → 이벤트 없음', evs(wG, 'car.status_changed').length === 1);
await call('POST', `${s1.P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, s1.B.carToken);
await wait(150);
let snap = (await call('GET', s1.P, undefined, s1.G.guestToken)).body.session;
expect('공동호스트(B) 시동 OFF → B만 PAUSED, 세션 ACTIVE', snap.state === 'ACTIVE' && snap.cars.find((c) => c.carId === s1.B.carId).status === 'PAUSED' && evs(wG, 'session.state_changed').length === 0);
expect('R-08 driveState 틀림 → VALIDATION_ERROR', code(await call('POST', `${s1.P}/car-state`, { driveState: 'P', ignition: 'ON' }, s1.A.carToken)) === 'VALIDATION_ERROR');
expect('R-08 ignition 틀림 → VALIDATION_ERROR', code(await call('POST', `${s1.P}/car-state`, { driveState: 'PARKED', ignition: 'X' }, s1.A.carToken)) === 'VALIDATION_ERROR');
expect('R-08 웹 → FORBIDDEN', code(await call('POST', `${s1.P}/car-state`, rest['R-08'].request, s1.G.guestToken)) === 'FORBIDDEN');

// ── 2. 호스트 시동 OFF → PAUSED, 입력 → 연장, 무입력 → PAUSE_TIMEOUT ──
const t0 = Date.now();
await call('POST', `${s1.P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, s1.A.carToken);
await wait(150);
const sc1 = evs(wG, 'session.state_changed')[0];
check('session.state_changed 이벤트', sc1, fx('session.state_changed'));
const exp1 = Date.parse(sc1.payload.expiresAt);
expect('호스트 OFF → PAUSED, pausedAt, expiresAt ≈ +3초', sc1.payload.state === 'PAUSED' && sc1.payload.pausedAt && Math.abs(exp1 - (t0 + 3000)) < 1500, JSON.stringify(sc1.payload));
snap = (await call('GET', s1.P, undefined, s1.G.guestToken)).body.session;
expect('스냅샷 state=PAUSED, expiresAt 일치', snap.state === 'PAUSED' && snap.expiresAt === sc1.payload.expiresAt);
await wait(1500);
await call('GET', s1.P, undefined, s1.G.guestToken);              // R-07: 입력 아님
await call('PUT', `${s1.P}/mutes`, { targetCarId: s1.B.carId, muted: true }, s1.A.carToken); // R-22: 입력 아님
await call('POST', `${s1.P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, s1.A.carToken); // R-08: 입력 아님
await wait(100);
expect('R-07·R-22·R-08은 연장 안 함(state_changed 1건 그대로)', evs(wG, 'session.state_changed').length === 1);
await call('POST', `${s1.P}/messages`, { kind: 'TEXT', text: '휴게소 도착', urgent: false, target: 'ALL' }, s1.G.guestToken); // R-20: 입력
await wait(150);
const sc2 = evs(wG, 'session.state_changed')[1];
expect('입력(R-20) → state_changed 재전송, expiresAt 늦춰짐', sc2 && sc2.payload.state === 'PAUSED' && Date.parse(sc2.payload.expiresAt) > exp1 && sc2.payload.pausedAt === sc1.payload.pausedAt, JSON.stringify(sc2?.payload));
await wait(2000);
expect('원래 만료 시각이 지나도 아직 안 끝남', evs(wG, 'session.ended').length === 0);
await wait(1500);
const ended = evs(wG, 'session.ended')[0];
check('session.ended 이벤트', ended, fx('session.ended'));
expect('무입력 → reason=PAUSE_TIMEOUT, summary.cars=[A차,B차], trackCount=0', ended?.payload.reason === 'PAUSE_TIMEOUT' && JSON.stringify(ended.payload.summary.cars) === '["A차","B차"]' && ended.payload.summary.trackCount === 0);
await wait(100);
expect('WebSocket 닫힘', wG.isClosed());
expect('종료 뒤 R-07(차) → 410 SESSION_ENDED', code(await call('GET', s1.P, undefined, s1.A.carToken)) === 'SESSION_ENDED');
expect('종료 뒤 guestToken 파기 → 401 UNAUTHORIZED', code(await call('GET', s1.P, undefined, s1.G.guestToken)) === 'UNAUTHORIZED');
expect('종료 뒤 QR 입장 → SESSION_ENDED', code(await call('POST', '/v1/sessions/join', { qrToken: s1.ses.qr.url.split('/j/')[1], name: 'x' })) === 'SESSION_ENDED');
expect('종료 뒤 접속코드 합류 → SESSION_ENDED', code(await call('POST', '/v1/sessions/join-by-code', { joinCode: s1.ses.joinCode }, s1.B.carToken)) === 'SESSION_ENDED');
const wsAfter = await new Promise((res) => { const w = new WebSocket(`${WS}/v1/ws?sessionId=${s1.sid}&token=${s1.A.carToken}`); w.onopen = () => res('open'); w.onerror = () => res('rejected'); });
expect('종료 뒤 WS 연결 거부', wsAfter === 'rejected');
expect('차량 등록은 남음(같은 deviceId → 같은 carId)', (await call('POST', '/v1/cars/register', { deviceId: 'd1-A', displayName: 'A차', driverName: '드라이버A' })).body.carId === s1.A.carId);

// ── 3. R-09 ──
const s2 = await setup('d2');
const wB2 = await openWs(s2.sid, s2.B.carToken);
await call('POST', `${s2.P}/waypoints`, { place: { placeId: 'plc_01', name: '카페 오롯', address: 'x', lat: 1, lng: 2, distanceKm: 1 }, scope: 'PROPOSE_OTHERS' }, s2.G.guestToken);
expect('R-09 크루 → FORBIDDEN', code(await call('POST', `${s2.P}/end`, undefined, s2.G.guestToken)) === 'FORBIDDEN');
expect('R-09 공동호스트 차 → FORBIDDEN', code(await call('POST', `${s2.P}/end`, undefined, s2.B.carToken)) === 'FORBIDDEN');
const e2 = await call('POST', `${s2.P}/end`, undefined, s2.A.carToken);
expect('R-09 호스트 차 → 204', e2.status === 204 && e2.body === null);
await wait(150);
const ended2 = evs(wB2, 'session.ended')[0];
expect('session.ended HOST_ENDED, summary.places=[카페 오롯]', ended2?.payload.reason === 'HOST_ENDED' && JSON.stringify(ended2.payload.summary.places) === '["카페 오롯"]', JSON.stringify(ended2?.payload));
expect('R-09 다시 → SESSION_ENDED', code(await call('POST', `${s2.P}/end`, undefined, s2.A.carToken)) === 'SESSION_ENDED');
await wait(2500);
expect('종료 뒤 제안 만료 타이머 안 울림(이벤트 추가 없음)', wB2.events.filter((e) => e.type === 'waypoint.proposal_resolved').length === 0);

// ── 4. 시동 OFF 안 했으면(ACTIVE) 무로그인 세션은 안 끝남 ──
const s3 = await setup('d3');
await wait(3500);
expect('ACTIVE 무로그인 세션은 시간이 지나도 유지', (await call('GET', s3.P, undefined, s3.A.carToken)).status === 200);

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
