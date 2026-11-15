// Place.distanceKm은 숫자만 받는다(명세 v1.3 6절: Place에 nullable 필드 없음) — R-02·R-31
const API = 'http://localhost:8080';
let fails = 0;
const expect = (n, c, info = '') => { if (!c) { fails++; console.log(`✗ ${n} ${info}`); } else console.log(`✓ ${n}`); };
async function call(method, path, body, token) {
  const r = await fetch(API + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}
const code = (r) => r.body?.error?.code;
const base = { placeId: 'plc_05', name: '경포대', address: '강원 강릉시 경포로 365', lat: 37.7955, lng: 128.8966 };

const A = (await call('POST', '/v1/cars/register', { deviceId: 'pd-A', displayName: 'A차', driverName: '드라이버A' })).body;
expect('R-02 destination.distanceKm 없음 → VALIDATION_ERROR', code(await call('POST', '/v1/sessions', { name: 's', destination: base }, A.carToken)) === 'VALIDATION_ERROR');
expect('R-02 destination.distanceKm null → VALIDATION_ERROR', code(await call('POST', '/v1/sessions', { name: 's', destination: { ...base, distanceKm: null } }, A.carToken)) === 'VALIDATION_ERROR');
expect('R-02 destination.distanceKm 문자열 → VALIDATION_ERROR', code(await call('POST', '/v1/sessions', { name: 's', destination: { ...base, distanceKm: '6.8' } }, A.carToken)) === 'VALIDATION_ERROR');
const ok = await call('POST', '/v1/sessions', { name: 's', destination: { ...base, distanceKm: 6.8 } }, A.carToken);
expect('R-02 distanceKm 숫자 → 200, 그대로 저장', ok.status === 200 && ok.body.session.destination.distanceKm === 6.8);
expect('R-02 destination null(목적지 없음)은 그대로 허용', (await call('POST', '/v1/sessions', { name: 's', destination: null }, A.carToken)).status === 200);

const ses = ok.body.session;
const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
const P = `/v1/sessions/${ses.sessionId}`;
expect('R-31 place.distanceKm 없음 → VALIDATION_ERROR', code(await call('POST', `${P}/waypoints`, { place: base, scope: 'MY_CAR' }, G.guestToken)) === 'VALIDATION_ERROR');
const seed = (await call('GET', `${P}/places`, undefined, G.guestToken)).body.places[0];
expect('R-30 결과를 그대로 R-31에 넣으면 성공', (await call('POST', `${P}/waypoints`, { place: seed, scope: 'MY_CAR' }, G.guestToken)).status === 200);
expect('스냅샷의 모든 Place에 distanceKm 숫자', (await call('GET', P, undefined, G.guestToken)).body.session.waypoints.every((w) => typeof w.place.distanceKm === 'number'));

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
