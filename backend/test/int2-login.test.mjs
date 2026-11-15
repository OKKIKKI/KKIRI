// 개발용 로그인(DEV_LOGIN=1): R-10 → #userToken, R-12, R-06(userToken 헤더)
import { readFileSync } from 'node:fs';
const repo = process.argv[2];
const API = 'http://localhost:8080';
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
const code = (r) => r.body?.error?.code;
const start = (redirect) => fetch(`${API}/v1/auth/kakao/start${redirect === undefined ? '' : `?redirect=${encodeURIComponent(redirect)}`}`, { redirect: 'manual' });

// R-10
const r1 = await start(`${WEB}/me`);
const loc = r1.headers.get('location');
expect('R-10 → 302', r1.status === 302, String(r1.status));
expect('Location = {redirect}#userToken=ut_...', loc && loc.startsWith(`${WEB}/me#userToken=ut_`), loc);
const userToken = decodeURIComponent(new URL(loc).hash.slice('#userToken='.length));
const r1b = await start(`${WEB}/me?x=1#old`);
expect('redirect에 쿼리·해시가 있어도 해시만 교체', r1b.headers.get('location').startsWith(`${WEB}/me?x=1#userToken=ut_`), r1b.headers.get('location'));
const t2 = decodeURIComponent(new URL(r1b.headers.get('location')).hash.slice('#userToken='.length));
expect('로그인마다 새 토큰', t2 !== userToken);

expect('redirect 없음 → VALIDATION_ERROR', (await start(undefined).then((r) => r.json())).error.code === 'VALIDATION_ERROR');
expect('다른 사이트 redirect → VALIDATION_ERROR', (await start('https://evil.example/steal').then((r) => r.json())).error.code === 'VALIDATION_ERROR');
expect('주소 형식 아님 → VALIDATION_ERROR', (await start('/me').then((r) => r.json())).error.code === 'VALIDATION_ERROR');

// R-12
const me = await call('GET', '/v1/me', undefined, userToken);
check('R-12 응답', me.body, rest['R-12'].response);
expect('R-12 = 시드 첫 사용자 usr_01 드라이버A', me.body.userId === 'usr_01' && me.body.name === '드라이버A');
expect('R-12 토큰 없음 → UNAUTHORIZED', code(await call('GET', '/v1/me')) === 'UNAUTHORIZED');
expect('R-12 잘못된 토큰 → UNAUTHORIZED', code(await call('GET', '/v1/me', undefined, 'ut_bad')) === 'UNAUTHORIZED');
const A = (await call('POST', '/v1/cars/register', { deviceId: 'l-A', displayName: 'A차', driverName: '드라이버A' })).body;
expect('R-12 carToken → FORBIDDEN', code(await call('GET', '/v1/me', undefined, A.carToken)) === 'FORBIDDEN');

// R-06 + userToken 헤더 (빠른 세션이라 주인 아님 → CREW, isMember=true)
const ses = (await call('POST', '/v1/sessions', { name: 's', destination: null }, A.carToken)).body.session;
const j = await fetch(`${API}/v1/sessions/join`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userToken}` }, body: JSON.stringify({ qrToken: ses.qr.url.split('/j/')[1], name: '드라이버A' }) }).then((r) => r.json());
// fixture R-06_member.session은 "(R-06과 같은 모양…)" 설명 문자열이라 R-06 응답 모양과 비교한다
check('R-06(회원) 응답 = R-06 모양', j, rest['R-06'].response);
const par = j.session.participants.find((p) => p.participantId === j.participantId);
expect('R-06 userToken → isMember=true, CREW(세션 주인 아님), guestToken 발급', par.isMember === true && par.role === 'CREW' && j.guestToken.startsWith('gt_'));
expect('세션 안 호출에 userToken → FORBIDDEN (명세 1.3)', code(await call('GET', `/v1/sessions/${ses.sessionId}`, undefined, userToken)) === 'FORBIDDEN');
expect('같은 사람은 guestToken으로 세션 조회 가능', (await call('GET', `/v1/sessions/${ses.sessionId}`, undefined, j.guestToken)).status === 200);
const g = (await call('POST', '/v1/sessions/join', { qrToken: ses.qr.url.split('/j/')[1], name: '민지' })).body;
expect('R-12 guestToken → FORBIDDEN', code(await call('GET', '/v1/me', undefined, g.guestToken)) === 'FORBIDDEN');

// CORS (웹이 R-12 호출)
const pre = await fetch(`${API}/v1/me`, { method: 'OPTIONS', headers: { Origin: WEB, 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'authorization' } });
expect('R-12 CORS preflight 허용', pre.status === 204 && pre.headers.get('access-control-allow-origin') === WEB && /Authorization/i.test(pre.headers.get('access-control-allow-headers')));

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
