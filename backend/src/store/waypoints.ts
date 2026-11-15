import { config } from '../config.js';
import type { Place, Proposal, Waypoint } from '../types.js';
import { isoNow, newId } from '../util.js';
import { emit } from '../ws/hub.js';
import type { SessionRecord } from './sessions.js';

type Ref = { participantId: string; name: string };

function record(rec: SessionRecord, carId: string, action: 'ADDED' | 'REMOVED', waypoint: Waypoint): void {
  const h = rec.waypointHistory.get(carId) ?? [];
  h.push({ action, waypoint });
  rec.waypointHistory.set(carId, h);
}

/** 경유지 추가 + waypoint.added. byUndo=false인 경우만 되돌리기 기록에 남긴다. */
export function addWaypoint(rec: SessionRecord, carId: string, place: Place, addedBy: Ref): Waypoint {
  const wp: Waypoint = { waypointId: newId('wpt_'), carId, place, addedBy, createdAt: isoNow() };
  rec.waypoints.push(wp);
  record(rec, carId, 'ADDED', wp);
  emit(rec, 'waypoint.added', { waypoint: wp, byUndo: false });
  return wp;
}

export function removeWaypoint(rec: SessionRecord, wp: Waypoint): void {
  rec.waypoints = rec.waypoints.filter((w) => w.waypointId !== wp.waypointId);
  record(rec, wp.carId, 'REMOVED', wp);
  emit(rec, 'waypoint.removed', { waypointId: wp.waypointId, carId: wp.carId, byUndo: false });
}

/** R-33: 이 차의 가장 최근 변경 1건을 되돌린다. 없으면 null. */
export function undoLast(rec: SessionRecord, carId: string): { action: 'ADDED' | 'REMOVED'; waypoint: Waypoint } | null {
  const h = rec.waypointHistory.get(carId);
  const last = h?.pop();
  if (!last) return null;
  if (last.action === 'ADDED') {
    rec.waypoints = rec.waypoints.filter((w) => w.waypointId !== last.waypoint.waypointId);
    emit(rec, 'waypoint.removed', { waypointId: last.waypoint.waypointId, carId, byUndo: true });
  } else {
    rec.waypoints.push(last.waypoint);
    emit(rec, 'waypoint.added', { waypoint: last.waypoint, byUndo: true });
  }
  return last;
}

/** 다른 차들에 제안(NAV-08). 다른 차가 없으면 null. PROPOSAL_TIMEOUT_SEC 뒤 남은 PENDING은 EXPIRED. */
export function createProposal(rec: SessionRecord, fromCarId: string, place: Place, proposedBy: Ref): Proposal | null {
  const others = rec.cars.map((c) => c.carId).filter((id) => id !== fromCarId);
  if (others.length === 0) return null;
  const proposal: Proposal = {
    proposalId: newId('prp_'),
    place,
    fromCarId,
    proposedBy,
    responses: others.map((carId) => ({ carId, status: 'PENDING' })),
    expiresAt: isoNow(config.proposalTimeoutSec),
  };
  rec.proposals.push(proposal);
  emit(rec, 'waypoint.proposed', { proposal });

  const timer = setTimeout(() => {
    rec.timers.delete(timer);
    if (rec.state === 'ENDED') return;
    for (const r of proposal.responses) {
      if (r.status !== 'PENDING') continue;
      r.status = 'EXPIRED';
      emit(rec, 'waypoint.proposal_resolved', { proposalId: proposal.proposalId, carId: r.carId, status: 'EXPIRED' });
    }
  }, config.proposalTimeoutSec * 1000);
  rec.timers.add(timer);
  return proposal;
}

/** R-34 응답 처리. 수락이면 그 차 경유지를 추가해 돌려준다. 호출 전에 PENDING 여부를 확인할 것. */
export function respondProposal(rec: SessionRecord, proposal: Proposal, carId: string, accept: boolean): Waypoint | null {
  const r = proposal.responses.find((x) => x.carId === carId)!;
  r.status = accept ? 'ACCEPTED' : 'DECLINED';
  emit(rec, 'waypoint.proposal_resolved', { proposalId: proposal.proposalId, carId, status: r.status });
  return accept ? addWaypoint(rec, carId, proposal.place, proposal.proposedBy) : null;
}
