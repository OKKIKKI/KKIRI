import type { IncomingMessage } from 'node:http';
import { issueToken } from '../auth.js';
import { config } from '../config.js';
import { randomBytes } from 'node:crypto';
import { ApiError, Errors } from '../errors.js';
import { devLoginUser, userForKakao } from '../store/users.js';

/**
 * LoginProvider (D-10, ACC-01): 운전자 로그인 방식. R-10·R-11은 이 뒤에서만 동작한다.
 * 개발용(DEV_LOGIN=1, 시연 기본)과 카카오(KakaoLoginProvider, 키가 있고 DEV_LOGIN이 꺼졌을 때)가 있다.
 *
 * 공통 결과(명세 3.2): 로그인이 끝나면 브라우저를 `{redirect}#userToken=...`으로 보낸다.
 */
export interface LoginProvider {
  readonly name: 'DEV' | 'KAKAO';
  /** R-10: 브라우저를 보낼 주소(302 Location). redirect는 이미 KKIRI_WEB_BASE 출처로 검증된 값 */
  start(redirect: URL): Promise<string>;
  /**
   * R-11: 외부 로그인 서비스가 돌려보낸 요청 처리 → 브라우저를 보낼 주소(`{redirect}#userToken=...`).
   * 개발용 로그인은 콜백이 없다(null).
   */
  readonly callback: ((req: IncomingMessage, query: URLSearchParams) => Promise<string>) | null;
}

/** 로그인이 끝난 사용자를 `{redirect}#userToken=...`으로 보낼 주소 */
export function finishLogin(redirect: URL, userId: string): string {
  const userToken = issueToken({ kind: 'USER', userId });
  redirect.hash = `userToken=${encodeURIComponent(userToken)}`;
  return redirect.toString();
}

/** 개발용 로그인: 외부 서비스 없이 seed/users.json 첫 사용자로 바로 로그인 */
class DevLoginProvider implements LoginProvider {
  readonly name = 'DEV' as const;
  readonly callback = null;
  async start(redirect: URL): Promise<string> {
    return finishLogin(redirect, devLoginUser().userId);
  }
}

/**
 * 카카오 로그인 (D-10, 명세 v1.8 R-10/11, D-23).
 * API 주소·파라미터·응답 필드는 카카오 디벨로퍼스 공식 문서 "카카오 로그인 > REST API"에서 확인한 것만 쓴다(2026-11-12 확인):
 *  - 인가 코드 요청: GET https://kauth.kakao.com/oauth/authorize
 *      client_id(REST API 키), redirect_uri(콘솔 등록값과 일치), response_type=code, state(CSRF 방지)
 *      성공 → redirect_uri?code=&state= / 실패 → redirect_uri?error=&error_description=
 *  - 토큰 요청: POST https://kauth.kakao.com/oauth/token, Content-Type: application/x-www-form-urlencoded;charset=utf-8
 *      grant_type=authorization_code, client_id, redirect_uri(인가 때와 같은 값), code, client_secret(켜져 있으면 필수, REST API 키는 기본 ON)
 *      응답 access_token. 같은 인가 코드는 한 번만(재사용·만료 시 KOE320)
 *  - 사용자 정보 조회: GET https://kapi.kakao.com/v2/user/me, Authorization: Bearer ${ACCESS_TOKEN},
 *      Content-Type: application/x-www-form-urlencoded;charset=utf-8
 *      응답 id(Long, 회원번호), kakao_account.profile.nickname(닉네임 동의 시). properties.nickname은 Deprecated라 쓰지 않음
 * 토큰·비밀값은 로그에 남기지 않는다. 카카오 액세스 토큰은 사용자 정보를 읽은 뒤 버린다(저장하지 않음).
 */
export class KakaoLoginProvider implements LoginProvider {
  readonly name = 'KAKAO' as const;
  /** state → 돌아갈 웹 주소. 한 번 쓰면 지운다 */
  private readonly pending = new Map<string, { redirect: string; expiresAt: number }>();
  private readonly redirectUri: string;

  constructor(
    private readonly opts: {
      restApiKey: string;
      clientSecret: string | null;
      apiBase: string;
      /** 테스트용으로만 바꾼다. 기본은 공식 문서의 주소 */
      authHost?: string;
      apiHost?: string;
      /** state 유효 시간(ms). 사람이 카카오 화면에서 로그인할 시간 */
      stateTtlMs?: number;
    },
  ) {
    this.redirectUri = `${opts.apiBase}/v1/auth/kakao/callback`;
  }

  private get authHost() { return this.opts.authHost ?? 'https://kauth.kakao.com'; }
  private get apiHost() { return this.opts.apiHost ?? 'https://kapi.kakao.com'; }

  async start(redirect: URL): Promise<string> {
    const now = Date.now();
    for (const [k, v] of this.pending) if (v.expiresAt <= now) this.pending.delete(k);
    const state = randomBytes(16).toString('base64url');
    this.pending.set(state, { redirect: redirect.toString(), expiresAt: now + (this.opts.stateTtlMs ?? 10 * 60 * 1000) });
    const u = new URL('/oauth/authorize', this.authHost);
    u.searchParams.set('client_id', this.opts.restApiKey);
    u.searchParams.set('redirect_uri', this.redirectUri);
    u.searchParams.set('response_type', 'code');
    u.searchParams.set('state', state);
    return u.toString();
  }

  readonly callback = async (_req: IncomingMessage, query: URLSearchParams): Promise<string> => {
    const state = query.get('state') ?? '';
    const entry = this.pending.get(state);
    this.pending.delete(state);
    if (!entry || entry.expiresAt <= Date.now()) throw Errors.validation('로그인 요청이 만료됐어요. 다시 로그인해 주세요');
    const back = new URL(entry.redirect);

    const error = query.get('error');
    if (error) {
      // 사용자가 취소했거나(access_denied 등) 카카오 쪽 오류: 토큰 없이 웹으로 돌려보낸다(웹은 로그인 버튼을 다시 보여 줌)
      console.warn(`[kakao] 인가 실패: ${error}`);
      return back.toString();
    }
    const code = query.get('code');
    if (!code) throw Errors.validation('인가 코드가 없어요');

    try {
      const accessToken = await this.exchangeCode(code);
      const me = await this.fetchMe(accessToken);
      const { user, seeded, created } = userForKakao(me.id, me.nickname);
      // D-23: 시드에 kakaoId를 채울 수 있게 회원번호를 남긴다(토큰·닉네임 외 개인정보는 남기지 않음)
      console.log(`[kakao] 로그인 회원번호 ${me.id} → ${user.userId}${seeded ? ' (시드 연결)' : created ? ' (새 계정, 시드에 kakaoId를 넣으면 연결됨)' : ''}`);
      return finishLogin(back, user.userId);
    } catch (e) {
      console.warn(`[kakao] 로그인 처리 실패: ${(e as Error).message}`);
      return back.toString();
    }
  };

  private async exchangeCode(code: string): Promise<string> {
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: this.opts.restApiKey,
      redirect_uri: this.redirectUri,
      code,
    });
    if (this.opts.clientSecret) body.set('client_secret', this.opts.clientSecret);
    const r = await fetch(new URL('/oauth/token', this.authHost), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8' },
      body,
      signal: AbortSignal.timeout(8000),
    });
    const j = (await r.json().catch(() => ({}))) as Record<string, unknown>;
    if (!r.ok || typeof j.access_token !== 'string') {
      // 카카오 오류 코드만 남긴다(본문 전체·비밀값은 남기지 않음)
      throw new Error(`토큰 요청 ${r.status} ${String(j.error_code ?? j.error ?? '')}`.trim());
    }
    return j.access_token;
  }

  private async fetchMe(accessToken: string): Promise<{ id: string; nickname: string | null }> {
    const r = await fetch(new URL('/v2/user/me', this.apiHost), {
      method: 'GET',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8' },
      signal: AbortSignal.timeout(8000),
    });
    const j = (await r.json().catch(() => ({}))) as { id?: unknown; kakao_account?: { profile?: { nickname?: unknown } } };
    if (!r.ok || (typeof j.id !== 'number' && typeof j.id !== 'string')) throw new Error(`사용자 정보 ${r.status}`);
    const nick = j.kakao_account?.profile?.nickname;
    return { id: String(j.id), nickname: typeof nick === 'string' && nick.trim() ? nick.trim() : null };
  }
}

/**
 * 지금 쓸 로그인 방식.
 * - DEV_LOGIN=1 → 개발용(시연 기본, 명세 v1.8: 카카오 연결과 무관하게 시드 첫 사용자)
 * - 아니고 KAKAO_REST_API_KEY가 있으면 → 카카오
 * - 둘 다 아니면 null → R-10이 500 INTERNAL(명세 v1.5 Q-009 ③)
 */
export function createLoginProvider(): LoginProvider | null {
  if (config.devLogin) return new DevLoginProvider();
  if (config.kakaoRestApiKey && config.apiBase) {
    return new KakaoLoginProvider({ restApiKey: config.kakaoRestApiKey, clientSecret: config.kakaoClientSecret, apiBase: config.apiBase });
  }
  return null;
}

export const LOGIN_NOT_CONFIGURED = () =>
  new ApiError(500, 'INTERNAL', '카카오 로그인이 아직 설정되지 않았어요(D-10). DEV_LOGIN=1로 실행하세요');
