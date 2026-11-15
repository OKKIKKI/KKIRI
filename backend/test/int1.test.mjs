// 통합 1회차 범위 확인: 실제 서버 응답·이벤트의 모양을 docs/fixtures와 키 단위로 비교
import { readFileSync } from 'node:fs';
const repo = process.argv[2];
const API = 'http://localhost:8080';
const WS = 'ws://localhost:8080';
const rest = JSON.parse(readFileSync(`${repo}/docs/fixtures/rest_examples.json`, 'utf8'));
const wsFx = JSON.parse(readFileSync(`${repo}/docs/fixtures/ws_events.json`, 'utf8'));
const snapFx = JSON.parse(readFileSync(`${repo}/docs/fixtures/session_snapshot.json`, 'utf8'));
let fails = 0;

function shape(a, b, path = '$', out = []) {
  if (b === null || a === null) return out; // null 허용(값 없음)
  if (Array.isArray(b)) {
    if (!Array.isArray(a)) out.push(`${path}: 배열이어야 함`);
    else if (a.length && b.length) shape(a[0], b[0], path + '[0]', out);
    return out;
  }
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
function check(name, actual, fixture) {
  const d = shape(actual, fixture);
  if (d.length) { fails++; console.log(`✗ ${name}\n   ${d.join('\n   ')}`); } else console.log(`✓ ${name}`);
}
function expect(name, cond, info = '') {
  if (!cond) { fails++; console.log(`✗ ${name} ${info}`); } else console.log(`✓ ${name}`);
}
async function call(method, path, body, token) {
  const r = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : null };
}
function openWs(sessionId, token) {
  const ws = new WebSocket(`${WS}/v1/ws?sessionId=${sessionId}&token=${token}`);
  const events = [];
  ws.onmessage = (e) => events.push(JSON.parse(e.data));
  return new Promise((res, rej) => { ws.onopen = () => res({ ws, events }); ws.onerror = rej; });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const evFx = (t) => wsFx.find((e) => e.type === t);

// R-01
const a = await call('POST', '/v1/cars/register', rest['R-01'].request);
check('R-01 응답', a.body, rest['R-01'].response);
const a2 = await call('POST', '/v1/cars/register', rest['R-01'].request);
expect('R-01 같은 deviceId → 같은 carId', a2.body.carId === a.body.carId);
const b = await call('POST', '/v1/cars/register', { deviceId: 'dev-0002', displayName: 'B차', driverName: '현우' });

// R-02
const s = await call('POST', '/v1/sessions', { name: '강릉 여행', destination: snapFx.destination }, a.body.carToken);
check('R-02 응답 (Session)', s.body, { session: snapFx });
const ses = s.body.session;
expect('R-02 ACTIVE·HOST·isAccountSession=false', ses.state === 'ACTIVE' && ses.cars[0].role === 'HOST' && ses.isAccountSession === false);
expect('R-02 joinCode 6자리', /^\d{6}$/.test(ses.joinCode));
expect('R-02 qr.url = KKIRI_WEB_BASE/j/{token}', ses.qr.url.startsWith('http://localhost:5173/j/'));
expect('R-02 토큰 없음 → 401 UNAUTHORIZED', (await call('POST', '/v1/sessions', { name: 'x' })).body.error.code === 'UNAUTHORIZED');
expect('R-02 name 없음 → 400 VALIDATION_ERROR', (await call('POST', '/v1/sessions', {}, a.body.carToken)).body.error.code === 'VALIDATION_ERROR');

// WS (호스트 차)
const hostWs = await openWs(ses.sessionId, a.body.carToken);
await wait(200);
check('WS session.snapshot', hostWs.events[0], evFx('session.snapshot'));
expect('snapshot you.actorType=CAR role=HOST', hostWs.events[0].payload.you.actorType === 'CAR' && hostWs.events[0].payload.you.role === 'HOST');

// R-06
const qrToken = ses.qr.url.split('/j/')[1];
const g = await call('POST', '/v1/sessions/join', { qrToken, name: '민지' });
check('R-06 응답', g.body, rest['R-06'].response);
expect('R-06 CREW, 호스트 차 배정, isMember=false', g.body.session.participants[0].role === 'CREW' && g.body.session.participants[0].carId === a.body.carId && !g.body.session.participants[0].isMember);
expect('R-06 틀린 qrToken → 404 SESSION_NOT_FOUND', (await call('POST', '/v1/sessions/join', { qrToken: 'nope', name: 'x' })).body.error.code === 'SESSION_NOT_FOUND');

// R-05
const j = await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode, applyDestination: true }, b.body.carToken);
check('R-05 응답', j.body, rest['R-05'].response);
expect('R-05 COHOST', j.body.session.cars.find((c) => c.carId === b.body.carId)?.role === 'COHOST');
expect('R-05 틀린 코드 → 404 JOIN_CODE_INVALID', (await call('POST', '/v1/sessions/join-by-code', { joinCode: '000000x' }, b.body.carToken)).body.error.code === 'JOIN_CODE_INVALID');

await wait(200);
const pj = hostWs.events.find((e) => e.type === 'participant.joined');
const cj = hostWs.events.find((e) => e.type === 'car.joined');
check('WS participant.joined', pj, evFx('participant.joined'));
check('WS car.joined', cj, evFx('car.joined'));
expect('seq 1씩 증가 (snapshot 0 → 1 → 2)', hostWs.events.map((e) => e.seq).join(',') === '0,1,2', hostWs.events.map((e) => e.seq).join(','));

// 동승자 WS
const guestWs = await openWs(ses.sessionId, g.body.guestToken);
await wait(200);
const gy = guestWs.events[0].payload.you;
expect('동승자 snapshot you=PARTICIPANT/CREW, seq=현재값 2', gy.actorType === 'PARTICIPANT' && gy.role === 'CREW' && guestWs.events[0].seq === 2);

// R-07
const g7 = await call('GET', `/v1/sessions/${ses.sessionId}`, undefined, g.body.guestToken);
check('R-07 응답', g7.body, { session: snapFx });
const other = await call('POST', '/v1/cars/register', { deviceId: 'dev-0003', displayName: 'C차', driverName: '수진' });
expect('R-07 소속 아닌 차 → 403 FORBIDDEN', (await call('GET', `/v1/sessions/${ses.sessionId}`, undefined, other.body.carToken)).body.error.code === 'FORBIDDEN');
expect('R-07 없는 세션 → 404 SESSION_NOT_FOUND', (await call('GET', '/v1/sessions/ses_x', undefined, g.body.guestToken)).body.error.code === 'SESSION_NOT_FOUND');

// WS 거부
const bad = await new Promise((res) => { const w = new WebSocket(`${WS}/v1/ws?sessionId=${ses.sessionId}&token=bad`); w.onopen = () => res('open'); w.onerror = () => res('rejected'); });
expect('WS 잘못된 토큰 거부', bad === 'rejected');

// CORS
const cors = await fetch(API + '/health', { headers: { Origin: 'http://localhost:5173' } });
const corsBad = await fetch(API + '/health', { headers: { Origin: 'http://evil.example' } });
expect('CORS: KKIRI_WEB_BASE만 허용', cors.headers.get('access-control-allow-origin') === 'http://localhost:5173' && corsBad.headers.get('access-control-allow-origin') === null);

// v1.1: online = WS 연결 여부, userToken 계열은 세션 안 호출 불가
expect('R-06 직후 online=false (WS 연결 전)', g.body.session.participants[0].online === false);
expect('동승자 WS 연결 중 online=true', g7.body.session.participants[0].online === true);
guestWs.ws.close(); await wait(300);
const g8 = await call('GET', `/v1/sessions/${ses.sessionId}`, undefined, g.body.guestToken);
expect('동승자 WS 끊긴 뒤 online=false', g8.body.session.participants[0].online === false);
expect('없는 경로 → 404 NOT_FOUND', (await call('GET', '/v1/nope')).body.error.code === 'NOT_FOUND');
hostWs.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
