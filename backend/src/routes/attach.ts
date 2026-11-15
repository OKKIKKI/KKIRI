import { requireCar } from '../auth.js';
import { Errors } from '../errors.js';
import { sendJson, type Router } from '../http.js';
import { resumeSession } from '../store/lifecycle.js';
import { sessionCar, sessionsOfCar, toSession, type SessionRecord } from '../store/sessions.js';
import { emit } from '../ws/hub.js';
import { carMember } from './common.js';

/**
 * 이 차가 시동(앱 시작) 때 붙어야 할 세션인가 (R-03).
 * - RESERVED: 이 차를 호스트로 미리 연 예약 세션(R-14)
 * - PAUSED: 이 차가 속한 일시정지 세션
 * - 이 차 자신이 PAUSED·DISCONNECTED인 ACTIVE 세션(공동호스트가 먼저 시동을 끈 경우 등) — 명세 v1.5 R-03 ③
 */
function isPending(rec: SessionRecord, carId: string): boolean {
  if (rec.state === 'RESERVED') return rec.hostCarId === carId;
  if (rec.state === 'PAUSED') return true;
  const car = sessionCar(rec, carId);
  return rec.state === 'ACTIVE' && !!car && car.status !== 'CONNECTED';
}

export function attachRoutes(r: Router): void {
  /** R-03 붙어야 할 세션 조회 (ACC-03, SES-06). 없으면 { session: null }. 여러 개면 가장 최근 것. */
  r.add('GET', '/v1/cars/me/pending-session', ({ req, res }) => {
    const { carId } = requireCar(req);
    const rec = sessionsOfCar(carId).find((s) => isPending(s, carId)) ?? null;
    sendJson(res, 200, { session: rec ? toSession(rec) : null });
  });

  /**
   * R-04 세션에 붙기 (ACC-03, SES-06). 본문 없음.
   * 이 차를 CONNECTED로(바뀌면 car.status_changed). 호스트 차면 RESERVED·PAUSED → ACTIVE(session.state_changed).
   * 공동호스트는 세션 상태를 바꾸지 않는다. R-04는 Q-006 "입력"이 아니다(재연결).
   */
  r.add('POST', '/v1/sessions/:sessionId/attach', ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const car = sessionCar(rec, carId)!;
    if (rec.state === 'RESERVED' && carId !== rec.hostCarId) throw Errors.forbidden();
    if (car.status !== 'CONNECTED') {
      car.status = 'CONNECTED';
      emit(rec, 'car.status_changed', { carId, status: car.status, driveState: car.driveState });
    }
    if (carId === rec.hostCarId) resumeSession(rec);
    sendJson(res, 200, { session: toSession(rec) });
  });
}
