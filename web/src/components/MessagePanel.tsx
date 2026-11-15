import { useState, type FormEvent } from 'react';
import { api, ApiError, type Message } from '../api';
import { useSession } from '../state/SessionContext';
import { S } from '../strings';

// HB-S-WEB-02 ⑤ 메시지 목록 + 입력 — HB-F-MSG-01, MSG-05(안내)
// - 목록은 message.created 이벤트로만 늘어난다. 보내기 성공 응답으로 목록을 미리 바꾸지 않는다.
// - 웹은 kind=TEXT, target=ALL만 보낸다(대상 고르기는 화면 설계에 없음).

function timeOf(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function retryAfterSec(e: ApiError): number | null {
  const v = e.details?.retryAfterSec;
  return typeof v === 'number' && Number.isFinite(v) ? Math.max(1, Math.ceil(v)) : null;
}

function MessageItem({ m }: { m: Message }) {
  return (
    <li className={`card ${m.urgent ? '' : 'card--filled'} message`}>
      <div className="message__meta">
        <span className="muted">{S.message.meta(m.sender.name, timeOf(m.createdAt))}</span>
        {m.urgent && <span className="badge">{S.message.urgent}</span>}
      </div>
      <p className="message__text">{m.text ?? S.message.voice}</p>
    </li>
  );
}

export default function MessagePanel() {
  const { sessionId, auth, messages } = useSession();
  const [text, setText] = useState('');
  const [urgent, setUrgent] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmed = text.trim();

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!trimmed || sending) return;
    setSending(true);
    setError(null);
    try {
      await api.sendMessage(sessionId, auth.guestToken, { kind: 'TEXT', text: trimmed, urgent, target: 'ALL' });
      setText('');
      setUrgent(false);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'URGENT_RATE_LIMITED') {
        const sec = retryAfterSec(err);
        setError(sec !== null ? S.message.urgentLimited(sec) : S.error.retry);
      } else {
        setError(S.error.retry);
      }
    } finally {
      setSending(false);
    }
  };

  // 입력줄은 페이지(.page)의 직접 자식이어야 화면 아래에 계속 붙어 있다(position: sticky).
  return (
    <>
      <section className="messages">
        <h2 className="section-title">{S.message.title}</h2>
        {messages.length > 0 && (
          <ul className="list list--messages">
            {messages.map((m) => (
              <MessageItem key={m.messageId} m={m} />
            ))}
          </ul>
        )}
      </section>

      <form className="composer" onSubmit={onSubmit}>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <div className="composer__row">
          <button
            type="button"
            className={`btn btn--toggle ${urgent ? 'is-on' : ''}`}
            aria-pressed={urgent}
            onClick={() => setUrgent((v) => !v)}
          >
            {S.message.urgent}
          </button>
          <input
            className="input"
            value={text}
            onChange={(e) => setText(e.target.value)}
            aria-label={S.message.title}
            autoComplete="off"
            enterKeyHint="send"
          />
          <button type="submit" className="btn btn--send" disabled={!trimmed || sending}>
            {S.message.send}
          </button>
        </div>
      </form>
    </>
  );
}
