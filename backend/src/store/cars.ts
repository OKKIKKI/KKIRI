import { issueToken } from '../auth.js';
import { newId } from '../util.js';

/** 등록된 차량(R-01). 세션과 무관하게 남는다. 메모리 저장(D-02). */
export interface CarRecord {
  carId: string;
  deviceId: string;
  displayName: string;
  driverName: string;
  carToken: string;
}

const byId = new Map<string, CarRecord>();
const byDevice = new Map<string, string>();

/**
 * 같은 deviceId면 같은 carId·carToken을 돌려준다. 이름은 최신 값으로 갱신한다(D-19 설정값 변경 대비).
 * 통합 테스트용 로그: 등록마다 deviceId → carId를 남기고(시드 deviceIds와 맞춰 보기용),
 * 같은 deviceId로 **다른 차 이름**이 오면 경고한다. 차량·에뮬레이터 두 대가 같은 KKIRI_DEVICE_ID(예: 기본값 dev-0001)를 쓰면
 * 서버가 두 대를 한 차로 합쳐 버리기 때문이다(명세 v1.7: 차량마다 다르게). 응답은 바뀌지 않는다.
 */
export function registerCar(deviceId: string, displayName: string, driverName: string): CarRecord {
  const existingId = byDevice.get(deviceId);
  if (existingId) {
    const rec = byId.get(existingId)!;
    if (rec.displayName !== displayName || rec.driverName !== driverName) {
      console.warn(
        `[car] ⚠ 같은 deviceId(${deviceId})로 다른 차가 등록됨: "${rec.displayName}/${rec.driverName}" → "${displayName}/${driverName}". ` +
          `두 차가 같은 KKIRI_DEVICE_ID를 쓰면 서버가 한 차(${rec.carId})로 합칩니다. 차마다 -Pkkiri.deviceId를 다르게 빌드하세요.`,
      );
    } else {
      console.log(`[car] R-01 다시 등록 deviceId=${deviceId} → ${rec.carId} (같은 차)`);
    }
    rec.displayName = displayName;
    rec.driverName = driverName;
    return rec;
  }
  const carId = newId('car_');
  const rec: CarRecord = { carId, deviceId, displayName, driverName, carToken: issueToken({ kind: 'CAR', carId }) };
  byId.set(carId, rec);
  byDevice.set(deviceId, carId);
  console.log(`[car] R-01 새 차 deviceId=${deviceId} → ${carId} (${displayName}/${driverName})`);
  return rec;
}

/** 이 기기로 R-01 등록된 차. 아직 등록 안 됐으면 undefined (R-13·R-14 시드 연결, 명세 v1.5) */
export function getCarByDevice(deviceId: string): CarRecord | undefined {
  const carId = byDevice.get(deviceId);
  return carId ? byId.get(carId) : undefined;
}

export function getCar(carId: string): CarRecord | undefined {
  return byId.get(carId);
}
