import { Errors } from './errors.js';
import type { Place } from './types.js';

export function asObject(body: unknown): Record<string, unknown> {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) throw Errors.validation('JSON 객체가 필요해요');
  return body as Record<string, unknown>;
}

export function reqString(o: Record<string, unknown>, key: string): string {
  const v = o[key];
  if (typeof v !== 'string' || v.trim() === '') throw Errors.validation(`${key}가 필요해요`);
  return v.trim();
}

export function optBoolean(o: Record<string, unknown>, key: string): boolean | null {
  const v = o[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'boolean') throw Errors.validation(`${key}는 true/false여야 해요`);
  return v;
}

/** 명세 2절 Place. null 허용 여부는 호출하는 쪽이 정한다. */
export function placeOrNull(v: unknown, key: string): Place | null {
  if (v === undefined || v === null) return null;
  if (typeof v !== 'object' || Array.isArray(v)) throw Errors.validation(`${key} 형식이 틀렸어요`);
  const o = v as Record<string, unknown>;
  const s = (k: string) => {
    if (typeof o[k] !== 'string') throw Errors.validation(`${key}.${k}가 필요해요`);
    return o[k] as string;
  };
  const n = (k: string) => {
    if (typeof o[k] !== 'number' || !Number.isFinite(o[k])) throw Errors.validation(`${key}.${k}는 숫자여야 해요`);
    return o[k] as number;
  };
  // 명세 v1.3 6절: Place에서 null이 될 수 있는 필드는 없다(distanceKm 포함). 빠지거나 null이면 거절한다.
  return {
    placeId: s('placeId'),
    name: s('name'),
    address: s('address'),
    lat: n('lat'),
    lng: n('lng'),
    distanceKm: n('distanceKm'),
  };
}
