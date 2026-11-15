// KakaoLoginProvider를 가짜 카카오 서버(공식 문서의 요청·응답 형식)로 시험한다. 실제 카카오는 부르지 않는다. test/run.mjs가 실행
import { createServer } from 'node:http';
import { KakaoLoginProvider } from '../src/adapters/login.ts';
import { principalOf } from '../src/auth.ts';
import { getUser } from '../src/store/users.ts';

let fails = 0;
const expect = (n: string, c: boolean, info = '') => { if (!c) { fails++; console.log(`✗ ${n} ${info}`); } else console.log(`✓ ${n}`); };

// ── 가짜 카카오 ──
const seen: { token: URLSearchParams[]; me: Record<string, string>[] } = { token: [], me: [] };
let tokenMode: 'ok' | 'fail' = 'ok';
let meBody: unknown = { id: 1234567890, kakao_account: { profile: { nickname: '드라이버A' } } };
const fake = createServer(async (req, res) => {
  const chunks: Buffer[] = []; for await (const c of req) chunks.push(c as Buffer);
  const url = new URL(req.url ?? '/', 'http://x');
  if (req.method === 'POST' && url.pathname === '/oauth/token') {
    seen.token.push(new URLSearchParams(Buffer.concat(chunks).toString()));
    (seen as any).tokenCT = req.headers['content-type'];
    if (tokenMode === 'fail') { res.writeHead(400, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error: 'invalid_grant', error_code: 'KOE320' })); return; }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ token_type: 'bearer', access_token: 'AT_FAKE', expires_in: 43199, refresh_token: 'RT', refresh_token_expires_in: 5184000 })); return;
  }
  if (req.method === 'GET' && url.pathname === '/v2/user/me') {
    seen.me.push(req.headers as Record<string, string>);
    if (req.headers.authorization !== 'Bearer AT_FAKE') { res.writeHead(401); res.end('{}'); return; }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(meBody)); return;
  }
  res.writeHead(404); res.end();
});
await new Promise<void>((r) => fake.listen(9099, r));
const H = 'http://127.0.0.1:9099';
const mk = (secret: string | null, ttl?: number) => new KakaoLoginProvider({ restApiKey: 'REST_KEY_TEST', clientSecret: secret, apiBase: 'http://localhost:8080', authHost: H, apiHost: H, stateTtlMs: ttl });
const WEB = 'http://localhost:5173/me';
const tokenOf = (loc: string) => decodeURIComponent(new URL(loc).hash.slice('#userToken='.length));

// 1. R-10 인가 주소
const p = mk('SECRET_TEST');
const a = new URL(await p.start(new URL(WEB)));
expect('인가 주소 = {authHost}/oauth/authorize', a.origin + a.pathname === `${H}/oauth/authorize`);
expect('client_id·redirect_uri·response_type=code·state', a.searchParams.get('client_id') === 'REST_KEY_TEST' && a.searchParams.get('redirect_uri') === 'http://localhost:8080/v1/auth/kakao/callback' && a.searchParams.get('response_type') === 'code' && (a.searchParams.get('state') ?? '').length >= 16);
const st = a.searchParams.get('state')!;

// 2. R-11 성공 → 새 계정
const back1 = await p.callback!({} as any, new URLSearchParams({ code: 'CODE1', state: st }));
const t = seen.token.at(-1)!;
expect('토큰 요청: grant_type·client_id·redirect_uri(인가와 같음)·code·client_secret', t.get('grant_type') === 'authorization_code' && t.get('client_id') === 'REST_KEY_TEST' && t.get('redirect_uri') === 'http://localhost:8080/v1/auth/kakao/callback' && t.get('code') === 'CODE1' && t.get('client_secret') === 'SECRET_TEST', t.toString());
expect('토큰 요청 Content-Type', (seen as any).tokenCT === 'application/x-www-form-urlencoded;charset=utf-8');
expect('사용자 정보: Authorization: Bearer access_token', seen.me.at(-1)!.authorization === 'Bearer AT_FAKE');
expect('성공 → {redirect}#userToken=ut_...', back1.startsWith(`${WEB}#userToken=ut_`), back1);
const u1 = principalOf(tokenOf(back1)) as { kind: string; userId: string };
const nu = getUser(u1.userId)!;
expect('시드에 없는 카카오 ID → 차 없는 새 usr_ 계정, 이름=닉네임', u1.kind === 'USER' && nu.userId !== 'usr_01' && nu.name === '드라이버A' && nu.deviceIds.length === 0 && nu.kakaoId === '1234567890');

// 3. 같은 카카오 ID 다시 → 같은 계정
const s2 = new URL(await p.start(new URL(WEB))).searchParams.get('state')!;
const back2 = await p.callback!({} as any, new URLSearchParams({ code: 'CODE2', state: s2 }));
expect('같은 카카오 ID 재로그인 → 같은 usr_', (principalOf(tokenOf(back2)) as any).userId === u1.userId);

// 4. 시드 kakaoId 연결 (D-23)
getUser('usr_01')!.kakaoId = '999';
meBody = { id: 999, kakao_account: { profile: { nickname: '다른닉' } } };
const s3 = new URL(await p.start(new URL(WEB))).searchParams.get('state')!;
const back3 = await p.callback!({} as any, new URLSearchParams({ code: 'CODE3', state: s3 }));
expect('시드 kakaoId와 같으면 그 사용자(usr_01)로 로그인, 시드 이름 유지', (principalOf(tokenOf(back3)) as any).userId === 'usr_01' && getUser('usr_01')!.name === '드라이버A');

// 5. state 재사용·모름·만료
let err = '';
try { await p.callback!({} as any, new URLSearchParams({ code: 'X', state: s3 })); } catch (e: any) { err = e.code; }
expect('같은 state 재사용 → VALIDATION_ERROR', err === 'VALIDATION_ERROR');
err = ''; try { await p.callback!({} as any, new URLSearchParams({ code: 'X', state: 'unknown' })); } catch (e: any) { err = e.code; }
expect('모르는 state → VALIDATION_ERROR', err === 'VALIDATION_ERROR');
const pShort = mk(null, 50);
const s4 = new URL(await pShort.start(new URL(WEB))).searchParams.get('state')!;
await new Promise((r) => setTimeout(r, 80));
err = ''; try { await pShort.callback!({} as any, new URLSearchParams({ code: 'X', state: s4 })); } catch (e: any) { err = e.code; }
expect('만료된 state → VALIDATION_ERROR', err === 'VALIDATION_ERROR');

// 6. 사용자가 취소(error=access_denied) → 토큰 없이 웹으로
const s5 = new URL(await p.start(new URL(WEB))).searchParams.get('state')!;
const back5 = await p.callback!({} as any, new URLSearchParams({ error: 'access_denied', error_description: 'User denied access', state: s5 }));
expect('취소 → 토큰 없이 redirect로', back5 === WEB, back5);

// 7. 토큰 요청 실패(KOE320) → 토큰 없이
tokenMode = 'fail';
const s6 = new URL(await p.start(new URL(WEB))).searchParams.get('state')!;
const back6 = await p.callback!({} as any, new URLSearchParams({ code: 'USED', state: s6 }));
expect('토큰 실패(KOE320) → 토큰 없이 redirect로', back6 === WEB, back6);
tokenMode = 'ok';

// 8. client_secret 없음 → 본문에 안 넣음 / 닉네임 동의 안 함 → 기본 이름
const pNo = mk(null);
meBody = { id: 555, kakao_account: { profile_nickname_needs_agreement: true } };
const s7 = new URL(await pNo.start(new URL(WEB))).searchParams.get('state')!;
const back7 = await pNo.callback!({} as any, new URLSearchParams({ code: 'C7', state: s7 }));
expect('client_secret 없으면 토큰 요청에 안 넣음', !seen.token.at(-1)!.has('client_secret'));
expect('닉네임 없으면 이름 "카카오 사용자"', getUser((principalOf(tokenOf(back7)) as any).userId)!.name === '카카오 사용자');

fake.close();
console.log(fails ? `\n실패 ${fails}건` : '\n전부 통과');
process.exit(fails ? 1 : 0);
