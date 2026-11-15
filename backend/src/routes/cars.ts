import { readJson, sendJson, type Router } from '../http.js';
import { registerCar } from '../store/cars.js';
import { asObject, reqString } from '../validate.js';

export function carRoutes(r: Router): void {
  /** R-01 차량 등록. 토큰 없이 호출. */
  r.add('POST', '/v1/cars/register', async ({ req, res }) => {
    const body = asObject(await readJson(req));
    const car = registerCar(reqString(body, 'deviceId'), reqString(body, 'displayName'), reqString(body, 'driverName'));
    sendJson(res, 200, { carId: car.carId, carToken: car.carToken });
  });
}
