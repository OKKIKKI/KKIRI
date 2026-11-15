import { Errors } from '../errors.js';
import { readJson, sendNoContent, type Router } from '../http.js';
import { endSession, pauseSession } from '../store/lifecycle.js';
import { sessionCar } from '../store/sessions.js';
import type { CarStatus, DriveState } from '../types.js';
import { emit } from '../ws/hub.js';
import { asObject, optBoolean, reqString } from '../validate.js';
import { carMember, member } from './common.js';

const DRIVE_STATES: DriveState[] = ['DRIVING', 'PARKED', 'UNKNOWN'];
const IGNITIONS = ['ON', 'OFF'] as const;

export function driveRoutes(r: Router): void {
  /**
   * R-08 주행 상태 (COM-01, SES-06). 응답 204.
   * 차 상태를 반영하고 바뀌었으면 car.status_changed. 시동 OFF면 그 차는 PAUSED.
   * 호스트 차의 시동 OFF면 세션 ACTIVE → PAUSED + 종료 타이머(session.state_changed).
   * R-08은 Q-006의 "입력"이 아니다(타이머를 늘리지 않는다).
   */
  r.add('POST', '/v1/sessions/:sessionId/car-state', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const body = asObject(await readJson(req));
    const driveState = reqString(body, 'driveState') as DriveState;
    if (!DRIVE_STATES.includes(driveState)) throw Errors.validation('driveState는 DRIVING, PARKED, UNKNOWN 중 하나예요');
    const ignition = reqString(body, 'ignition');
    if (!(IGNITIONS as readonly string[]).includes(ignition)) throw Errors.validation('ignition은 ON 또는 OFF예요');

    const car = sessionCar(rec, carId)!;
    const status: CarStatus = ignition === 'OFF' ? 'PAUSED' : 'CONNECTED';
    if (car.driveState !== driveState || car.status !== status) {
      car.driveState = driveState;
      car.status = status;
      emit(rec, 'car.status_changed', { carId, status, driveState });
    }
    if (ignition === 'OFF' && carId === rec.hostCarId) pauseSession(rec);
    sendNoContent(res);
  });

  /**
   * R-47 내 차 모양 (v1.15). 응답 204. 이 차의 look을 저장하고 같은 세션 모든 연결에 car.look_changed.
   * 화면 장식이라 "입력"(명세 1.2)이 아니다(타이머를 늘리지 않는다). 같은 값이면 이벤트를 다시 보내지 않는다.
   */
  r.add('POST', '/v1/sessions/:sessionId/cars/me/look', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const body = asObject(await readJson(req));
    const step = body.steerStep;
    if (typeof step !== 'number' || !Number.isInteger(step) || step < -2 || step > 2) throw Errors.validation('steerStep은 -2~2 정수예요');
    const color = body.color;
    if (color !== null && (typeof color !== 'string' || !/^#[0-9A-F]{6}$/.test(color))) throw Errors.validation('color는 #RRGGBB(대문자) 또는 null이에요');
    const hat = optBoolean(body, 'hat');
    const balloon = optBoolean(body, 'balloon');
    if (hat === null || balloon === null) throw Errors.validation('hat, balloon은 true/false예요');

    // v1.16: character·items는 생략 가능(이전 앱) → null·[]
    const idRe = /^[a-z0-9_]{1,24}$/;
    const character = body.character === undefined ? null : body.character;
    if (character !== null && (typeof character !== 'string' || !idRe.test(character))) throw Errors.validation('character는 id 문자열 또는 null이에요');
    const rawItems = body.items === undefined ? [] : body.items;
    if (!Array.isArray(rawItems) || rawItems.length > 4 || rawItems.some((x) => typeof x !== 'string' || !idRe.test(x)) || new Set(rawItems).size !== rawItems.length) {
      throw Errors.validation('items는 id 문자열 배열(최대 4개, 중복 없음)이에요');
    }
    const items = rawItems as string[];

    const car = sessionCar(rec, carId)!;
    const look = { steerStep: step, color: color as string | null, hat, balloon, character: character as string | null, items };
    const p = car.look;
    if (!p || p.steerStep !== look.steerStep || p.color !== look.color || p.hat !== look.hat || p.balloon !== look.balloon || p.character !== look.character || p.items.join(',') !== look.items.join(',')) {
      car.look = look;
      emit(rec, 'car.look_changed', { carId, look });
    }
    sendNoContent(res);
  });

  /** R-09 세션 종료 (SES-04). 호스트 차량 또는 리더만. 응답 204, 이벤트 session.ended(HOST_ENDED). */
  r.add('POST', '/v1/sessions/:sessionId/end', ({ req, res, params }) => {
    const { rec, you } = member(req, params.sessionId);
    const allowed = (you.actorType === 'CAR' && you.role === 'HOST') || (you.actorType === 'PARTICIPANT' && you.role === 'LEADER');
    if (!allowed) throw Errors.forbidden();
    endSession(rec, 'HOST_ENDED');
    sendNoContent(res);
  });
}
