import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { loadUserToken, saveGuestAuth } from '../state/tokens';
import { S } from '../strings';

// HB-S-WEB-01 입장 `/j/{qrToken}` — R-06 → guestToken 저장(sessionStorage) → `/s/{sessionId}`

const NAME_MAX = 10;

function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'QR_EXPIRED') return S.error.QR_EXPIRED;
    if (e.code === 'SESSION_ENDED') return S.error.SESSION_ENDED;
    if (e.code === 'SESSION_NOT_FOUND') return S.error.SESSION_NOT_FOUND;
  }
  return S.error.retry;
}

export default function JoinPage() {
  const { qrToken = '' } = useParams();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmed = name.trim();
  const valid = trimmed.length >= 1 && trimmed.length <= NAME_MAX;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      // 로그인한 운전자면 userToken을 헤더로 함께 보낸다(명세 1.3). 이름 자동 채우기(R-12)는 3회차.
      const res = await api.joinSession({ qrToken, name: trimmed }, loadUserToken());
      saveGuestAuth(res.session.sessionId, { guestToken: res.guestToken, participantId: res.participantId });
      navigate(`/s/${encodeURIComponent(res.session.sessionId)}`, { replace: true });
    } catch (err) {
      setError(errorText(err));
      setSubmitting(false);
    }
  };

  return (
    <main className="page page--center">
      <h1 className="title">{S.join.title}</h1>
      <form className="field" onSubmit={onSubmit} noValidate>
        <label className="field">
          <span className="label">{S.join.nameLabel}</span>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={NAME_MAX}
            autoComplete="off"
            enterKeyHint="go"
            required
          />
        </label>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button className="btn btn--primary" type="submit" disabled={!valid || submitting}>
          {S.join.submit}
        </button>
      </form>
      <p className="notice">{S.join.notice}</p>
    </main>
  );
}
