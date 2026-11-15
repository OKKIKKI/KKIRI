import type { IncomingMessage } from 'node:http';
import { createLoginProvider, LOGIN_NOT_CONFIGURED } from '../adapters/login.js';
import { requirePrincipal } from '../auth.js';
import { Errors } from '../errors.js';
import { readJson, sendJson, type Router } from '../http.js';
import { startLifecycle } from '../store/lifecycle.js';
import { createSession, toSession } from '../store/sessions.js';
import { friendCarsOf, getUser, myCars } from '../store/users.js';
import { asObject, placeOrNull, reqString } from '../validate.js';
import { safeRedirect } from './common.js';

export function authRoutes(r: Router): void {
  const login = createLoginProvider();

  /**
   * R-10 로그인 시작 (ACC-01). 명세 3.2: 끝나면 {redirect}#userToken=... 으로 돌려보낸다.
   * 로그인 방식은 adapters/login.ts의 LoginProvider. D-10 확정 전에는 DEV_LOGIN=1 개발용 로그인만 있다.
   */
  r.add('GET', '/v1/auth/kakao/start', async ({ res, query }) => {
    const redirect = safeRedirect(query.get('redirect'));
    if (!login) throw LOGIN_NOT_CONFIGURED();
    res.writeHead(302, { Location: await login.start(redirect), 'Cache-Control': 'no-store' });
    res.end();
  });

  /** R-11 외부 로그인 콜백. 카카오 실제 구현(D-10)이 생기면 그 provider가 처리한다. 지금은 없음(404). */
  r.add('GET', '/v1/auth/kakao/callback', async ({ req, res, query }) => {
    if (!login?.callback) throw Errors.notFound();
    res.writeHead(302, { Location: await login.callback(req, query), 'Cache-Control': 'no-store' });
    res.end();
  });

  /** R-12 내 정보. userToken만. */
  r.add('GET', '/v1/me', ({ req, res }) => {
    const user = requireUser(req);
    sendJson(res, 200, { userId: user.userId, name: user.name });
  });

  /**
   * R-13 내 차 (ACC-02, 1차 목업: 시드). seed/users.json의 deviceIds 중 R-01로 등록된 기기의 차만 돌려준다(명세 v1.5).
   * ⚠ 시드에 시연 기기 deviceId가 없으면 빈 목록이다 → backend/README.md "시연 기기 deviceId 넣기".
   */
  r.add('GET', '/v1/me/cars', ({ req, res }) => {
    const user = requireUser(req);
    sendJson(res, 200, { cars: myCars(user.userId) });
  });

  /**
   * R-14 세션 미리 열기 (ACC-03). carId는 내 차(R-13 목록)여야 한다(아니면 FORBIDDEN).
   * RESERVED, isAccountSession=true, 호스트=그 차(DISCONNECTED). RESERVE_ATTACH_TIMEOUT_SEC 안에 R-04가 없으면 RESERVE_TIMEOUT.
   * 리더는 이후 R-06을 userToken과 함께 불러 LEADER로 들어간다.
   */
  r.add('POST', '/v1/sessions/reserve', async ({ req, res }) => {
    const user = requireUser(req);
    const body = asObject(await readJson(req));
    const carId = reqString(body, 'carId');
    const name = reqString(body, 'name');
    const destination = placeOrNull(body.destination, 'destination');
    if (!myCars(user.userId).some((c) => c.carId === carId)) throw Errors.forbidden();
    const rec = createSession({
      name,
      destination,
      hostCarId: carId,
      state: 'RESERVED',
      isAccountSession: true,
      ownerUserId: user.userId,
      hostStatus: 'DISCONNECTED',
    });
    startLifecycle(rec);
    sendJson(res, 200, { session: toSession(rec) });
  });

  /** R-15 친구 차량 (ACC-05, 목업: 고정 목록). userToken만. 초대 API는 1차에 없다. */
  r.add('GET', '/v1/me/friend-cars', ({ req, res }) => {
    const user = requireUser(req);
    sendJson(res, 200, { friendCars: friendCarsOf(user.userId) });
  });
}

/** 세션 밖 계정 호출(R-12~R-15): userToken만(명세 1.3). */
function requireUser(req: IncomingMessage) {
  const p = requirePrincipal(req);
  if (p.kind !== 'USER') throw Errors.forbidden();
  const user = getUser(p.userId);
  if (!user) throw Errors.unauthorized();
  return user;
}
