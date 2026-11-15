// 통합 3회차: R-15 친구 차량(목업, 고정 목록)
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

const loc = (await fetch(`${API}/v1/auth/kakao/start?redirect=${encodeURIComponent(`${WEB}/me`)}`, { redirect: 'manual' })).headers.get('location');
const userToken = decodeURIComponent(new URL(loc).hash.slice('#userToken='.length));

const f = await call('GET', '/v1/me/friend-cars', undefined, userToken);
check('R-15 응답', f.body, rest['R-15'].response);
expect('R-15 = 시드 목록(B차·현우)', JSON.stringify(f.body) === JSON.stringify({ friendCars: [{ carId: 'car_B', displayName: 'B차', ownerName: '현우' }] }), JSON.stringify(f.body));
expect('R-15 토큰 없음 → UNAUTHORIZED', code(await call('GET', '/v1/me/friend-cars')) === 'UNAUTHORIZED');
const A = (await call('POST', '/v1/cars/register', { deviceId: 'f-A', displayName: 'A차', driverName: '드라이버A' })).body;
expect('R-15 carToken → FORBIDDEN', code(await call('GET', '/v1/me/friend-cars', undefined, A.carToken)) === 'FORBIDDEN');
expect('R-12는 그대로 동작', (await call('GET', '/v1/me', undefined, userToken)).body.userId === 'usr_01');
const pre = await fetch(`${API}/v1/me/friend-cars`, { method: 'OPTIONS', headers: { Origin: WEB, 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'authorization' } });
expect('R-15 CORS preflight 허용', pre.status === 204 && pre.headers.get('access-control-allow-origin') === WEB);

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
