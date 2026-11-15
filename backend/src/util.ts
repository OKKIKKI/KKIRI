import { randomBytes } from 'node:crypto';

/** 명세 1.1: 접두사가 붙은 문자열 ID (`ses_`, `car_`, `par_` ...) */
export function newId(prefix: string): string {
  return prefix + randomBytes(6).toString('hex');
}

export function newSecret(prefix: string): string {
  return prefix + randomBytes(24).toString('base64url');
}

/** 명세 1.1: ISO-8601 UTC (초 단위, 예 "2026-11-13T05:20:00Z") */
export function isoNow(offsetSec = 0): string {
  return toIso(Date.now() + offsetSec * 1000);
}

export function toIso(ms: number): string {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
}
