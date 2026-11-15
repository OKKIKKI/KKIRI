import { useEffect, useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import type { EndReason, Summary } from '../api';
import { Toast } from '../components/Toast';
import { clearGuestAuth, loadUserToken } from '../state/tokens';
import { S } from '../strings';

// HB-S-WEB-04 오늘의 주행 요약 (HB-F-SES-07, 1차 목업) — 경로 /s/{sessionId}/summary
// session.ended의 summary로만 그린다(명세 4.3). 세션 화면(SessionLayout)이 이동하면서 location.state로 넘긴다.
// 새로고침해도 브라우저 이동 기록에 state가 남는다. 주소로 바로 들어오면 state가 없다.
// 승인 대기(Q-WEB-020): 목록 잇기(", "), 빈 목록(줄 생략), 직접 접속(안내만), 운전자 버튼 노출 기준.

interface SummaryState {
  ended: { reason: EndReason; summary: Summary };
  sessionName: string | null;
  role: string | null;
}

function readState(raw: unknown): SummaryState | null {
  const v = raw as Partial<SummaryState> | null;
  const sum = v?.ended?.summary;
  if (!sum || !Array.isArray(sum.places) || !Array.isArray(sum.cars) || typeof sum.trackCount !== 'number') return null;
  return { ended: v!.ended!, sessionName: v!.sessionName ?? null, role: v!.role ?? null };
}

// ③·④ "로그인한 운전자에게만". 기준이 문서에 없어 Q-WEB-020 승인 전까지 그리지 않는다.
// 제안안: userToken이 있고 이 세션에서 내 역할이 LEADER.
const DRIVER_BUTTONS_APPROVED = false;

export default function SummaryPage() {
  const { sessionId = '' } = useParams();
  const location = useLocation();
  const data = readState(location.state);
  const [toast, setToast] = useState<string | null>(null);

  // 이 화면에 들어오면 저장된 guestToken을 지운다(WEB-04).
  useEffect(() => {
    clearGuestAuth(sessionId);
  }, [sessionId]);

  const isDriver = DRIVER_BUTTONS_APPROVED && !!loadUserToken() && data?.role === 'LEADER';
  const s = data?.ended.summary;

  return (
    <main className="page">
      {/* ① 요약 */}
      {s && (
        <section className="card">
          {data.sessionName && <h1 className="title">{data.sessionName}</h1>}
          {s.places.length > 0 && <p style={{ margin: 0 }}>{S.summary.places(s.places.join(', '))}</p>}
          {s.cars.length > 0 && <p style={{ margin: 0 }}>{S.summary.cars(s.cars.join(', '))}</p>}
          <p style={{ margin: 0 }}>{S.summary.tracks(s.trackCount)}</p>
        </section>
      )}

      {/* ② 비회원 안내 */}
      <p className="notice">{S.summary.guestNotice}</p>

      {/* ③·④ 로그인한 운전자에게만 */}
      {isDriver && (
        <>
          <button type="button" className="btn btn--primary" onClick={() => setToast(S.summary.preparing)}>
            {S.summary.startAgain}
          </button>
          <button type="button" className="btn btn--secondary" onClick={() => setToast(S.summary.preparing)}>
            {S.summary.keepSession}
          </button>
        </>
      )}

      {toast && <Toast text={toast} onDone={() => setToast(null)} />}
    </main>
  );
}
