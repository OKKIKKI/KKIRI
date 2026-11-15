// 계약·회귀 확인 실행기: `npm run test:contract` (backend 폴더에서)
// - 확인 묶음마다 서버를 새로 띄운다(메모리 저장이라 이전 묶음의 상태가 남지 않게). 타이머는 환경변수로 줄인다.
// - 실제 서버에 HTTP·WebSocket으로 요청하고, 응답·이벤트 모양을 docs/fixtures와 키 단위로 비교한다.
// - 새 라이브러리 없음(Node 내장 fetch·WebSocket·FormData, Node 22 이상). 실제 카카오·Spotify는 부르지 않는다.
// - 포트 8080을 쓴다(명세 1.2). 다른 서버가 8080을 쓰고 있으면 멈춘다.
// 일부만: `npm run test:contract -- int1 int2-msg`
import { spawn } from 'node:child_process';
import { appendFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const backendDir = fileURLToPath(new URL('..', import.meta.url));
const repoRoot = path.resolve(backendDir, '..');
const API = 'http://localhost:8080';

/** 모든 묶음에 공통으로 주는 값: 사용자의 .env(LAN 주소·카카오 키)와 상관없이 같은 조건으로 돈다 */
const BASE_ENV = {
  PORT: '8080',
  KKIRI_WEB_BASE: 'http://localhost:5173',
  KKIRI_API_BASE: 'http://localhost:8080',
  DEV_LOGIN: '1',
  // 기존 묶음은 명세 원래 동작(제안 흐름)을 검사한다. 모든 차 바로 추가는 int5-wpt-all에서 따로
  WAYPOINT_TO_ALL_CARS: '0',
  KAKAO_REST_API_KEY: '',
  KAKAO_CLIENT_SECRET: '',
  // 실제 Spotify를 부르지 않게 비운다(.env에 키가 있어도 목업으로)
  SPOTIFY_CLIENT_ID: '',
  SPOTIFY_CLIENT_SECRET: '',
  MUSIC_POLL_SEC: '5',
  PAUSE_TIMEOUT_SEC: '900',
  ACCOUNT_SESSION_TTL_SEC: '86400',
  RESERVE_ATTACH_TIMEOUT_SEC: '900',
  QR_TTL_SEC: '1800',
  URGENT_LIMIT_SEC: '30',
  PROPOSAL_TIMEOUT_SEC: '60',
  CAR_DISCONNECT_SEC: '30',
  MUSIC_CONTROL_LIMIT_SEC: '3',
};

/** 묶음 이름 → 서버에 줄 환경변수(타이머 단축). 파일은 test/<이름>.test.mjs */
const SUITES = [
  ['int1', {}],                                                        // 통합 1회차: R-01·02·05·06·07, WS
  ['int2-msg', {}],                                                    // R-20, R-22~24
  ['int2-voice', {}],                                                  // R-21, R-25
  ['int2-wpt', { PROPOSAL_TIMEOUT_SEC: '2' }],                         // R-30~34, 제안 만료
  ['int2-login', {}],                                                  // R-10(DEV), R-12, R-06 회원
  ['int2-drive', { PAUSE_TIMEOUT_SEC: '3', PROPOSAL_TIMEOUT_SEC: '2' }], // R-08, R-09, 세션 수명
  ['int3-reconnect', { CAR_DISCONNECT_SEC: '2', PAUSE_TIMEOUT_SEC: '4' }], // R-03, R-04, 차량 끊김
  ['int3-music', { MUSIC_CONTROL_LIMIT_SEC: '1', PAUSE_TIMEOUT_SEC: '5' }], // R-40, R-42~46
  ['int3-friends', {}],                                                // R-15
  ['int3-reserve', { RESERVE_ATTACH_TIMEOUT_SEC: '3' }],               // R-13, R-14, 예약 미연결
  ['int5-wpt-all', { WAYPOINT_TO_ALL_CARS: '1' }],                      // 시연 방침: 경유지 모든 차 바로 추가
  ['int4-look', {}],                                                   // R-47 car look, car.look_changed (v1.15)
  ['place-distance', {}],                                              // Place.distanceKm 숫자
  ['device-log', {}],                                                  // R-01 등록 로그·같은 deviceId 다른 차 경고
  ['car-jsonmapper', { PAUSE_TIMEOUT_SEC: '30', PROPOSAL_TIMEOUT_SEC: '30', CAR_DISCONNECT_SEC: '1', MUSIC_CONTROL_LIMIT_SEC: '1' }], // 차량 앱 JsonMapper 규칙
];

const wanted = process.argv.slice(2);
const suites = wanted.length ? SUITES.filter(([n]) => wanted.includes(n)) : SUITES;
/** 서버 없이 프로세스 안에서 도는 확인(가짜 외부 서버). [이름, 설명] — 파일은 test/<이름>.test.ts */
const TS_SUITES = [
  ['kakao-provider', '가짜 카카오 서버'],
  ['music-connector', '음악 연결 흐름·가짜 Spotify 서버'],
  ['spotify-provider', '실제 Spotify 구현을 가짜 Spotify 서버로'],
].filter(([n]) => !wanted.length || wanted.includes(n));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function up() {
  try { const r = await fetch(`${API}/health`); if (r.ok) return true; } catch { /* 안 떠 있음 */ }
  return false;
}

function run(cmd, args, env, opts = {}) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { cwd: backendDir, env: { ...process.env, ...BASE_ENV, ...env }, stdio: opts.capture ? ['ignore', 'pipe', 'pipe'] : 'inherit' });
    let out = '';
    const keep = (d) => { out += d; if (opts.logFile) appendFileSync(opts.logFile, d); };
    if (opts.capture) { p.stdout.on('data', keep); p.stderr.on('data', keep); }
    p.on('exit', (code) => resolve({ code, out }));
    if (opts.onSpawn) opts.onSpawn(p);
  });
}

if (await up()) {
  console.error('포트 8080에 이미 서버가 떠 있어요. 끄고 다시 실행하세요(확인용 서버를 새로 띄워야 함).');
  process.exit(2);
}

const results = [];
for (const [name, env] of suites) {
  let server;
  // 서버 로그를 파일로도 남겨 확인 스크립트가 읽을 수 있게 한다(3번째 인자). 토큰·본문은 원래 로그에 없다
  const logFile = path.join(tmpdir(), `kkiri-test-${name}-${process.pid}.log`);
  writeFileSync(logFile, '');
  const serverDone = run(process.execPath, ['--import', 'tsx', 'src/index.ts'], env, { capture: true, logFile, onSpawn: (p) => (server = p) });
  let ok = false;
  for (let i = 0; i < 60; i++) { if (await up()) { ok = true; break; } await sleep(250); }
  if (!ok) { server?.kill(); const { out } = await serverDone; console.error(`[${name}] 서버가 뜨지 않음\n${out}`); results.push([name, 1]); continue; }
  console.log(`\n── ${name} ──`);
  const { code } = await run(process.execPath, [path.join('test', `${name}.test.mjs`), repoRoot, logFile], {});
  server.kill();
  await serverDone;
  rmSync(logFile, { force: true });
  for (let i = 0; i < 40 && (await up()); i++) await sleep(100);
  results.push([name, code]);
}

for (const [name, desc] of TS_SUITES) {
  console.log(`\n── ${name} (${desc}) ──`);
  const { code } = await run(process.execPath, ['--import', 'tsx', path.join('test', `${name}.test.ts`)], {});
  results.push([name, code]);
}

console.log('\n══ 결과 ══');
for (const [n, c] of results) console.log(`${c === 0 ? '✓' : '✗'} ${n}`);
const failed = results.filter(([, c]) => c !== 0);
console.log(failed.length ? `\n실패 ${failed.length}묶음` : `\n전부 통과 (${results.length}묶음)`);
process.exit(failed.length ? 1 : 0);
