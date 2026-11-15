import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { api, ApiError, type FriendCar, type MeResponse, type MyCar } from '../api';
import { Toast } from '../components/Toast';
import { SessionProvider, useSession } from '../state/SessionContext';
import {
  clearMySessionId,
  clearUserToken,
  loadGuestAuth,
  loadMySessionId,
  loadUserToken,
  saveGuestAuth,
  saveMySessionId,
  saveUserToken,
  type GuestAuth,
} from '../state/tokens';
import { S } from '../strings';

// HB-S-WEB-07 운전자 홈 `/me` — ACC-01 로그인(R-10·R-12), ACC-02 내 차(R-13, 목업), ACC-03 세션 미리 열기(R-14 → R-06),
// ④ QR 크게 보기, ⑤ 사전 입장 현황(WebSocket), ACC-05 친구 차량(R-15, 목업), [세션으로 가기]
// userToken은 세션 밖 호출에만 쓴다. 미리 연 세션 안에서는 R-06으로 받은 guestToken(LEADER)을 쓴다(명세 1.3).

/** R-11이 돌려보낸 `/me#userToken=...`에서 토큰을 꺼내 저장하고 주소에서 지운다. */
function takeTokenFromHash(): string | null {
  const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
  const token = new URLSearchParams(hash).get('userToken');
  if (!token) return null;
  saveUserToken(token);
  window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
  return token;
}

/** qr.url = `{KKIRI_WEB_BASE}/j/{qrToken}` (명세 7절) */
function qrTokenOf(qrUrl: string): string | null {
  try {
    const m = new URL(qrUrl).pathname.match(/\/j\/([^/]+)\/?$/);
    return m ? decodeURIComponent(m[1]) : null;
  } catch {
    return null;
  }
}

interface MySession {
  sessionId: string;
  auth: GuestAuth;
}

function loadMySession(): MySession | null {
  const sessionId = loadMySessionId();
  const auth = sessionId ? loadGuestAuth(sessionId) : null;
  return sessionId && auth ? { sessionId, auth } : null;
}

// ④ ⑤ 미리 연 세션 (WebSocket으로 갱신)
function ReservedPanel({ onGone }: { onGone(): void }) {
  const { session, you, ended, stopReason, sessionId } = useSession();
  const gone = ended !== null || stopReason !== null;

  useEffect(() => {
    if (gone) onGone();
  }, [gone, onGone]);

  if (!session || gone) return null;
  // 리더 본인은 빼고 센다(팀원 결정 2026-11-11)
  const preJoined = session.participants.filter((p) => p.participantId !== you?.participantId).length;

  return (
    <section className="card qr-card">
      <h2 className="section-title">{session.name}</h2>
      {session.state === 'ACTIVE' && (
        <div className="banner" role="status">
          {S.me.carAttached}
        </div>
      )}
      <div className="qr">
        <QRCodeSVG value={session.qr.url} size={240} level="M" marginSize={2} title={session.qr.url} />
      </div>
      <p className="qr-hint">{S.me.qrHint}</p>
      <p className="muted" style={{ margin: 0 }}>
        {S.me.preJoin(preJoined, session.waypoints.length)}
      </p>
      <Link className="btn btn--primary" to={`/s/${encodeURIComponent(sessionId)}`}>
        {S.me.goSession}
      </Link>
    </section>
  );
}

export default function MePage() {
  const [userToken, setUserToken] = useState<string | null>(() => takeTokenFromHash() ?? loadUserToken());
  const [me, setMe] = useState<MeResponse | null>(null);
  const [cars, setCars] = useState<MyCar[]>([]);
  const [friends, setFriends] = useState<FriendCar[]>([]);
  const [loadError, setLoadError] = useState(false);
  const [mySession, setMySession] = useState<MySession | null>(loadMySession);
  const [sessionName, setSessionName] = useState('');
  const [reserving, setReserving] = useState(false);
  const [reserveError, setReserveError] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  // 같은 페이지에서 주소의 #userToken만 바뀌는 경우(페이지를 다시 불러오지 않음)도 받는다.
  useEffect(() => {
    const onHash = () => {
      const t = takeTokenFromHash();
      if (t) setUserToken(t);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // 로그인 상태면 R-12·R-13·R-15
  useEffect(() => {
    if (!userToken) return;
    let cancelled = false;
    (async () => {
      try {
        const [meRes, carsRes, friendsRes] = await Promise.all([
          api.getMe(userToken),
          api.getMyCars(userToken),
          api.getFriendCars(userToken),
        ]);
        if (cancelled) return;
        setMe(meRes);
        setCars(carsRes.cars);
        setFriends(friendsRes.friendCars);
        setLoadError(false);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError && e.code === 'UNAUTHORIZED') {
          clearUserToken(); // 만료·무효 토큰 → 다시 로그인
          setUserToken(null);
        } else {
          setLoadError(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [userToken]);

  const onLogin = () => {
    window.location.href = api.kakaoLoginUrl(`${window.location.origin}/me`);
  };

  // ③ 세션 미리 열기: R-14 → R-06(userToken 헤더, 자기 자신 입장 → LEADER)
  const onReserve = async (e: FormEvent) => {
    e.preventDefault();
    const name = sessionName.trim();
    const car = cars[0];
    if (!userToken || !me || !car || !name || reserving) return;
    setReserving(true);
    setReserveError(false);
    try {
      const { session } = await api.reserveSession(userToken, { carId: car.carId, name, destination: null });
      const qrToken = qrTokenOf(session.qr.url);
      if (!qrToken) throw new Error(`qr.url 형식이 명세 7절과 다름: ${session.qr.url}`);
      const joined = await api.joinSession({ qrToken, name: me.name }, userToken);
      const auth = { guestToken: joined.guestToken, participantId: joined.participantId };
      saveGuestAuth(joined.session.sessionId, auth);
      saveMySessionId(joined.session.sessionId);
      setMySession({ sessionId: joined.session.sessionId, auth });
      setSessionName('');
    } catch (err) {
      console.warn('[me] 세션 미리 열기 실패', err);
      setReserveError(true);
    } finally {
      setReserving(false);
    }
  };

  const onSessionGone = () => {
    clearMySessionId();
    setMySession(null);
  };

  // ① 미로그인
  if (!userToken) {
    return (
      <main className="page page--center">
        <button type="button" className="btn btn--primary" onClick={onLogin}>
          {S.me.login}
        </button>
      </main>
    );
  }

  return (
    <main className="page">
      {/* ① 로그인 */}
      <h1 className="title">{me ? S.me.hello(me.name) : ''}</h1>
      {loadError && <p className="error">{S.error.retry}</p>}

      {/* ② 내 차 (목업: 서버 시드) */}
      {cars.map((c) => (
        <p key={c.carId} style={{ margin: 0 }}>
          {S.me.myCar(c.displayName)}
        </p>
      ))}

      {/* ③ 세션 미리 열기 / ④ ⑤ 미리 연 세션 */}
      {mySession ? (
        <SessionProvider key={mySession.sessionId} sessionId={mySession.sessionId} auth={mySession.auth}>
          <ReservedPanel onGone={onSessionGone} />
        </SessionProvider>
      ) : (
        <form className="card" onSubmit={onReserve}>
          <label className="field">
            <span className="label">{S.me.sessionName}</span>
            <input
              className="input"
              value={sessionName}
              onChange={(e) => setSessionName(e.target.value)}
              autoComplete="off"
            />
          </label>
          {reserveError && (
            <p className="error" role="alert">
              {S.error.retry}
            </p>
          )}
          <button
            type="submit"
            className="btn btn--primary"
            disabled={!me || cars.length === 0 || !sessionName.trim() || reserving}
          >
            {S.me.reserve}
          </button>
        </form>
      )}

      {/* ⑥ 친구 차량 (목업: 고정 목록, 초대 API 없음) */}
      {friends.length > 0 && (
        <section className="card">
          <h2 className="section-title">{S.me.friends}</h2>
          <ul className="list">
            {friends.map((f) => (
              <li key={f.carId} className="row row--center">
                <span>{S.me.friendLine(f.displayName, f.ownerName)}</span>
                <button type="button" className="btn btn--small" onClick={() => setToast(S.me.preparing)}>
                  {S.me.invite}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {toast && <Toast text={toast} onDone={() => setToast(null)} />}
    </main>
  );
}
