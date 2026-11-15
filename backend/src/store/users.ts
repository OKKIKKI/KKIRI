import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { getCarByDevice } from './cars.js';

/** 계정(운전자). 1차는 seed/users.json 시드만 쓴다(D-10 확정 전 개발용 로그인). 계정 정보는 세션 종료 후에도 남는다. */
export interface User {
  userId: string;
  name: string;
  /**
   * 이 계정에 등록된 차의 기기 ID(R-01의 deviceId = 차량 앱 KKIRI_DEVICE_ID, 명세 v1.7). R-13·R-14가 이 값으로 차를 찾는다.
   * 지금 값: dev-0001(개발 기본값) + demo-car-a(docs/06_INTEGRATION_TEST.md 5절의 시연 A차 예시).
   * ⚠ 시연 차량 값이 다르게 정해지면 seed/users.json에 그 값을 추가한다(코드 변경 없음, 서버 재시작).
   *   절차: backend/README.md "시연 기기 deviceId 넣기"
   */
  deviceIds: string[];
  /**
   * 카카오 회원번호(사용자 정보 API의 id, 문자열로 보관). 시드의 선택 필드(D-23).
   * 이 값과 같은 카카오 계정으로 로그인하면 이 사용자가 된다. 첫 카카오 로그인 때 서버 로그에 찍힌 값을 넣는다.
   */
  kakaoId?: string;
}

const users: User[] = (JSON.parse(readFileSync(new URL('../../seed/users.json', import.meta.url), 'utf-8')) as User[])
  .map((u) => ({
    ...u,
    deviceIds: Array.isArray(u.deviceIds) ? u.deviceIds : [],
    kakaoId: u.kakaoId === undefined || u.kakaoId === null ? undefined : String(u.kakaoId),
  }));

/** 개발용 deviceId. 시드에 이것만 있으면 서버 시작 때 경고한다(시연 기기 값 누락 방지). */
export const DEV_DEVICE_IDS = ['dev-0001'];

/**
 * 서버 시작 때 찍는 시드 안내. 차량 앱 KKIRI_DEVICE_ID와 같은지 눈으로 맞춰 보라고 항상 목록을 보여 주고,
 * 개발용 값뿐이면 경고를 덧붙인다.
 */
export function seedDeviceWarnings(): string[] {
  const lines = users.map((u) => `[seed] ${u.userId}(${u.name}) 내 차 deviceIds: ${u.deviceIds.join(', ') || '없음'} — 차량 앱 KKIRI_DEVICE_ID와 같아야 R-13·R-14가 동작`);
  for (const u of users.filter((x) => x.deviceIds.every((d) => DEV_DEVICE_IDS.includes(d)))) {
    lines.push(`[seed] ⚠ ${u.userId}(${u.name})의 deviceIds가 개발용 값(${u.deviceIds.join(', ') || '없음'})뿐이에요. 시연 전에 시연 차량 deviceId를 backend/seed/users.json에 추가하세요(README "시연 기기 deviceId 넣기").`);
  }
  return lines;
}
const byId = new Map(users.map((u) => [u.userId, u]));
const seedIds = new Set(users.map((u) => u.userId));

export function getUser(userId: string): User | undefined {
  return byId.get(userId);
}

/**
 * 카카오 로그인한 사용자 찾기/만들기 (D-23, 명세 v1.8 R-10/11).
 * 시드(또는 이미 만든 계정) 중 kakaoId가 같으면 그 사용자. 없으면 차 없는 새 usr_ 계정을 메모리에 만든다
 * (서버 재시작 시 사라짐, 영구 저장·가입 흐름 없음). seeded: 시드 사용자와 이어졌는지.
 */
export function userForKakao(kakaoId: string, nickname: string | null): { user: User; seeded: boolean; created: boolean } {
  const found = users.find((u) => u.kakaoId === kakaoId);
  if (found) return { user: found, seeded: seedIds.has(found.userId), created: false };
  const user: User = { userId: `usr_k${randomBytes(5).toString('hex')}`, name: nickname ?? '카카오 사용자', deviceIds: [], kakaoId };
  users.push(user);
  byId.set(user.userId, user);
  return { user, seeded: false, created: true };
}

/**
 * R-15 친구 차량 (ACC-05, 1차 목업): seed/friend-cars.json의 userId별 고정 목록.
 * carId는 표시용 고정 값이다(실제 등록 차량과 연결하지 않는다. 초대 API는 1차에 없음).
 */
export interface FriendCar {
  carId: string;
  displayName: string;
  ownerName: string;
}

const friendCars: Record<string, FriendCar[]> = JSON.parse(
  readFileSync(new URL('../../seed/friend-cars.json', import.meta.url), 'utf-8'),
);

export function friendCarsOf(userId: string): FriendCar[] {
  return friendCars[userId] ?? [];
}

/** 개발용 로그인(DEV_LOGIN=1)이 로그인시키는 사용자: 시드의 첫 사용자 */
export function devLoginUser(): User {
  if (users.length === 0) throw new Error('seed/users.json에 사용자가 없습니다');
  return users[0];
}

/** R-13 내 차: 시드 deviceIds 중 R-01로 등록된 기기의 차만(미등록 기기는 빠짐, 명세 v1.5). */
export function myCars(userId: string): { carId: string; displayName: string }[] {
  const u = byId.get(userId);
  if (!u) return [];
  const out: { carId: string; displayName: string }[] = [];
  for (const d of u.deviceIds) {
    const car = getCarByDevice(d);
    if (car && !out.some((c) => c.carId === car.carId)) out.push({ carId: car.carId, displayName: car.displayName });
  }
  return out;
}
