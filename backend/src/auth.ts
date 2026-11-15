import type { IncomingMessage } from 'node:http';
import { Errors } from './errors.js';
import { newSecret } from './util.js';

/** 명세 1.3절 토큰이 담는 정보 */
export type CarPrincipal = { kind: 'CAR'; carId: string };
export type GuestPrincipal = { kind: 'GUEST'; participantId: string; sessionId: string; carId: string; userId: string | null };
export type UserPrincipal = { kind: 'USER'; userId: string };
export type Principal = CarPrincipal | GuestPrincipal | UserPrincipal;

const PREFIX = { CAR: 'ct_', GUEST: 'gt_', USER: 'ut_' } as const;

// 메모리 저장(D-02). 서버 재시작 시 모든 토큰이 무효가 된다.
const tokens = new Map<string, Principal>();

export function issueToken(p: Principal): string {
  const token = newSecret(PREFIX[p.kind]);
  tokens.set(token, p);
  return token;
}

export function revokeToken(token: string): void {
  tokens.delete(token);
}

/** 세션 파기 시 그 세션의 guestToken을 모두 지운다(SES-04). */
export function revokeSessionTokens(sessionId: string): void {
  for (const [t, p] of tokens) if (p.kind === 'GUEST' && p.sessionId === sessionId) tokens.delete(t);
}

export function principalOf(token: string | null | undefined): Principal | null {
  if (!token) return null;
  return tokens.get(token) ?? null;
}

export function bearer(req: IncomingMessage): string | null {
  const h = req.headers.authorization;
  if (!h || !h.startsWith('Bearer ')) return null;
  return h.slice('Bearer '.length).trim() || null;
}

/** 헤더에 토큰이 있으면 확인하고, 없으면 null. 잘못된 토큰은 UNAUTHORIZED. */
export function optionalPrincipal(req: IncomingMessage): Principal | null {
  const t = bearer(req);
  if (t === null) return null;
  const p = principalOf(t);
  if (!p) throw Errors.unauthorized();
  return p;
}

export function requirePrincipal(req: IncomingMessage): Principal {
  const p = optionalPrincipal(req);
  if (!p) throw Errors.unauthorized();
  return p;
}

export function requireCar(req: IncomingMessage): CarPrincipal {
  const p = requirePrincipal(req);
  if (p.kind !== 'CAR') throw Errors.forbidden();
  return p;
}
