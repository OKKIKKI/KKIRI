// 통합 2회차: R-20, R-22~R-24 확인 (delivery.mode, 긴급 제한, 보관함)
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
function openWs(sessionId, token) {
  const ws = new WebSocket(`${WS}/v1/ws?sessionId=${sessionId}&token=${token}`);
  const events = [];
  ws.onmessage = (e) => events.push(JSON.parse(e.data));
  return new Promise((res, rej) => { ws.onopen = () => res({ ws, events }); ws.onerror = rej; });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const lastMsg = (c) => c.events.filter((e) => e.type === 'message.created').at(-1);
const msgEvents = (c) => c.events.filter((e) => e.type === 'message.created');
const fxMsg = wsFx.find((e) => e.type === 'message.created');

const A = (await call('POST', '/v1/cars/register', { deviceId: 'm-A', displayName: 'A차', driverName: '드라이버A' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'm-B', displayName: 'B차', driverName: '현우' })).body;
const C = (await call('POST', '/v1/cars/register', { deviceId: 'm-C', displayName: 'C차', driverName: '수진' })).body;
const ses = (await call('POST', '/v1/sessions', { name: '강릉', destination: null }, A.carToken)).body.session;
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode, applyDestination: false }, B.carToken);
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode, applyDestination: false }, C.carToken);
const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
const sid = ses.sessionId;
const P = `/v1/sessions/${sid}`;
const wA = await openWs(sid, A.carToken), wB = await openWs(sid, B.carToken), wC = await openWs(sid, C.carToken), wG = await openWs(sid, G.guestToken);
await wait(150);

// 1. 웹 TEXT → 전체
const r1 = await call('POST', `${P}/messages`, { kind: 'TEXT', text: '기름 넣고 갈게', urgent: false, target: 'ALL' }, G.guestToken);
check('R-20 응답', r1.body, rest['R-20'].response);
expect('R-20 sender = PARTICIPANT 민지, car A', r1.body.message.sender.type === 'PARTICIPANT' && r1.body.message.sender.name === '민지' && r1.body.message.sender.carId === A.carId);
await wait(150);
check('message.created 이벤트', lastMsg(wB), fxMsg);
expect('음소거 없음: A SPEAK, B SPEAK, C SPEAK, 웹 DISPLAY',
  [wA, wB, wC, wG].map((c) => lastMsg(c)?.payload.delivery.mode).join() === 'SPEAK,SPEAK,SPEAK,DISPLAY',
  [wA, wB, wC, wG].map((c) => lastMsg(c)?.payload.delivery.mode).join());

// 2. B가 A를 음소거
const r2 = await call('PUT', `${P}/mutes`, { targetCarId: A.carId, muted: true }, B.carToken);
check('R-22 응답', r2.body, rest['R-22'].response);
expect('R-22 mutes = [B→A]', JSON.stringify(r2.body.mutes) === JSON.stringify([{ byCarId: B.carId, targetCarId: A.carId }]));
await wait(150);
const mc = wG.events.find((e) => e.type === 'mute.changed');
check('mute.changed 이벤트', mc, wsFx.find((e) => e.type === 'mute.changed'));

// 3. A차 동승자 일반 메시지 → B는 HOLD
const r3 = await call('POST', `${P}/messages`, { kind: 'TEXT', text: '휴게소 들르자', urgent: false, target: 'ALL' }, G.guestToken);
await wait(150);
expect('B가 A를 음소거: A SPEAK, B HOLD, C SPEAK', [wA, wB, wC].map((c) => lastMsg(c).payload.delivery.mode).join() === 'SPEAK,HOLD,SPEAK');

// 4. R-23
const r4 = await call('GET', `${P}/held-messages`, undefined, B.carToken);
check('R-23 응답', r4.body, rest['R-23'].response);
expect('R-23 B 보관함 = 방금 메시지 1건', r4.body.messages.length === 1 && r4.body.messages[0].messageId === r3.body.message.messageId);
expect('R-23 A 보관함 비어 있음', (await call('GET', `${P}/held-messages`, undefined, A.carToken)).body.messages.length === 0);

// 5. 긴급: 음소거한 B에도 SPEAK, 30초 안 두 번째는 429
const before = msgEvents(wB).length;
const r5 = await call('POST', `${P}/messages`, { kind: 'TEXT', text: '긴급!', urgent: true, target: 'ALL' }, G.guestToken);
await wait(150);
expect('긴급 1건째 성공, B에 SPEAK', r5.status === 200 && lastMsg(wB).payload.delivery.mode === 'SPEAK');
const r6 = await call('POST', `${P}/messages`, { kind: 'TEXT', text: '또 긴급', urgent: true, target: 'ALL' }, G.guestToken);
await wait(150);
expect('긴급 2건째 → 429 URGENT_RATE_LIMITED + retryAfterSec', r6.status === 429 && r6.body.error.code === 'URGENT_RATE_LIMITED' && Number.isInteger(r6.body.error.details.retryAfterSec) && r6.body.error.details.retryAfterSec > 0, JSON.stringify(r6.body));
expect('실패한 긴급은 아무에게도 안 감(전체 실패)', msgEvents(wB).length === before + 1 && msgEvents(wC).filter((e) => e.payload.message.text === '또 긴급').length === 0);
const rC = await call('POST', `${P}/messages`, { kind: 'BROADCAST', text: 'C 긴급', urgent: true, target: 'ALL' }, C.carToken);
expect('다른 보낸 사람(C)의 긴급은 제한 없음', rC.status === 200);

// 6. A 차량 BROADCAST → B만
const r7 = await call('POST', `${P}/messages`, { kind: 'BROADCAST', text: '다음 휴게소', urgent: false, target: B.carId }, A.carToken);
await wait(150);
expect('R-20 sender = CAR 드라이버A', r7.body.message.sender.type === 'CAR' && r7.body.message.sender.id === A.carId && r7.body.message.sender.name === '드라이버A');
expect('target=B: A DISPLAY(자기 차), B HOLD, 웹(A차) DISPLAY', [wA, wB, wG].map((c) => lastMsg(c).payload.message.messageId === r7.body.message.messageId ? lastMsg(c).payload.delivery.mode : 'none').join() === 'DISPLAY,HOLD,DISPLAY');
expect('target=B: C는 못 받음', lastMsg(wC).payload.message.messageId !== r7.body.message.messageId);

// 7. R-24
const held = (await call('GET', `${P}/held-messages`, undefined, B.carToken)).body.messages;
expect('B 보관함 2건', held.length === 2);
const r8 = await call('POST', `${P}/held-messages/ack`, { messageIds: [held[0].messageId, 'msg_none'] }, B.carToken);
expect('R-24 → 204', r8.status === 204 && r8.body === null);
const left = (await call('GET', `${P}/held-messages`, undefined, B.carToken)).body.messages;
expect('R-24 뒤 1건 남음', left.length === 1 && left[0].messageId === held[1].messageId);

// 8. 음소거 해제·ALL
await call('PUT', `${P}/mutes`, { targetCarId: A.carId, muted: false }, B.carToken);
const all = (await call('PUT', `${P}/mutes`, { targetCarId: 'ALL', muted: true }, C.carToken)).body.mutes;
expect('B 해제 + C 전체 음소거 → [C→A, C→B]', JSON.stringify(all) === JSON.stringify([{ byCarId: C.carId, targetCarId: A.carId }, { byCarId: C.carId, targetCarId: B.carId }]), JSON.stringify(all));
const unall = (await call('PUT', `${P}/mutes`, { targetCarId: 'ALL', muted: false }, C.carToken)).body.mutes;
expect('C 전체 해제 → []', unall.length === 0);

// 9. 오류
const code = async (...a) => (await call(...a)).body?.error?.code;
expect('웹이 BROADCAST → VALIDATION_ERROR', await code('POST', `${P}/messages`, { kind: 'BROADCAST', text: 'x', target: 'ALL' }, G.guestToken) === 'VALIDATION_ERROR');
expect('차량이 TEXT → VALIDATION_ERROR', await code('POST', `${P}/messages`, { kind: 'TEXT', text: 'x', target: 'ALL' }, A.carToken) === 'VALIDATION_ERROR');
expect('VOICE를 R-20으로 → VALIDATION_ERROR', await code('POST', `${P}/messages`, { kind: 'VOICE', text: 'x', target: 'ALL' }, G.guestToken) === 'VALIDATION_ERROR');
expect('없는 target → VALIDATION_ERROR', await code('POST', `${P}/messages`, { kind: 'TEXT', text: 'x', target: 'car_none' }, G.guestToken) === 'VALIDATION_ERROR');
expect('빈 text → VALIDATION_ERROR', await code('POST', `${P}/messages`, { kind: 'TEXT', text: ' ', target: 'ALL' }, G.guestToken) === 'VALIDATION_ERROR');
expect('웹이 R-22 → FORBIDDEN', await code('PUT', `${P}/mutes`, { targetCarId: A.carId, muted: true }, G.guestToken) === 'FORBIDDEN');
expect('내 차 음소거 → VALIDATION_ERROR', await code('PUT', `${P}/mutes`, { targetCarId: B.carId, muted: true }, B.carToken) === 'VALIDATION_ERROR');
expect('웹이 R-23 → FORBIDDEN', await code('GET', `${P}/held-messages`, undefined, G.guestToken) === 'FORBIDDEN');
expect('토큰 없이 R-20 → UNAUTHORIZED', await code('POST', `${P}/messages`, { kind: 'TEXT', text: 'x', target: 'ALL' }) === 'UNAUTHORIZED');
expect('seq 연속 증가(A 기준)', wA.events.every((e, i) => i === 0 || e.seq === wA.events[i - 1].seq + 1), wA.events.map((e) => e.seq).join());

for (const c of [wA, wB, wC, wG]) c.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
