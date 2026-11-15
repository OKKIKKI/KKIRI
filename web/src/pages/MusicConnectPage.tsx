import { useEffect, useState } from 'react';
import { api } from '../api';
import { useSession } from '../state/SessionContext';
import { S } from '../strings';

// HB-S-WEB-06 Spotify 연결 `/s/{sessionId}/music/connect` — MUS-01
// ② [Spotify로 연결] → R-40으로 이동 → (Spotify 로그인 → R-41) → 이 주소로 돌아오면 R-42로 상태 확인
// R-40은 쿼리 token=으로 guestToken을 싣는다(명세 v1.5). D-09 OPEN → Spotify는 BE 목업(바로 연결 후 돌아옴).
// [Spotify로 연결]은 호스트 차 소속 참가자에게만 보인다(03_SCREENS WEB-05, Q-WEB-019 승인).

export default function MusicConnectPage() {
  const { session, you, sessionId, auth } = useSession();
  const [checked, setChecked] = useState<boolean | null>(null); // R-42로 확인한 connected

  // 들어올 때(= R-40에서 돌아왔을 때 포함) R-42로 상태 확인
  useEffect(() => {
    let cancelled = false;
    api
      .getMusic(sessionId, auth.guestToken)
      .then((res) => {
        if (!cancelled) setChecked(res.music.connected);
      })
      .catch((e) => console.warn('[music] R-42 실패', e));
    return () => {
      cancelled = true;
    };
  }, [sessionId, auth.guestToken]);

  if (!session) return null;
  // 이벤트(music.now_playing)로 바뀐 스냅샷 값과 R-42 결과 중 하나라도 연결이면 연결됨
  const connected = session.music.connected || checked === true;
  const isHostCar = you?.carId === session.hostCarId;

  const onConnect = () => {
    const back = `${window.location.origin}/s/${encodeURIComponent(sessionId)}/music/connect`;
    window.location.href = api.spotifyConnectUrl(sessionId, auth.guestToken, back);
  };

  return (
    <main className="page">
      {/* ① 안내 */}
      <h1 className="title">{S.connect.intro}</h1>

      {/* ④ 연결 상태 */}
      <p className="status-line">{connected ? S.connect.connected : S.connect.notConnected}</p>

      {/* ③ 조건 안내 */}
      <p className="muted" style={{ margin: 0 }}>
        {S.connect.conditions}
      </p>

      {/* ② Spotify로 연결 — 호스트 차 참가자에게만 */}
      {isHostCar && (
        <button type="button" className="btn btn--primary" onClick={onConnect}>
          {S.connect.button}
        </button>
      )}
    </main>
  );
}
