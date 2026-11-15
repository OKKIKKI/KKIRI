import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, type MusicAction, type Track } from '../api';
import { useSession } from '../state/SessionContext';
import { S } from '../strings';
import { Toast } from './Toast';

// HB-S-WEB-05 음악 탭 — MUS-02(R-43), MUS-03(session.music · music.now_playing), MUS-04(R-44·R-45), MUS-06(잠금 표시)
// 음악 상태는 스냅샷·이벤트로만 그린다. 폴링하지 않는다. 조작 성공 응답으로 화면을 미리 바꾸지 않는다.
// 03_SCREENS WEB-05 보충(Q-WEB-019 승인): 다른 사람이 넣은 곡 토스트, MUSIC_NOT_CONNECTED·MUSIC_LOCKED 문구,
// 검색칸 접근성 이름 "곡 검색", [연결하러 가기]는 호스트 차 참가자에게만.
// 2차(그리지 않음): 볼륨(MUS-07)

const SEARCH_DEBOUNCE_MS = 250; // 입력 중 요청을 줄이는 UI 값
const MAX_RESULTS = 10; // 명세 R-44 "최대 10개"

function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'CONTROL_RATE_LIMITED') return S.music.errRate;
    if (e.code === 'MUSIC_NO_ACTIVE_DEVICE') return S.music.errNoDevice;
    if (e.code === 'MUSIC_PREMIUM_REQUIRED') return S.music.errPremium;
    if (e.code === 'MUSIC_NOT_CONNECTED') return S.music.errNotConnected;
    if (e.code === 'MUSIC_LOCKED') return S.music.errLocked;
  }
  return S.error.retry;
}

export default function MusicTab() {
  const { session, you, sessionId, auth, lastQueueAdded } = useSession();
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState('');
  const [tracks, setTracks] = useState<Track[]>([]);
  const [queuing, setQueuing] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  // 내가 R-45로 넣은 곡 제목(이벤트가 오면 하나씩 지운다). queue_added에는 이름만 오고 id가 없어서
  // "내 이름 + 내가 넣은 제목"이면 내 곡으로 본다.
  const myQueued = useRef<string[]>([]);
  // 탭을 열기 전에 받은 이벤트로는 토스트를 띄우지 않는다.
  const seenSeq = useRef<number>(lastQueueAdded?.seq ?? 0);

  const music = session?.music;
  const connected = music?.connected ?? false;
  const myName = session?.participants.find((p) => p.participantId === you?.participantId)?.name ?? null;
  // R-40은 1차에 호스트 차 참가자만(명세 3.5, 03_SCREENS WEB-05)
  const isHostCar = !!session && you?.carId === session.hostCarId;

  // music.queue_added → 다른 사람이 넣은 곡이면 토스트(내가 넣은 곡은 ④ 토스트만)
  useEffect(() => {
    if (!lastQueueAdded || lastQueueAdded.seq <= seenSeq.current) return;
    seenSeq.current = lastQueueAdded.seq;
    const i = myQueued.current.indexOf(lastQueueAdded.title);
    if (lastQueueAdded.addedByName === myName && i >= 0) {
      myQueued.current.splice(i, 1);
      return;
    }
    setToast(S.music.queuedByOther(lastQueueAdded.addedByName, lastQueueAdded.title));
  }, [lastQueueAdded, myName]);

  // ③ 곡 검색(빈 검색어면 결과 없음)
  useEffect(() => {
    const q = query.trim();
    if (!connected || !q) {
      setTracks([]);
      return;
    }
    let cancelled = false;
    const t = setTimeout(async () => {
      try {
        const res = await api.searchTracks(sessionId, auth.guestToken, q);
        if (!cancelled) setTracks(res.tracks.slice(0, MAX_RESULTS));
      } catch (e) {
        if (!cancelled) setToast(errorText(e));
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [query, connected, sessionId, auth.guestToken]);

  if (!session || !music) return null;

  // 미연결 — [연결하러 가기]는 호스트 차 참가자에게만
  if (!connected) {
    return (
      <section className="card">
        <p style={{ margin: 0 }}>{S.music.notConnected}</p>
        {isHostCar && (
          <Link className="btn btn--primary" to="music/connect">
            {S.music.goConnect}
          </Link>
        )}
      </section>
    );
  }

  const locked = music.locked;
  const np = music.nowPlaying;

  const onControl = async (action: MusicAction) => {
    if (busy || locked) return;
    setBusy(true);
    try {
      await api.controlMusic(sessionId, auth.guestToken, action);
    } catch (e) {
      setToast(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const onQueue = async (t: Track) => {
    if (queuing || locked) return;
    setQueuing(t.uri);
    // 이벤트가 응답보다 먼저 올 수 있어 호출 전에 기록한다
    myQueued.current.push(t.title);
    try {
      await api.queueTrack(sessionId, auth.guestToken, { uri: t.uri, title: t.title, artist: t.artist });
      setToast(S.music.queued);
    } catch (e) {
      const i = myQueued.current.indexOf(t.title);
      if (i >= 0) myQueued.current.splice(i, 1);
      setToast(errorText(e));
    } finally {
      setQueuing(null);
    }
  };

  return (
    <>
      {/* ⑤ 잠금 안내 */}
      {locked && (
        <div className="banner" role="status">
          {S.music.locked}
        </div>
      )}

      {/* ① 지금 재생 */}
      <section className="card now-playing">
        {np ? (
          <>
            <div className="now-playing__title">{np.title}</div>
            <div className="muted">{np.artist}</div>
          </>
        ) : (
          <p style={{ margin: 0 }}>{S.music.nothing}</p>
        )}

        {/* ② 이전 / 일시정지·재생 / 다음 곡 */}
        <div className="controls">
          <button type="button" className="btn btn--secondary" disabled={busy || locked} onClick={() => onControl('PREVIOUS')}>
            {S.music.prev}
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy || locked}
            onClick={() => onControl(np?.isPlaying ? 'PAUSE' : 'PLAY')}
          >
            {np?.isPlaying ? S.music.pause : S.music.play}
          </button>
          <button type="button" className="btn btn--secondary" disabled={busy || locked} onClick={() => onControl('NEXT')}>
            {S.music.next}
          </button>
        </div>
      </section>

      {/* ③ 곡 검색 */}
      <input
        className="input"
        type="search"
        aria-label={S.music.searchLabel}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        autoComplete="off"
        enterKeyHint="search"
      />

      {/* ④ 결과 + [다음 곡으로] */}
      {tracks.length > 0 && (
        <ul className="list list--places">
          {tracks.map((t) => (
            <li key={t.uri} className="card track">
              <div className="track__info">
                <div>{t.title}</div>
                <div className="muted">{t.artist}</div>
              </div>
              <button
                type="button"
                className="btn btn--small"
                disabled={locked || queuing !== null}
                onClick={() => onQueue(t)}
              >
                {S.music.queue}
              </button>
            </li>
          ))}
        </ul>
      )}

      {toast && <Toast text={toast} onDone={() => setToast(null)} />}
    </>
  );
}
