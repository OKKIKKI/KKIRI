// CAR-A net/JsonMapper.kt 와 같은 규칙(org.json getString/getLong/getDouble/getBoolean, isNull, enumValueOf)으로
// 실제 서버 JSON을 읽어 본다. 서버: RESERVE_ATTACH_TIMEOUT_SEC=60 PAUSE_TIMEOUT_SEC=3 PROPOSAL_TIMEOUT_SEC=2 CAR_DISCONNECT_SEC=1 MUSIC_CONTROL_LIMIT_SEC=1
const API = 'http://localhost:8080', WS = 'ws://localhost:8080', WEB = 'http://localhost:5173';
const E = {
  SessionState: ['RESERVED', 'ACTIVE', 'PAUSED', 'ENDED'], CarRole: ['HOST', 'COHOST'], CarStatus: ['CONNECTED', 'PAUSED', 'DISCONNECTED'],
  DriveState: ['DRIVING', 'PARKED', 'UNKNOWN'], ParticipantRole: ['LEADER', 'CREW'], MessageKind: ['TEXT', 'BROADCAST', 'VOICE'],
  DeliveryMode: ['SPEAK', 'HOLD', 'DISPLAY'], ProposalStatus: ['PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED'],
  EndReason: ['HOST_ENDED', 'PAUSE_TIMEOUT', 'ACCOUNT_TTL', 'RESERVE_TIMEOUT'], SenderType: ['PARTICIPANT', 'CAR'], ActorType: ['CAR', 'PARTICIPANT'],
};
// ── org.json 흉내 ──
class J extends Error {}
const isNull = (o, k) => !(k in o) || o[k] === null;
const getString = (o, k) => { if (!(k in o)) throw new J(`${k} 없음`); if (o[k] === null) throw new J(`${k} null(getString은 "null" 문자열이 됨)`); if (typeof o[k] === 'object') throw new J(`${k} 객체`); return String(o[k]); };
const getNum = (o, k) => { if (typeof o[k] !== 'number') throw new J(`${k} 숫자 아님(${JSON.stringify(o[k])})`); return o[k]; };
const getLong = (o, k) => { const n = getNum(o, k); if (!Number.isInteger(n)) throw new J(`${k} 정수 아님`); return n; };
const getBool = (o, k) => { if (typeof o[k] !== 'boolean') throw new J(`${k} boolean 아님`); return o[k]; };
const getObj = (o, k) => { if (!o[k] || typeof o[k] !== 'object' || Array.isArray(o[k])) throw new J(`${k} 객체 아님`); return o[k]; };
const getArr = (o, k) => { if (!Array.isArray(o[k])) throw new J(`${k} 배열 아님`); return o[k]; };
const stringOrNull = (o, k) => (isNull(o, k) ? null : getString(o, k));
const objOrNull = (o, k) => (isNull(o, k) ? null : getObj(o, k));
const enumOf = (o, k, T) => { const v = getString(o, k); if (!E[T].includes(v)) throw new J(`${k}="${v}"는 ${T}에 없음`); return v; };
// ── JsonMapper.kt 그대로 ──
const place = (o) => (getString(o, 'placeId'), getString(o, 'name'), getString(o, 'address'), getNum(o, 'lat'), getNum(o, 'lng'), getNum(o, 'distanceKm'));
const car = (o) => (getString(o, 'carId'), getString(o, 'displayName'), getString(o, 'driverName'), enumOf(o, 'role', 'CarRole'), enumOf(o, 'status', 'CarStatus'), enumOf(o, 'driveState', 'DriveState'));
const participant = (o) => (getString(o, 'participantId'), getString(o, 'name'), enumOf(o, 'role', 'ParticipantRole'), getString(o, 'carId'), getBool(o, 'isMember'), getBool(o, 'online'));
const pref = (o) => (getString(o, 'participantId'), getString(o, 'name'));
const waypoint = (o) => (getString(o, 'waypointId'), getString(o, 'carId'), place(getObj(o, 'place')), pref(getObj(o, 'addedBy')), getString(o, 'createdAt'));
const proposal = (o) => (getString(o, 'proposalId'), place(getObj(o, 'place')), getString(o, 'fromCarId'), pref(getObj(o, 'proposedBy')), getArr(o, 'responses').forEach((r) => (getString(r, 'carId'), enumOf(r, 'status', 'ProposalStatus'))), getString(o, 'expiresAt'));
const message = (o) => { getString(o, 'messageId'); enumOf(o, 'kind', 'MessageKind'); const s = getObj(o, 'sender'); enumOf(s, 'type', 'SenderType'); getString(s, 'id'); getString(s, 'name'); getString(s, 'carId'); getString(o, 'target'); stringOrNull(o, 'text'); const c = objOrNull(o, 'clip'); if (c) { getString(c, 'url'); getLong(c, 'durationMs'); getString(c, 'mime'); } getBool(o, 'urgent'); getString(o, 'createdAt'); };
const mute = (o) => (getString(o, 'byCarId'), getString(o, 'targetCarId'));
const music = (o) => { getBool(o, 'connected'); getBool(o, 'locked'); const n = objOrNull(o, 'nowPlaying'); if (n) { getString(n, 'title'); getString(n, 'artist'); stringOrNull(n, 'albumImageUrl'); getBool(n, 'isPlaying'); } };
const session = (o) => { getString(o, 'sessionId'); getString(o, 'name'); enumOf(o, 'state', 'SessionState'); getBool(o, 'isAccountSession'); getString(o, 'hostCarId'); const d = objOrNull(o, 'destination'); if (d) place(d); getString(o, 'joinCode'); const q = getObj(o, 'qr'); getString(q, 'url'); getString(q, 'expiresAt'); getArr(o, 'cars').forEach(car); getArr(o, 'participants').forEach(participant); getArr(o, 'waypoints').forEach(waypoint); getArr(o, 'proposals').forEach(proposal); getArr(o, 'mutes').forEach(mute); music(getObj(o, 'music')); getString(o, 'createdAt'); stringOrNull(o, 'pausedAt'); stringOrNull(o, 'expiresAt'); };
const you = (o) => (enumOf(o, 'actorType', 'ActorType'), stringOrNull(o, 'carId'), stringOrNull(o, 'participantId'), stringOrNull(o, 'role'));
const summary = (o) => (getArr(o, 'places').forEach((s) => { if (typeof s !== 'string') throw new J('places 문자열 아님'); }), getArr(o, 'cars').forEach((s) => { if (typeof s !== 'string') throw new J('cars 문자열 아님'); }), getLong(o, 'trackCount'));
function serverEvent(o) {
  const type = getString(o, 'type'); getString(o, 'sessionId'); getLong(o, 'seq'); getString(o, 'ts'); const p = o.payload ?? {};
  switch (type) {
    case 'session.snapshot': session(getObj(p, 'session')); you(getObj(p, 'you')); break;
    case 'session.state_changed': enumOf(p, 'state', 'SessionState'); stringOrNull(p, 'pausedAt'); stringOrNull(p, 'expiresAt'); break;
    case 'session.ended': enumOf(p, 'reason', 'EndReason'); summary(getObj(p, 'summary')); break;
    case 'participant.joined': case 'participant.left': participant(getObj(p, 'participant')); break;
    case 'car.joined': car(getObj(p, 'car')); break;
    case 'car.status_changed': getString(p, 'carId'); enumOf(p, 'status', 'CarStatus'); enumOf(p, 'driveState', 'DriveState'); break;
    case 'message.created': message(getObj(p, 'message')); enumOf(getObj(p, 'delivery'), 'mode', 'DeliveryMode'); break;
    case 'mute.changed': getArr(p, 'mutes').forEach(mute); break;
    case 'waypoint.added': waypoint(getObj(p, 'waypoint')); break;
    case 'waypoint.removed': getString(p, 'waypointId'); getString(p, 'carId'); break;
    case 'waypoint.proposed': proposal(getObj(p, 'proposal')); break;
    case 'waypoint.proposal_resolved': getString(p, 'proposalId'); getString(p, 'carId'); enumOf(p, 'status', 'ProposalStatus'); break;
    case 'music.now_playing': music(getObj(p, 'music')); break;
    case 'music.queue_added': getString(p, 'title'); getString(p, 'artist'); getString(getObj(p, 'addedBy'), 'name'); break;
    case 'music.lock_changed': getBool(p, 'locked'); break;
    default: return 'Unknown:' + type;
  }
  return type;
}
// ── 시나리오 ──
async function call(m, path, body, t) { const r = await fetch(API + path, { method: m, headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) }); const x = await r.text(); return { status: r.status, text: x, body: x ? JSON.parse(x) : null }; }
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
function openWs(sid, t) { const ws = new WebSocket(`${WS}/v1/ws?sessionId=${sid}&token=${t}`); const ev = []; ws.onmessage = (e) => ev.push(JSON.parse(e.data)); return new Promise((res, rej) => { ws.onopen = () => res({ ws, ev }); ws.onerror = rej; }); }
let fails = 0; const seenTypes = new Set();
const ok = (n, f) => { try { const r = f(); console.log(`✓ ${n}${r && typeof r === 'string' ? ' ' + r : ''}`); } catch (e) { fails++; console.log(`✗ ${n}: ${e.message}`); } };

const A = (await call('POST', '/v1/cars/register', { deviceId: 'dev-0001', displayName: 'A차', driverName: '드라이버A' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'dev-0002', displayName: 'B차', driverName: '현우' })).body;
const loc = (await fetch(`${API}/v1/auth/kakao/start?redirect=${encodeURIComponent(WEB + '/me')}`, { redirect: 'manual' })).headers.get('location');
const U = decodeURIComponent(new URL(loc).hash.slice(11));
const pl = { placeId: 'plc_05', name: '경포대', address: '강원 강릉시 경포로 365', lat: 37.7955, lng: 128.8966, distanceKm: 6.8 };
// 예약 세션 → R-03(RESERVED) → R-04
const resv = await call('POST', '/v1/sessions/reserve', { carId: A.carId, name: '강릉', destination: pl }, U);
const sid = resv.body.session.sessionId, P = `/v1/sessions/${sid}`;
const Lg = await fetch(`${API}/v1/sessions/join`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${U}` }, body: JSON.stringify({ qrToken: resv.body.session.qr.url.split('/j/')[1], name: '드라이버A' }) }).then((r) => r.json());
const G = (await call('POST', '/v1/sessions/join', { qrToken: resv.body.session.qr.url.split('/j/')[1], name: '민지' })).body;
await call('POST', `${P}/waypoints`, { place: pl, scope: 'MY_CAR' }, G.guestToken);
const r03 = await call('GET', '/v1/cars/me/pending-session', undefined, A.carToken);
ok('R-03 응답(RESERVED 세션) → sessionOrNullResponse', () => { const o = r03.body; if (!isNull(o, 'session')) session(getObj(o, 'session')); });
const wA = await openWs(sid, A.carToken);
const r04 = await call('POST', `${P}/attach`, undefined, A.carToken);
ok('R-04 응답 → sessionResponse', () => session(getObj(r04.body, 'session')));
const r05 = await call('POST', '/v1/sessions/join-by-code', { joinCode: r04.body.session.joinCode, applyDestination: true }, B.carToken);
ok('R-05 응답 → sessionResponse', () => session(getObj(r05.body, 'session')));
const r07 = await call('GET', P, undefined, A.carToken);
ok('R-07 응답 → sessionResponse', () => session(getObj(r07.body, 'session')));
const wB = await openWs(sid, B.carToken);
// 이벤트 16종 만들기
await call('POST', '/v1/sessions/join', { qrToken: resv.body.session.qr.url.split('/j/')[1], name: '지호' });   // participant.joined
await call('POST', `${P}/messages`, { kind: 'BROADCAST', text: '다음 휴게소', urgent: false, target: 'ALL' }, A.carToken); // BROADCAST
await call('POST', `${P}/car-state`, { driveState: 'DRIVING', ignition: 'ON' }, A.carToken);           // car.status_changed
await call('POST', `${P}/messages`, { kind: 'TEXT', text: '기름 넣고 갈게', urgent: true, target: 'ALL' }, G.guestToken); // message.created
await call('PUT', `${P}/mutes`, { targetCarId: A.carId, muted: true }, B.carToken);                      // mute.changed
const fd = new FormData(); fd.append('audio', new Blob([new Uint8Array(2000)], { type: 'audio/mp4' }), 'c.m4a'); fd.append('target', B.carId); fd.append('durationMs', '2100');
await fetch(`${API}${P}/voice-clips`, { method: 'POST', headers: { Authorization: `Bearer ${A.carToken}` }, body: fd }); // message.created VOICE (B HOLD)
const pp = (await call('POST', `${P}/waypoints`, { place: { ...pl, placeId: 'plc_02', name: '강릉휴게소' }, scope: 'PROPOSE_OTHERS' }, G.guestToken)).body; // waypoint.added + proposed
await call('POST', `${P}/proposals/${pp.proposal.proposalId}/respond`, { accept: true }, B.carToken);    // proposal_resolved + added
await call('POST', `${P}/waypoints/undo`, undefined, A.carToken);                                        // waypoint.removed
await fetch(`${API}${P}/music/spotify/start?redirect=${encodeURIComponent(WEB + '/x')}&token=${encodeURIComponent(Lg.guestToken)}`, { redirect: 'manual' }); // music.now_playing
await call('POST', `${P}/music/queue`, { uri: 'spotify:track:mock_02', title: 'Lose Yourself', artist: 'Eminem' }, G.guestToken); // music.queue_added
await call('PUT', `${P}/music/lock`, { locked: true }, A.carToken);                                      // music.lock_changed
await call('POST', `${P}/car-state`, { driveState: 'PARKED', ignition: 'OFF' }, A.carToken);             // session.state_changed
await wait(300);
const r03b = await call('GET', '/v1/cars/me/pending-session', undefined, A.carToken);
ok('R-03 응답(PAUSED 세션)', () => session(getObj(r03b.body, 'session')));
await call('POST', `${P}/attach`, undefined, A.carToken);
wB.ws.close(); await wait(1500);                                                                          // car.status_changed DISCONNECTED (B)
await call('POST', `${P}/end`, undefined, Lg.guestToken);                                                 // session.ended
await wait(300);
ok('R-03 응답(세션 없음 → null)', () => { const o = (globalThis.__x = null); });
const r03c = await call('GET', '/v1/cars/me/pending-session', undefined, A.carToken);
ok('R-03 {session:null} → sessionOrNullResponse', () => { if (!isNull(r03c.body, 'session')) throw new J('null이어야 함'); });
// R-02 빠른 세션
const r02 = await call('POST', '/v1/sessions', { name: '빠른', destination: null }, A.carToken);
ok('R-02 응답 → sessionResponse', () => session(getObj(r02.body, 'session')));

const all = [...wA.ev, ...wB.ev];
for (const e of all) { try { seenTypes.add(serverEvent(e)); } catch (err) { fails++; console.log(`✗ 이벤트 ${e.type} seq=${e.seq}: ${err.message}`); } }
const expected = ['session.snapshot', 'session.state_changed', 'session.ended', 'participant.joined', 'car.joined', 'car.status_changed', 'message.created', 'mute.changed', 'waypoint.added', 'waypoint.removed', 'waypoint.proposed', 'waypoint.proposal_resolved', 'music.now_playing', 'music.queue_added', 'music.lock_changed'];
const missing = expected.filter((t) => !seenTypes.has(t));
console.log(`이벤트 ${all.length}건을 JsonMapper 규칙으로 읽음. 종류: ${[...seenTypes].length}가지${missing.length ? ' / 시나리오에서 안 나온 종류: ' + missing.join(', ') : ''}`);
const kinds = all.filter((e) => e.type === 'message.created').map((e) => `${e.payload.message.kind}/${e.payload.delivery.mode}`);
console.log('message.created 조합:', [...new Set(kinds)].join(' '));
console.log('participant.left: 1차에 보내지 않음(명세 v1.1) — JsonMapper에는 처리 코드 있음');
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
