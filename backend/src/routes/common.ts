import type { IncomingMessage } from 'node:http';
import { bearer, principalOf, requirePrincipal, type Principal } from '../auth.js';
import { config } from '../config.js';
import { Errors } from '../errors.js';
import { actorFor, findById, type Lookup, type SessionRecord } from '../store/sessions.js';
import type { You } from '../types.js';

/** 찾은 세션이 살아 있으면 돌려주고, 없으면 notFound(), 끝났으면 SESSION_ENDED. */
export function live(found: Lookup, notFound: () => Error): SessionRecord {
  if (!found) throw notFound();
  if ('ended' in found) throw Errors.sessionEnded();
  return found.rec;
}

/**
 * 세션 안 호출: 토큰 확인 → 세션 확인 → 소속 확인(carToken·guestToken만, 명세 1.3).
 * queryToken: 헤더를 실을 수 없는 브라우저 주소 이동(R-40, 명세 v1.5)만 넘긴다. 헤더가 있으면 헤더가 우선.
 */
export function member(
  req: IncomingMessage,
  sessionId: string,
  queryToken: string | null = null,
): { rec: SessionRecord; you: You; principal: Principal } {
  const principal = bearer(req) === null && queryToken ? principalOf(queryToken) : requirePrincipal(req);
  if (!principal) throw Errors.unauthorized();
  const rec = live(findById(sessionId), Errors.sessionNotFound);
  const you = actorFor(rec, principal);
  if (!you) throw Errors.forbidden();
  return { rec, you, principal };
}

/** 세션 안 호출 중 차량만 가능한 것(R-21~R-24, R-33, R-34, R-46). */
export function carMember(req: IncomingMessage, sessionId: string): { rec: SessionRecord; carId: string } {
  const { rec, you } = member(req, sessionId);
  if (you.actorType !== 'CAR') throw Errors.forbidden();
  return { rec, carId: you.carId };
}

/**
 * redirect는 KKIRI_WEB_BASE와 같은 출처(origin)만 허용한다(다른 사이트로 토큰이 새는 것 방지).
 */
export function safeRedirect(raw: string | null): URL {
  if (!raw) throw Errors.validation('redirect가 필요해요');
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw Errors.validation('redirect 주소 형식이 아니에요');
  }
  if (url.origin !== new URL(config.webBase).origin) throw Errors.validation('redirect는 웹 주소(KKIRI_WEB_BASE)만 쓸 수 있어요');
  return url;
}
