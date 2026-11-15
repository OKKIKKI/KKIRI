import { issueToken, optionalPrincipal, requireCar } from '../auth.js';
import { Errors } from '../errors.js';
import { readJson, sendJson, type Router } from '../http.js';
import { getCar } from '../store/cars.js';
import {
  addCar, addParticipant, createSession, findByJoinCode, findByQrToken, sessionCar,
  toCar, toParticipant, toSession,
} from '../store/sessions.js';
import { touchActivity } from '../store/lifecycle.js';
import { emit } from '../ws/hub.js';
import { asObject, optBoolean, placeOrNull, reqString } from '../validate.js';
import { live, member } from './common.js';

function registeredCar(carId: string) {
  // 서버 재시작으로 등록 정보가 사라진 토큰은 여기 오지 않지만(토큰도 함께 사라짐), 방어적으로 확인한다.
  const car = getCar(carId);
  if (!car) throw Errors.unauthorized();
  return car;
}

export function sessionRoutes(r: Router): void {
  /** R-02 빠른 세션 (SES-01). 호출한 차가 HOST, ACTIVE, isAccountSession=false */
  r.add('POST', '/v1/sessions', async ({ req, res }) => {
    const { carId } = requireCar(req);
    registeredCar(carId);
    const body = asObject(await readJson(req));
    const rec = createSession({
      name: reqString(body, 'name'),
      destination: placeOrNull(body.destination, 'destination'),
      hostCarId: carId,
      state: 'ACTIVE',
      isAccountSession: false,
      ownerUserId: null,
      hostStatus: 'CONNECTED',
    });
    sendJson(res, 200, { session: toSession(rec) });
  });

  /** R-05 다른 차량 합류 (SES-03). 합류 차량은 모두 COHOST. applyDestination은 저장하지 않는다. */
  r.add('POST', '/v1/sessions/join-by-code', async ({ req, res }) => {
    const { carId } = requireCar(req);
    registeredCar(carId);
    const body = asObject(await readJson(req));
    const joinCode = reqString(body, 'joinCode');
    optBoolean(body, 'applyDestination'); // 형식만 확인
    const rec = live(findByJoinCode(joinCode), Errors.joinCodeInvalid);
    if (!sessionCar(rec, carId)) {
      const car = addCar(rec, carId, 'COHOST');
      emit(rec, 'car.joined', { car: toCar(car) });
    }
    touchActivity(rec); // Q-006 입력(입장)
    sendJson(res, 200, { session: toSession(rec) });
  });

  /** R-06 동승자 QR 입장 (SES-02, ACC-04). QR의 차량(호스트 차)에 배정. */
  r.add('POST', '/v1/sessions/join', async ({ req, res }) => {
    const principal = optionalPrincipal(req);
    if (principal && principal.kind !== 'USER') throw Errors.forbidden();
    const userId = principal?.kind === 'USER' ? principal.userId : null;

    const body = asObject(await readJson(req));
    const qrToken = reqString(body, 'qrToken');
    const name = reqString(body, 'name');
    const rec = live(findByQrToken(qrToken), Errors.sessionNotFound);
    if (Date.parse(rec.qrExpiresAt) <= Date.now()) throw Errors.qrExpired();

    const par = addParticipant(rec, {
      name,
      role: userId !== null && userId === rec.ownerUserId ? 'LEADER' : 'CREW',
      carId: rec.hostCarId,
      isMember: userId !== null,
      userId,
    });
    const guestToken = issueToken({ kind: 'GUEST', participantId: par.participantId, sessionId: rec.sessionId, carId: par.carId, userId });
    emit(rec, 'participant.joined', { participant: toParticipant(par) });
    touchActivity(rec); // Q-006 입력(입장)
    sendJson(res, 200, { guestToken, participantId: par.participantId, session: toSession(rec) });
  });

  /** R-07 세션 조회. 세션 소속 누구나. */
  r.add('GET', '/v1/sessions/:sessionId', ({ req, res, params }) => {
    const { rec } = member(req, params.sessionId);
    sendJson(res, 200, { session: toSession(rec) });
  });
}
