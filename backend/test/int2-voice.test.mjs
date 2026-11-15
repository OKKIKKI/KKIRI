// 통합 2회차: R-21 음성 클립 업로드, R-25 받기
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
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
async function upload(sid, token, { bytes, target = 'ALL', durationMs = '4200', type = 'audio/mp4', omitAudio = false }) {
  const fd = new FormData();
  if (!omitAudio) fd.append('audio', new Blob([bytes], { type }), 'clip.m4a');
  fd.append('target', target);
  fd.append('durationMs', durationMs);
  const r = await fetch(`${API}/v1/sessions/${sid}/voice-clips`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: fd });
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
const lastMsg = (c) => c.events.filter((e) => e.type === 'message.created').at(-1);
const voiceFx = wsFx.find((e) => e.type === 'message.created' && e.payload.message.kind === 'VOICE');

const A = (await call('POST', '/v1/cars/register', { deviceId: 'v-A', displayName: 'A차', driverName: '드라이버A' })).body;
const B = (await call('POST', '/v1/cars/register', { deviceId: 'v-B', displayName: 'B차', driverName: '현우' })).body;
const ses = (await call('POST', '/v1/sessions', { name: '무전', destination: null }, A.carToken)).body.session;
await call('POST', '/v1/sessions/join-by-code', { joinCode: ses.joinCode }, B.carToken);
const G = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
const sid = ses.sessionId;
const wA = await openWs(sid, A.carToken), wB = await openWs(sid, B.carToken), wG = await openWs(sid, G.guestToken);
await wait(150);

// 1. 업로드 → 전체
const bytes = randomBytes(50_000);
const u1 = await upload(sid, A.carToken, { bytes });
expect('R-21 → 200', u1.status === 200, JSON.stringify(u1.body));
check('R-21 응답 Message(VOICE)', u1.body.message, rest['R-23'].response.messages[0]);
const m = u1.body.message;
expect('kind=VOICE, text=null, urgent=false, sender=CAR 드라이버A', m.kind === 'VOICE' && m.text === null && m.urgent === false && m.sender.type === 'CAR' && m.sender.name === '드라이버A');
expect('clip.url = /v1/sessions/{id}/voice-clips/{messageId} 상대 경로', m.clip.url === `/v1/sessions/${sid}/voice-clips/${m.messageId}`, m.clip.url);
expect('clip.durationMs=4200, mime=audio/mp4', m.clip.durationMs === 4200 && m.clip.mime === 'audio/mp4');
await wait(150);
check('message.created(VOICE) 이벤트', lastMsg(wB), voiceFx);
expect('A DISPLAY(자기 차), B SPEAK, 웹 DISPLAY', [wA, wB, wG].map((c) => lastMsg(c).payload.delivery.mode).join() === 'DISPLAY,SPEAK,DISPLAY');

// 2. R-25
const g1 = await fetch(API + m.clip.url, { headers: { Authorization: `Bearer ${B.carToken}` } });
const got = Buffer.from(await g1.arrayBuffer());
expect('R-25 → 200, audio/mp4, 올린 바이트와 같음', g1.status === 200 && g1.headers.get('content-type') === 'audio/mp4' && got.equals(bytes));
const g2 = await call('GET', m.clip.url, undefined, G.guestToken);
expect('R-25 웹(guestToken) → FORBIDDEN', g2.body?.error?.code === 'FORBIDDEN');
const g3 = await call('GET', `/v1/sessions/${sid}/voice-clips/msg_none`, undefined, B.carToken);
expect('R-25 없는 클립 → 404 NOT_FOUND', g3.status === 404 && g3.body.error.code === 'NOT_FOUND');
const g4 = await call('GET', m.clip.url);
expect('R-25 토큰 없음 → UNAUTHORIZED', g4.body?.error?.code === 'UNAUTHORIZED');

// 3. 음소거 → HOLD, 보관함
await call('PUT', `/v1/sessions/${sid}/mutes`, { targetCarId: A.carId, muted: true }, B.carToken);
const u2 = await upload(sid, A.carToken, { bytes: randomBytes(1000), target: B.carId, durationMs: '1500' });
await wait(150);
expect('B가 A 음소거 → B HOLD', lastMsg(wB).payload.message.messageId === u2.body.message.messageId && lastMsg(wB).payload.delivery.mode === 'HOLD');
const held = (await call('GET', `/v1/sessions/${sid}/held-messages`, undefined, B.carToken)).body.messages;
expect('R-23에 VOICE 1건', held.length === 1 && held[0].kind === 'VOICE' && held[0].clip.url === u2.body.message.clip.url);

// 4. 오류
const code = (r) => r.body?.error?.code;
expect('1MB 초과 → VALIDATION_ERROR', code(await upload(sid, A.carToken, { bytes: randomBytes(1024 * 1024 + 1) })) === 'VALIDATION_ERROR');
expect('정확히 1MB → 성공', (await upload(sid, A.carToken, { bytes: randomBytes(1024 * 1024) })).status === 200);
expect('durationMs 15001 → VALIDATION_ERROR', code(await upload(sid, A.carToken, { bytes, durationMs: '15001' })) === 'VALIDATION_ERROR');
expect('durationMs 숫자 아님 → VALIDATION_ERROR', code(await upload(sid, A.carToken, { bytes, durationMs: 'abc' })) === 'VALIDATION_ERROR');
expect('audio 없음 → VALIDATION_ERROR', code(await upload(sid, A.carToken, { bytes, omitAudio: true })) === 'VALIDATION_ERROR');
expect('없는 target → VALIDATION_ERROR', code(await upload(sid, A.carToken, { bytes, target: 'car_none' })) === 'VALIDATION_ERROR');
expect('웹(guestToken) 업로드 → FORBIDDEN', code(await upload(sid, G.guestToken, { bytes })) === 'FORBIDDEN');
expect('JSON으로 보냄 → VALIDATION_ERROR', code(await call('POST', `/v1/sessions/${sid}/voice-clips`, { target: 'ALL' }, A.carToken)) === 'VALIDATION_ERROR');
expect('오류 뒤에도 서버 정상', (await fetch(API + '/health')).status === 200);

for (const c of [wA, wB, wG]) c.ws.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
