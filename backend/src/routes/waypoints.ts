import type { IncomingMessage } from 'node:http';
import { Errors } from '../errors.js';
import { readJson, sendJson, sendNoContent, type Router } from '../http.js';
import { searchPlaces } from '../store/places.js';
import type { SessionRecord } from '../store/sessions.js';
import { addWaypoint, createProposal, removeWaypoint, respondProposal, undoLast } from '../store/waypoints.js';
import { config } from '../config.js';
import { asObject, optBoolean, placeOrNull, reqString } from '../validate.js';
import { carMember, member } from './common.js';
import { touchActivity } from '../store/lifecycle.js';

/** R-30~R-32는 "참가자"(웹, guestToken)만 부른다(명세 3.4 행위자). */
function participantMember(req: IncomingMessage, sessionId: string) {
  const { rec, you } = member(req, sessionId);
  if (you.actorType !== 'PARTICIPANT' || !you.participantId) throw Errors.forbidden();
  const par = rec.participants.find((p) => p.participantId === you.participantId)!;
  return { rec, carId: you.carId, ref: { participantId: par.participantId, name: par.name } };
}

function findProposal(rec: SessionRecord, proposalId: string) {
  const p = rec.proposals.find((x) => x.proposalId === proposalId);
  if (!p) throw Errors.notFound('제안이 없어요');
  return p;
}

export function waypointRoutes(r: Router): void {
  /** R-30 장소 검색 (NAV-09, 목업: seed/places.json에서 이름 포함 검색) */
  r.add('GET', '/v1/sessions/:sessionId/places', ({ req, res, params, query }) => {
    participantMember(req, params.sessionId);
    sendJson(res, 200, { places: searchPlaces(query.get('query')) });
  });

  /** R-31 경유지 추가 (NAV-02, NAV-08, ACC-04). RESERVED에서도 가능(탑승 전 담기). */
  r.add('POST', '/v1/sessions/:sessionId/waypoints', async ({ req, res, params }) => {
    const { rec, carId, ref } = participantMember(req, params.sessionId);
    const body = asObject(await readJson(req));
    const place = placeOrNull(body.place, 'place');
    if (!place) throw Errors.validation('place가 필요해요');
    const scope = reqString(body, 'scope');
    if (scope !== 'MY_CAR' && scope !== 'PROPOSE_OTHERS') throw Errors.validation('scope는 MY_CAR 또는 PROPOSE_OTHERS예요');

    // 시연 방침(2026-11-15): 기본은 수락 없이 세션의 모든 차에 바로 추가(WAYPOINT_TO_ALL_CARS, 기본 켬).
    // 이때 scope는 검증만 하고 동작은 같다. 응답의 waypoint는 요청한 참가자 차의 경유지, proposal은 null.
    // WAYPOINT_TO_ALL_CARS=0이면 예전 명세 동작(MY_CAR / PROPOSE_OTHERS 제안).
    const waypoint = addWaypoint(rec, carId, place, ref);
    let proposal = null;
    if (config.waypointToAllCars) {
      for (const c of rec.cars) if (c.carId !== carId) addWaypoint(rec, c.carId, place, ref);
    } else if (scope === 'PROPOSE_OTHERS') {
      proposal = createProposal(rec, carId, place, ref);
    }
    touchActivity(rec); // Q-006 입력(경유지)
    sendJson(res, 200, { waypoint, proposal });
  });

  /** R-32 경유지 삭제. 같은 차 참가자만. 응답 본문 없음(204). */
  r.add('DELETE', '/v1/sessions/:sessionId/waypoints/:waypointId', ({ req, res, params }) => {
    const { rec, carId } = participantMember(req, params.sessionId);
    const wp = rec.waypoints.find((w) => w.waypointId === params.waypointId);
    if (!wp) throw Errors.notFound('경유지가 없어요');
    if (wp.carId !== carId) throw Errors.forbidden();
    removeWaypoint(rec, wp);
    touchActivity(rec); // Q-006 입력(경유지)
    sendNoContent(res);
  });

  /** R-33 음성 되돌리기 (NAV-03). 호출한 차의 가장 최근 경유지 변경 1건. */
  r.add('POST', '/v1/sessions/:sessionId/waypoints/undo', ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const undone = undoLast(rec, carId);
    if (!undone) throw Errors.nothingToUndo();
    touchActivity(rec); // Q-006 입력(경유지)
    sendJson(res, 200, { undone });
  });

  /** R-34 제안 수락·거절 (NAV-08). 제안받은 차만, PENDING일 때만. */
  r.add('POST', '/v1/sessions/:sessionId/proposals/:proposalId/respond', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const body = asObject(await readJson(req));
    const accept = optBoolean(body, 'accept');
    if (accept === null) throw Errors.validation('accept가 필요해요');
    const proposal = findProposal(rec, params.proposalId);
    const mine = proposal.responses.find((x) => x.carId === carId);
    if (!mine) throw Errors.forbidden();
    if (mine.status !== 'PENDING') throw Errors.proposalClosed();
    const waypoint = respondProposal(rec, proposal, carId, accept);
    touchActivity(rec); // Q-006 입력(경유지)
    sendJson(res, 200, { proposal, waypoint });
  });
}
