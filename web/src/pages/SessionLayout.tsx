import { useEffect } from 'react';
import { Outlet, useNavigate, useParams } from 'react-router-dom';
import { SessionProvider, useSession } from '../state/SessionContext';
import { loadGuestAuth } from '../state/tokens';
import { S } from '../strings';

// `/s/{sessionId}` 아래 화면(세션 홈·장소 검색·Spotify 연결)이 WebSocket 연결 하나를 같이 쓴다.
// 요약 화면(WEB-04)은 연결이 필요 없어 이 레이아웃 밖에 둔다.

function Message({ text }: { text: string }) {
  return (
    <main className="page page--center">
      <p className="title">{text}</p>
    </main>
  );
}

function SessionGate() {
  const { session, you, ended, stopReason, sessionId } = useSession();
  const navigate = useNavigate();

  // session.ended 수신 → 오늘의 주행 요약(명세 4.3, 7절)
  useEffect(() => {
    if (ended) {
      navigate(`/s/${encodeURIComponent(sessionId)}/summary`, {
        replace: true,
        state: { ended, sessionName: session?.name ?? null, role: you?.role ?? null },
      });
    }
  }, [ended, navigate, sessionId, session?.name, you?.role]);

  if (stopReason === 'ended') return <Message text={S.error.SESSION_ENDED} />;
  if (stopReason === 'notFound') return <Message text={S.error.SESSION_NOT_FOUND} />;
  if (!session) return null; // 첫 스냅샷 전
  return <Outlet />;
}

export default function SessionLayout() {
  const { sessionId = '' } = useParams();
  const auth = loadGuestAuth(sessionId);
  if (!auth) return <Message text={S.error.SESSION_NOT_FOUND} />;

  return (
    <SessionProvider key={sessionId} sessionId={sessionId} auth={auth}>
      <SessionGate />
    </SessionProvider>
  );
}
