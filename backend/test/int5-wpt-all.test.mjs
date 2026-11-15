// 시연 방침(2026-11-15): WAYPOINT_TO_ALL_CARS=1이면 R-31 경유지가 수락 없이 세션의 모든 차에 바로 추가된다
const API = 'http://localhost:8080';
const WS = 'ws://localhost:8080';
let fails = 0;
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

const A = (await call('POST', '/v1/cars/register', { deviceId: 'all-A', displayName: 'A차', driverName: '드라이버A' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'all-B', displayName: 'B차', driverName: '현우' })).body;
const C = (await call('POST', '/v1/cars/register', { deviceId: 'all-C', displayName: 'C차', driverName: '수진' })).body;
const ses = (await call('POST', '/v1/sessions', { name: '강릉', destination: null }, A.carToken)).body.session;
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, B.carToken);
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, C.carToken);
const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
const sid = ses.sessionId, P = `/v1/sessions/${sid}`;
const wB = await openWs(sid, B.carToken);
await wait(150);
const place = (await call('GET', `${P}/places?query=`, undefined, G.guestToken)).body.places[0];

for (const scope of ['MY_CAR', 'PROPOSE_OTHERS']) {
  const before = (await call('GET', P, undefined, A.carToken)).body.session.waypoints.length;
  const r = await call('POST', `${P}/waypoints`, { place, scope }, G.guestToken);
  expect(`${scope}: 200, proposal=null`, r.status === 200 && r.body.proposal === null, JSON.stringify(r.body));
  expect(`${scope}: 응답 waypoint는 동승자 차(A)`, r.body.waypoint?.carId === A.carId);
  const s = (await call('GET', P, undefined, A.carToken)).body.session;
  const added = s.waypoints.slice(before);
  const cars = new Set(added.map((w) => w.carId));
  expect(`${scope}: A·B·C 모두에 1건씩 추가`, added.length === 3 && cars.has(A.carId) && cars.has(B.carId) && cars.has(C.carId), JSON.stringify(added.map((w) => w.carId)));
  expect(`${scope}: 제안 없음`, s.proposals.length === 0);
}
await wait(150);
const bAdded = wB.events.filter((e) => e.type === 'waypoint.added' && e.payload.waypoint.carId === B.carId);
expect('B차 WS로 자기 차 waypoint.added 2건', bAdded.length === 2, String(bAdded.length));
expect('waypoint.proposed 없음', !wB.events.some((e) => e.type === 'waypoint.proposed'));
wB.ws.close();
console.log(fails ? `실패 ${fails}` : '전부 통과');
process.exit(fails ? 1 : 0);
