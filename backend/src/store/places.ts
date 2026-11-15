import { readFileSync } from 'node:fs';
import type { Place } from '../types.js';

/**
 * R-30 장소 검색 목업(NAV-09, D-18): backend/seed/places.json 고정 목록에서 이름 포함 검색.
 * src/store/ 와 dist/store/ 어디서 실행해도 ../../seed 가 같은 위치다.
 */
const places: Place[] = JSON.parse(readFileSync(new URL('../../seed/places.json', import.meta.url), 'utf-8'));

export function searchPlaces(query: string | null): Place[] {
  const q = (query ?? '').trim().toLowerCase();
  if (q === '') return places;
  return places.filter((p) => p.name.toLowerCase().includes(q));
}
