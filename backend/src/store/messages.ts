import { config } from '../config.js';
import { Errors } from '../errors.js';
import type { DeliveryMode, Message, You } from '../types.js';
import { emit } from '../ws/hub.js';
import type { SessionRecord } from './sessions.js';

/** 명세 2절: target이 carId면 그 차와 보낸 쪽(차)에만 전달한다. */
function receivesCar(msg: Message, carId: string): boolean {
  return msg.target === 'ALL' || carId === msg.target || carId === msg.sender.carId;
}

function hasMuted(rec: SessionRecord, byCarId: string, targetCarId: string): boolean {
  return rec.mutes.some((m) => m.byCarId === byCarId && m.targetCarId === targetCarId);
}

/**
 * 명세 4.3 delivery.mode — 받는 쪽마다 서버가 정한다.
 * 웹 = DISPLAY / 자기 차가 보낸 메시지는 그 차에 DISPLAY /
 * 받는 차가 보낸 쪽 차를 음소거했고 urgent=false면 HOLD / 그 밖은 SPEAK.
 */
export function deliveryModeForCar(rec: SessionRecord, msg: Message, carId: string): DeliveryMode {
  if (msg.sender.type === 'CAR' && msg.sender.carId === carId) return 'DISPLAY';
  if (!msg.urgent && hasMuted(rec, carId, msg.sender.carId)) return 'HOLD';
  return 'SPEAK';
}

function senderKey(msg: Message): string {
  return `${msg.sender.type}:${msg.sender.id}`;
}

/** 긴급 메시지가 갈 차 중 보낸 쪽을 음소거한 차 */
function mutingRecipients(rec: SessionRecord, msg: Message): string[] {
  return rec.cars
    .map((c) => c.carId)
    .filter((carId) => carId !== msg.sender.carId && receivesCar(msg, carId) && hasMuted(rec, carId, msg.sender.carId));
}

/**
 * MSG-05: 음소거한 차에는 보낸 사람당 URGENT_LIMIT_SEC에 긴급 1건.
 * 한 차라도 걸리면 전체 실패(URGENT_RATE_LIMITED, details.retryAfterSec).
 */
export function checkUrgentLimit(rec: SessionRecord, msg: Message, nowMs = Date.now()): void {
  if (!msg.urgent) return;
  const limitMs = config.urgentLimitSec * 1000;
  let waitMs = 0;
  for (const carId of mutingRecipients(rec, msg)) {
    const last = rec.lastUrgentAt.get(`${senderKey(msg)}>${carId}`);
    if (last !== undefined && nowMs - last < limitMs) waitMs = Math.max(waitMs, limitMs - (nowMs - last));
  }
  if (waitMs > 0) throw Errors.urgentRateLimited(Math.ceil(waitMs / 1000));
}

/** 메시지를 저장하고, HOLD인 차의 보관함에 넣고, message.created를 받는 쪽마다 보낸다. */
export function deliver(rec: SessionRecord, msg: Message, nowMs = Date.now()): void {
  rec.messages.push(msg);
  if (msg.urgent) {
    for (const carId of mutingRecipients(rec, msg)) rec.lastUrgentAt.set(`${senderKey(msg)}>${carId}`, nowMs);
  }
  for (const c of rec.cars) {
    if (!receivesCar(msg, c.carId)) continue;
    if (deliveryModeForCar(rec, msg, c.carId) === 'HOLD') {
      const box = rec.held.get(c.carId) ?? [];
      box.push(msg.messageId);
      rec.held.set(c.carId, box);
    }
  }
  emit(rec, 'message.created', (you: You) => {
    if (!receivesCar(msg, you.carId)) return null;
    const mode: DeliveryMode = you.actorType === 'PARTICIPANT' ? 'DISPLAY' : deliveryModeForCar(rec, msg, you.carId);
    return { message: msg, delivery: { mode } };
  });
}

export function heldMessages(rec: SessionRecord, carId: string): Message[] {
  const ids = new Set(rec.held.get(carId) ?? []);
  return rec.messages.filter((m) => ids.has(m.messageId));
}

export function ackHeld(rec: SessionRecord, carId: string, messageIds: string[]): void {
  const remove = new Set(messageIds);
  const box = (rec.held.get(carId) ?? []).filter((id) => !remove.has(id));
  if (box.length) rec.held.set(carId, box);
  else rec.held.delete(carId);
}
