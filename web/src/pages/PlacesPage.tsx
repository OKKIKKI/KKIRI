import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, type Place, type WaypointScope } from '../api';
import { useSession } from '../state/SessionContext';
import { S } from '../strings';

// HB-S-WEB-03 장소 검색·경유지 `/s/{sessionId}/places` — NAV-09(검색, 목업), NAV-02(추가), NAV-08(제안)
// ① 검색창 → R-30 ② 결과(이름, "{distanceKm}km") 하나 선택 ③ 반영 범위(차 1대면 숨김) ④ [경유지로 추가] → R-31
// 2차(그리지 않음): [내 하차 지점으로]

const SEARCH_DEBOUNCE_MS = 250; // 입력 중 요청을 줄이는 UI 값

export default function PlacesPage() {
  const { sessionId, auth, session } = useSession();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [places, setPlaces] = useState<Place[] | null>(null);
  const [searchError, setSearchError] = useState(false);
  const [selected, setSelected] = useState<Place | null>(null);
  const [scope, setScope] = useState<WaypointScope>('MY_CAR');
  const [submitting, setSubmitting] = useState(false);
  const [addError, setAddError] = useState(false);

  const showScope = (session?.cars.length ?? 0) > 1;

  // 검색어가 비면 전체 목록(목업 목록 5~10개)
  useEffect(() => {
    let cancelled = false;
    const t = setTimeout(async () => {
      try {
        const res = await api.searchPlaces(sessionId, auth.guestToken, query.trim());
        if (cancelled) return;
        setPlaces(res.places);
        setSearchError(false);
      } catch {
        if (!cancelled) setSearchError(true);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [query, sessionId, auth.guestToken]);

  const onAdd = async () => {
    if (!selected || submitting) return;
    setSubmitting(true);
    setAddError(false);
    try {
      await api.addWaypoint(sessionId, auth.guestToken, {
        place: selected,
        scope: showScope ? scope : 'MY_CAR',
      });
      navigate(`/s/${encodeURIComponent(sessionId)}`, { replace: true, state: { toast: S.places.added } });
    } catch {
      setAddError(true);
      setSubmitting(false);
    }
  };

  return (
    <main className="page">
      <h1 className="title">{S.places.title}</h1>

      {/* ① 검색창 */}
      <input
        className="input"
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        aria-label={S.places.search}
        autoComplete="off"
        enterKeyHint="search"
      />

      {/* ② 결과 목록 */}
      {searchError && <p className="error">{S.error.retry}</p>}
      {places && places.length > 0 && (
        <ul className="list list--places">
          {places.map((p) => {
            const isSel = selected?.placeId === p.placeId;
            return (
              <li key={p.placeId}>
                <button
                  type="button"
                  className={`place ${isSel ? 'is-selected' : ''}`}
                  aria-pressed={isSel}
                  onClick={() => setSelected(p)}
                >
                  <span>{p.name}</span>
                  <span className="muted">{S.places.distance(p.distanceKm)}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <div className="bottom-bar">
        {/* ③ 반영 범위 — 세션에 차가 1대면 숨김 */}
        {showScope && (
          <div className="segmented" role="radiogroup">
            {(['MY_CAR', 'PROPOSE_OTHERS'] as const).map((v) => (
              <button
                key={v}
                type="button"
                role="radio"
                aria-checked={scope === v}
                className={`segmented__item ${scope === v ? 'is-on' : ''}`}
                onClick={() => setScope(v)}
              >
                {v === 'MY_CAR' ? S.places.scopeMyCar : S.places.scopePropose}
              </button>
            ))}
          </div>
        )}
        {addError && (
          <p className="error" role="alert">
            {S.error.retry}
          </p>
        )}
        {/* ④ 경유지로 추가 */}
        <button type="button" className="btn btn--primary" disabled={!selected || submitting} onClick={onAdd}>
          {S.places.add}
        </button>
      </div>
    </main>
  );
}
