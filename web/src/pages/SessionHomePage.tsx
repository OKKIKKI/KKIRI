import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import MessagePanel from '../components/MessagePanel';
import MusicTab from '../components/MusicTab';
import { Toast, useToast } from '../components/Toast';
import { useSession } from '../state/SessionContext';
import { S } from '../strings';

// HB-S-WEB-02 세션 홈 `/s/{sessionId}` — session.snapshot과 이벤트로만 그린다.
// ① 상단·상태 배너 ② 경로(목적지 + 내 차 경유지, 내가 넣은 것은 [삭제] R-32) ③ 경유지 추가 이동
// ④ 일행 차량·음소거 표시 ⑤ 메시지(R-20) ⑥ 내가 보낸 제안 상태 ⑦ 하단 탭(세션 / 음악 = WEB-05, 항상 보임)
// 2차(그리지 않음): 누르고 말하기, 탑승 차 바꾸기, 리더 메뉴

type Tab = 'session' | 'music';

export default function SessionHomePage() {
  const { session, you, sessionId, auth } = useSession();
  const [toast, setToast] = useToast();
  const [removing, setRemoving] = useState<ReadonlySet<string>>(new Set());
  const [tab, setTab] = useState<Tab>('session');
  if (!session) return null;

  const myCarId = you?.carId ?? null;
  const myParticipantId = you?.participantId ?? null;
  const myWaypoints = session.waypoints.filter((w) => w.carId === myCarId);
  const carName = (carId: string) => session.cars.find((c) => c.carId === carId)?.displayName ?? carId;
  // 내 차를 음소거한 차들(Mute: byCarId 차에서 targetCarId 차가 안 들림)
  const mutedBy = session.mutes.filter((m) => myCarId !== null && m.targetCarId === myCarId).map((m) => m.byCarId);
  // 내가 보낸 제안. 스냅샷에는 PENDING만 오고, 결과는 proposal_resolved로 응답 상태만 바뀐다.
  const myProposals = session.proposals.filter((p) => myParticipantId !== null && p.proposedBy.participantId === myParticipantId);

  const banner =
    session.state === 'RESERVED' ? S.home.bannerReserved : session.state === 'PAUSED' ? S.home.bannerPaused : null;

  const onRemove = async (waypointId: string) => {
    setRemoving((s) => new Set(s).add(waypointId));
    try {
      // 목록은 waypoint.removed 이벤트가 오면 바뀐다.
      await api.removeWaypoint(sessionId, auth.guestToken, waypointId);
    } catch {
      setToast(S.error.retry);
      setRemoving((s) => {
        const n = new Set(s);
        n.delete(waypointId);
        return n;
      });
    }
  };

  return (
    <main className="page page--tabs">
      {/* ① 상단 */}
      <header className="header">
        <h1 className="title">{session.name}</h1>
        <span className="muted">{S.home.people(session.participants.length)}</span>
      </header>
      {banner && (
        <div className="banner" role="status">
          {banner}
        </div>
      )}

      {tab === 'music' ? (
        <MusicTab />
      ) : (
      <>
      {/* ② 경로 */}
      <section className="card">
        <h2 className="section-title">{S.home.route}</h2>
        <p style={{ margin: 0 }}>
          {session.destination ? S.home.destination(session.destination.name) : S.home.noDestination}
        </p>
        {myWaypoints.length > 0 && (
          <ul className="list">
            {myWaypoints.map((w) => (
              <li key={w.waypointId} className="row row--center">
                <span>{w.place.name}</span>
                <span className="row__end">
                  <span className="muted">{w.addedBy.name}</span>
                  {w.addedBy.participantId === myParticipantId && (
                    <button
                      type="button"
                      className="btn btn--small"
                      disabled={removing.has(w.waypointId)}
                      onClick={() => onRemove(w.waypointId)}
                    >
                      {S.home.remove}
                    </button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ③ 경유지 추가 */}
      <Link className="btn btn--secondary" to="places">
        {S.home.addWaypoint}
      </Link>

      {/* ⑥ 내가 보낸 경유지 제안 상태 */}
      {myProposals.length > 0 && (
        <section className="card">
          <h2 className="section-title">{S.home.proposals}</h2>
          <ul className="list">
            {myProposals.map((p) => (
              <li key={p.proposalId}>
                <div>{p.place.name}</div>
                <div className="muted">
                  {p.responses
                    .map((r) => S.home.proposalLine(carName(r.carId), S.proposalStatus[r.status]))
                    .join(' · ')}
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ④ 일행 차량 */}
      <section className="card">
        <h2 className="section-title">{S.home.cars}</h2>
        <ul className="list">
          {session.cars.map((c) => (
            <li key={c.carId}>{S.home.carLine(c.displayName, c.driverName, S.carStatus[c.status])}</li>
          ))}
        </ul>
        {mutedBy.map((carId) => (
          <p key={carId} className="muted" style={{ margin: 0 }}>
            {S.home.mutedUs(carName(carId))}
          </p>
        ))}
      </section>

      {/* ⑤ 메시지 목록 + 입력 */}
      <MessagePanel />
      </>
      )}

      {/* ⑦ 하단 탭 */}
      <nav className="tabbar" role="tablist">
        {(['session', 'music'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            className={`tabbar__item ${tab === t ? 'is-on' : ''}`}
            onClick={() => setTab(t)}
          >
            {t === 'session' ? S.tabs.session : S.tabs.music}
          </button>
        ))}
      </nav>

      {toast && <Toast text={toast} onDone={() => setToast(null)} />}
    </main>
  );
}
