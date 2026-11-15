import { Errors } from '../errors.js';
import { readJson, sendJson, sendNoContent, type Router } from '../http.js';
import { getCar } from '../store/cars.js';
import { ackHeld, checkUrgentLimit, deliver, heldMessages } from '../store/messages.js';
import { sessionCar, type SessionRecord } from '../store/sessions.js';
import type { Message } from '../types.js';
import { isoNow, newId } from '../util.js';
import { emit } from '../ws/hub.js';
import { touchActivity } from '../store/lifecycle.js';
import { asObject, optBoolean, reqString } from '../validate.js';
import { carMember, member } from './common.js';

/** "ALL" 또는 이 세션의 carId */
function targetOf(rec: SessionRecord, body: Record<string, unknown>, key: string): string {
  const t = reqString(body, key);
  if (t !== 'ALL' && !sessionCar(rec, t)) throw Errors.validation(`${key}는 "ALL" 또는 이 세션의 carId여야 해요`);
  return t;
}

export function messageRoutes(r: Router): void {
  /**
   * R-20 메시지 (MSG-01 웹 TEXT, MSG-03 차량 BROADCAST, MSG-05 긴급 제한).
   * 행위자: guestToken(참가자) → kind=TEXT, carToken → kind=BROADCAST.
   */
  r.add('POST', '/v1/sessions/:sessionId/messages', async ({ req, res, params }) => {
    const { rec, you } = member(req, params.sessionId);
    const body = asObject(await readJson(req));
    const kind = reqString(body, 'kind');
    const expected = you.actorType === 'CAR' ? 'BROADCAST' : 'TEXT';
    if (kind !== expected) throw Errors.validation(`이 토큰으로 보낼 수 있는 kind는 ${expected}예요`);
    const text = reqString(body, 'text');
    const urgent = optBoolean(body, 'urgent') ?? false;
    const target = targetOf(rec, body, 'target');

    const sender: Message['sender'] = you.actorType === 'CAR'
      ? { type: 'CAR', id: you.carId, name: getCar(you.carId)?.driverName ?? '', carId: you.carId }
      : {
        type: 'PARTICIPANT',
        id: you.participantId!,
        name: rec.participants.find((p) => p.participantId === you.participantId)?.name ?? '',
        carId: you.carId,
      };
    const msg: Message = {
      messageId: newId('msg_'), kind, sender, target, text, clip: null, urgent, createdAt: isoNow(),
    };
    checkUrgentLimit(rec, msg);
    deliver(rec, msg);
    touchActivity(rec); // Q-006 입력(메시지)
    sendJson(res, 200, { message: msg });
  });

  /** R-22 음소거 (VOI-06). 호출한 차에서만 안 들리게 한다. targetCarId="ALL"이면 지금 세션의 다른 차 전부. */
  r.add('PUT', '/v1/sessions/:sessionId/mutes', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const body = asObject(await readJson(req));
    const target = targetOf(rec, body, 'targetCarId');
    const muted = optBoolean(body, 'muted');
    if (muted === null) throw Errors.validation('muted가 필요해요');
    if (target === carId) throw Errors.validation('내 차는 음소거할 수 없어요');

    const targets = target === 'ALL' ? rec.cars.map((c) => c.carId).filter((id) => id !== carId) : [target];
    rec.mutes = rec.mutes.filter((m) => !(m.byCarId === carId && targets.includes(m.targetCarId)));
    if (muted) for (const t of targets) rec.mutes.push({ byCarId: carId, targetCarId: t });

    emit(rec, 'mute.changed', { mutes: rec.mutes });
    sendJson(res, 200, { mutes: rec.mutes });
  });

  /** R-23 이 차에 HOLD로 쌓인 메시지 */
  r.add('GET', '/v1/sessions/:sessionId/held-messages', ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    sendJson(res, 200, { messages: heldMessages(rec, carId) });
  });

  /** R-24 확인한 메시지를 보관함에서 뺀다. 없는 ID는 무시. */
  r.add('POST', '/v1/sessions/:sessionId/held-messages/ack', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const body = asObject(await readJson(req));
    const ids = body.messageIds;
    if (!Array.isArray(ids) || !ids.every((x) => typeof x === 'string')) throw Errors.validation('messageIds는 문자열 배열이어야 해요');
    ackHeld(rec, carId, ids as string[]);
    sendNoContent(res);
  });
}
