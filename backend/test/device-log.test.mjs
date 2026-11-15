// R-01 등록 로그와 "같은 deviceId로 다른 차" 경고(통합 테스트 대비). 서버 응답은 바뀌지 않는다.
// 인자: [저장소 루트, 서버 로그 파일] — test/run.mjs가 넘겨 준다
import { readFileSync } from 'node:fs';
const logFile = process.argv[3];
const API = 'http://localhost:8080';
let fails = 0;
const expect = (n, c, info = '') => { if (!c) { fails++; console.log(`✗ ${n} ${info}`); } else console.log(`✓ ${n}`); };
const reg = (body) => fetch(`${API}/v1/cars/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then((r) => r.json());
const log = () => readFileSync(logFile, 'utf8');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const a1 = await reg({ deviceId: 'dup-1', displayName: 'A차', driverName: '드라이버A' });
await wait(150);
expect('새 차 등록 로그: deviceId → carId', log().includes(`[car] R-01 새 차 deviceId=dup-1 → ${a1.carId}`));
const a2 = await reg({ deviceId: 'dup-1', displayName: 'A차', driverName: '드라이버A' });
await wait(150);
expect('같은 차 재등록: 같은 carId·carToken, "같은 차" 로그', a2.carId === a1.carId && a2.carToken === a1.carToken && log().includes(`[car] R-01 다시 등록 deviceId=dup-1 → ${a1.carId} (같은 차)`));
expect('정상 재등록에는 경고 없음', !log().includes('⚠ 같은 deviceId(dup-1)'));
const b = await reg({ deviceId: 'dup-1', displayName: 'B차', driverName: '현우' });
await wait(150);
expect('다른 차 이름으로 같은 deviceId → 응답은 그대로(같은 carId, 명세 R-01)', b.carId === a1.carId);
expect('경고 로그: 같은 deviceId로 다른 차, 이름 둘 다 표시', log().includes('[car] ⚠ 같은 deviceId(dup-1)로 다른 차가 등록됨') && log().includes('"A차/드라이버A" → "B차/현우"'));
expect('로그에 carToken 없음', !log().includes(a1.carToken));

console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
