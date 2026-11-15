import { revokeSessionTokens } from '../auth.js';
import { config } from '../config.js';
import { getCar } from './cars.js';
import { removeSession, type SessionRecord } from './sessions.js';
import { toIso } from '../util.js';
import { closeSession, emit } from '../ws/hub.js';

/**
 * 세션 수명 (SES-04, SES-06, D-16, Q-006 승인).
 * - 무로그인 세션: 호스트 시동이 꺼진 뒤(PAUSED) 입력 없이 PAUSE_TIMEOUT_SEC가 지나면 종료(PAUSE_TIMEOUT).
 * - 예약 세션(RESERVED, R-14): 만든 뒤 RESERVE_ATTACH_TIMEOUT_SEC 안에 R-04가 없으면 종료(RESERVE_TIMEOUT).
 * - 계정 세션: 마지막 입력 후 ACCOUNT_SESSION_TTL_SEC가 지나면 종료(ACCOUNT_TTL). 차가 붙은 뒤에는 상태와 무관하게 센다.
 * - expiresAt은 PAUSED일 때만 내보낸다(ACTIVE면 null, fixtures와 같음).
 * "입력" = Q-006 목록의 REST 성공(R-05·06·20·21·31~34·43~46). touchActivity()로 알린다.
 */

export type EndReason = 'HOST_ENDED' | 'PAUSE_TIMEOUT' | 'ACCOUNT_TTL' | 'RESERVE_TIMEOUT';

function clearEndTimer(rec: SessionRecord): void {
  if (rec.endTimer) clearTimeout(rec.endTimer);
  rec.endTimer = null;
}

/** 지금 기준 종료 시각(ms)과 사유. 종료 타이머가 필요 없으면 null. */
function deadline(rec: SessionRecord): { at: number; reason: EndReason } | null {
  // 예약 세션: 만든 뒤 RESERVE_ATTACH_TIMEOUT_SEC 안에 호스트 차가 R-04로 붙지 않으면 종료. 입력으로 늘어나지 않는다.
  if (rec.state === 'RESERVED') return { at: Date.parse(rec.createdAt) + config.reserveAttachTimeoutSec * 1000, reason: 'RESERVE_TIMEOUT' };
  if (rec.isAccountSession) return { at: rec.lastActivityAt + config.accountSessionTtlSec * 1000, reason: 'ACCOUNT_TTL' };
  if (rec.state === 'PAUSED') return { at: rec.lastActivityAt + config.pauseTimeoutSec * 1000, reason: 'PAUSE_TIMEOUT' };
  return null;
}

/** 종료 타이머를 다시 맞추고 expiresAt을 갱신한다. expiresAt이 바뀌면 true. */
function reschedule(rec: SessionRecord): boolean {
  clearEndTimer(rec);
  const d = deadline(rec);
  if (d) rec.endTimer = setTimeout(() => endSession(rec, d.reason), Math.max(0, d.at - Date.now()));
  const next = rec.state === 'PAUSED' && d ? toIso(d.at) : null;
  const changed = next !== rec.expiresAt;
  rec.expiresAt = next;
  return changed;
}

function emitStateChanged(rec: SessionRecord): void {
  emit(rec, 'session.state_changed', { state: rec.state, pausedAt: rec.pausedAt, expiresAt: rec.expiresAt });
}

/** 예약 세션을 만들 때(R-14) 부른다: 예약 미연결 타이머 시작. R-04로 ACTIVE가 되면 계정 TTL로 바뀐다. */
export function startLifecycle(rec: SessionRecord): void {
  rec.lastActivityAt = Date.now();
  reschedule(rec);
}

/**
 * Q-006 "입력"이 성공했을 때 부른다. 마지막 활동을 지금으로 바꾸고 타이머를 다시 맞춘다.
 * PAUSED라 expiresAt이 늦춰지면 session.state_changed를 다시 보낸다(PM 승인, 명세 v1.4 예정).
 */
export function touchActivity(rec: SessionRecord): void {
  if (rec.state === 'ENDED') return;
  rec.lastActivityAt = Date.now();
  if (reschedule(rec)) emitStateChanged(rec);
}

/** 호스트 시동 OFF(R-08): ACTIVE → PAUSED. 무로그인 세션은 이때부터 무입력 시간을 센다. */
export function pauseSession(rec: SessionRecord): void {
  if (rec.state !== 'ACTIVE') return;
  rec.state = 'PAUSED';
  const now = Date.now();
  rec.pausedAt = toIso(now);
  if (!rec.isAccountSession) rec.lastActivityAt = now; // 시동이 꺼진 뒤부터 센다
  reschedule(rec);
  emitStateChanged(rec);
}

/** R-04: RESERVED → ACTIVE(예약 세션에 차 연결) 또는 PAUSED → ACTIVE(다시 연결). 종료 타이머도 다시 맞춘다. */
export function resumeSession(rec: SessionRecord): void {
  if (rec.state !== 'PAUSED' && rec.state !== 'RESERVED') return;
  rec.state = 'ACTIVE';
  rec.pausedAt = null;
  reschedule(rec);
  emitStateChanged(rec);
}

/** 오늘의 주행 요약(SES-07, 목업 값 3가지). 파기 전에 만든다. */
function summary(rec: SessionRecord) {
  const places = [...new Set(rec.waypoints.map((w) => w.place.name))];
  const cars = rec.cars.map((c) => getCar(c.carId)?.displayName ?? '');
  // 함께 들은 곡: 세션 동안 "지금 재생 중"이 된 곡 수(store/music.ts가 센다)
  const trackCount = rec.trackCount;
  return { places, cars, trackCount };
}

/**
 * 세션 종료·파기(SES-04). session.ended를 보내고 연결을 닫은 뒤,
 * 메시지·음성 클립·비회원 참가자·경유지·guestToken을 지운다. 계정과 등록 차량은 남는다.
 */
export function endSession(rec: SessionRecord, reason: EndReason): void {
  if (rec.state === 'ENDED') return;
  clearEndTimer(rec);
  for (const t of rec.timers) clearTimeout(t);
  rec.timers.clear();
  const s = summary(rec);
  rec.state = 'ENDED';
  emit(rec, 'session.ended', { reason, summary: s });
  closeSession(rec.sessionId);
  revokeSessionTokens(rec.sessionId);
  removeSession(rec);
}
