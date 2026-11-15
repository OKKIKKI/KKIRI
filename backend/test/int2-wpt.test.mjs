// 통합 2회차: R-30~R-34 (서버를 PROPOSAL_TIMEOUT_SEC=2로 띄워 실행)
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
  const events = [];
  ws.onmessage = (e) => events.push(JSON.parse(e.data));
  return new Promise((res, rej) => { ws.onopen = () => res({ ws, events }); ws.onerror = rej; });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const evs = (c, t) => c.events.filter((e) => e.type === t);
const fx = (t) => wsFx.find((e) => e.type === t);
const code = (r) => r.body?.error?.code;

const A = (await call('POST', '/v1/cars/register', { deviceId: 'w-A', displayName: 'A차', driverName: '드라이버A' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'w-B', displayName: 'B차', driverName: '현우' })).body;
const C = (await call('POST', '/v1/cars/register', { deviceId: 'w-C', displayName: 'C차', driverName: '수진' })).body;
const ses = (await call('POST', '/v1/sessions', { name: '강릉', destination: null }, A.carToken)).body.session;
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, B.carToken);
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, C.carToken);
const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
const sid = ses.sessionId, P = `/v1/sessions/${sid}`;
const wA = await openWs(sid, A.carToken), wB = await openWs(sid, B.carToken), wG = await openWs(sid, G.guestToken);
await wait(150);

// R-30
const s1 = await call('GET', `${P}/places?query=${encodeURIComponent('휴게소')}`, undefined, G.guestToken);
check('R-30 응답', s1.body, rest['R-30'].response);
expect('R-30 "휴게소" → 이름에 휴게소 포함만', s1.body.places.length > 0 && s1.body.places.every((p) => p.name.includes('휴게소')));
expect('R-30 query 없음 → 시드 8곳 전부', (await call('GET', `${P}/places`, undefined, G.guestToken)).body.places.length === 8);
expect('R-30 결과 없음 → []', (await call('GET', `${P}/places?query=zzz`, undefined, G.guestToken)).body.places.length === 0);
expect('R-30 차량 → FORBIDDEN', code(await call('GET', `${P}/places`, undefined, A.carToken)) === 'FORBIDDEN');
const [cafe, rest2] = [s1.body.places[0], (await call('GET', `${P}/places`, undefined, G.guestToken)).body.places[0]];

// R-31 MY_CAR
const w1 = await call('POST', `${P}/waypoints`, { place: rest2, scope: 'MY_CAR' }, G.guestToken);
check('R-31 응답', w1.body, rest['R-31'].response);
expect('R-31 내 차(A), addedBy 민지, proposal=null', w1.body.waypoint.carId === A.carId && w1.body.waypoint.addedBy.name === '민지' && w1.body.proposal === null);
await wait(150);
check('waypoint.added 이벤트', evs(wB, 'waypoint.added')[0], fx('waypoint.added'));
expect('byUndo=false', evs(wB, 'waypoint.added')[0].payload.byUndo === false);

// R-31 PROPOSE_OTHERS
const w2 = await call('POST', `${P}/waypoints`, { place: cafe, scope: 'PROPOSE_OTHERS' }, G.guestToken);
const prp = w2.body.proposal;
expect('PROPOSE_OTHERS: 내 차 추가 + 제안(B·C PENDING)', w2.body.waypoint.carId === A.carId && prp && prp.fromCarId === A.carId && prp.responses.map((r) => `${r.carId}:${r.status}`).join() === `${B.carId}:PENDING,${C.carId}:PENDING`);
await wait(150);
check('waypoint.proposed 이벤트', evs(wB, 'waypoint.proposed')[0], fx('waypoint.proposed'));
expect('스냅샷(R-07) proposals에 포함, waypoints 2개', (await call('GET', P, undefined, G.guestToken)).body.session.proposals.length === 1);

// R-34
const p1 = await call('POST', `${P}/proposals/${prp.proposalId}/respond`, { accept: true }, B.carToken);
check('R-34 응답', p1.body, rest['R-34'].response);
expect('R-34 수락 → B 경유지, addedBy=제안자', p1.body.waypoint.carId === B.carId && p1.body.waypoint.addedBy.name === '민지' && p1.body.proposal.responses.find((r) => r.carId === B.carId).status === 'ACCEPTED');
await wait(150);
const res1 = evs(wG, 'waypoint.proposal_resolved');
check('waypoint.proposal_resolved 이벤트', res1[0], fx('waypoint.proposal_resolved'));
expect('resolved(B, ACCEPTED) 다음 waypoint.added(B)', res1[0].payload.status === 'ACCEPTED' && evs(wG, 'waypoint.added').at(-1).payload.waypoint.carId === B.carId && evs(wG, 'waypoint.added').at(-1).seq > res1[0].seq);
expect('R-34 다시 → PROPOSAL_CLOSED', code(await call('POST', `${P}/proposals/${prp.proposalId}/respond`, { accept: false }, B.carToken)) === 'PROPOSAL_CLOSED');
expect('R-34 제안한 차(A) → FORBIDDEN', code(await call('POST', `${P}/proposals/${prp.proposalId}/respond`, { accept: true }, A.carToken)) === 'FORBIDDEN');
expect('R-34 없는 제안 → NOT_FOUND', code(await call('POST', `${P}/proposals/prp_none/respond`, { accept: true }, B.carToken)) === 'NOT_FOUND');
expect('R-34 웹 → FORBIDDEN', code(await call('POST', `${P}/proposals/${prp.proposalId}/respond`, { accept: true }, G.guestToken)) === 'FORBIDDEN');

// 만료 (C 무응답)
await wait(2600);
const exp = evs(wG, 'waypoint.proposal_resolved').find((e) => e.payload.carId === C.carId);
expect('PROPOSAL_TIMEOUT_SEC 뒤 C → EXPIRED 이벤트', exp && exp.payload.status === 'EXPIRED');
expect('B(이미 수락)는 다시 안 옴', evs(wG, 'waypoint.proposal_resolved').filter((e) => e.payload.carId === B.carId).length === 1);
expect('만료 뒤 스냅샷 proposals = []', (await call('GET', P, undefined, G.guestToken)).body.session.proposals.length === 0);
expect('만료 뒤 C 응답 → PROPOSAL_CLOSED', code(await call('POST', `${P}/proposals/${prp.proposalId}/respond`, { accept: true }, C.carToken)) === 'PROPOSAL_CLOSED');

// R-32
const d1 = await call('DELETE', `${P}/waypoints/${w1.body.waypoint.waypointId}`, undefined, G.guestToken);
expect('R-32 → 204', d1.status === 204);
await wait(150);
check('waypoint.removed 이벤트', evs(wA, 'waypoint.removed')[0], fx('waypoint.removed'));
expect('R-32 다른 차(B) 경유지 → FORBIDDEN', code(await call('DELETE', `${P}/waypoints/${p1.body.waypoint.waypointId}`, undefined, G.guestToken)) === 'FORBIDDEN');
expect('R-32 없는 경유지 → NOT_FOUND', code(await call('DELETE', `${P}/waypoints/wpt_none`, undefined, G.guestToken)) === 'NOT_FOUND');
expect('R-32 차량 → FORBIDDEN', code(await call('DELETE', `${P}/waypoints/${w2.body.waypoint.waypointId}`, undefined, A.carToken)) === 'FORBIDDEN');

// R-33 (A 기록: ADDED w1, ADDED w2, REMOVED w1)
const u1 = await call('POST', `${P}/waypoints/undo`, undefined, A.carToken);
check('R-33 응답', u1.body, rest['R-33'].response);
expect('되돌리기 1: 삭제였던 w1 복구(같은 ID)', u1.body.undone.action === 'REMOVED' && u1.body.undone.waypoint.waypointId === w1.body.waypoint.waypointId);
await wait(150);
expect('waypoint.added byUndo=true', evs(wA, 'waypoint.added').at(-1).payload.byUndo === true);
const u2 = await call('POST', `${P}/waypoints/undo`, undefined, A.carToken);
expect('되돌리기 2: 추가였던 w2 삭제', u2.body.undone.action === 'ADDED' && u2.body.undone.waypoint.waypointId === w2.body.waypoint.waypointId);
await wait(150);
expect('waypoint.removed byUndo=true', evs(wA, 'waypoint.removed').at(-1).payload.byUndo === true);
await call('POST', `${P}/waypoints/undo`, undefined, A.carToken);
expect('A 기록 소진 → 409 NOTHING_TO_UNDO', code(await call('POST', `${P}/waypoints/undo`, undefined, A.carToken)) === 'NOTHING_TO_UNDO');
const ub = await call('POST', `${P}/waypoints/undo`, undefined, B.carToken);
expect('B 되돌리기: 수락으로 생긴 경유지 삭제(차별 기록)', ub.body.undone.action === 'ADDED' && ub.body.undone.waypoint.carId === B.carId);
expect('R-33 웹 → FORBIDDEN', code(await call('POST', `${P}/waypoints/undo`, undefined, G.guestToken)) === 'FORBIDDEN');
expect('최종 waypoints = [] ', (await call('GET', P, undefined, G.guestToken)).body.session.waypoints.length === 0);

// 검증 오류
expect('scope 틀림 → VALIDATION_ERROR', code(await call('POST', `${P}/waypoints`, { place: cafe, scope: 'ALL' }, G.guestToken)) === 'VALIDATION_ERROR');
expect('place 없음 → VALIDATION_ERROR', code(await call('POST', `${P}/waypoints`, { scope: 'MY_CAR' }, G.guestToken)) === 'VALIDATION_ERROR');
expect('accept 없음 → VALIDATION_ERROR', code(await call('POST', `${P}/proposals/${prp.proposalId}/respond`, {}, B.carToken)) === 'VALIDATION_ERROR');
expect('seq 연속(웹 기준)', wG.events.every((e, i) => i === 0 || e.seq === wG.events[i - 1].seq + 1), wG.events.map((e) => e.seq).join());

for (const c of [wA, wB, wG]) c.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
