import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

// 화면 아래 잠깐 뜨는 안내. REST 성공·실패 안내에만 쓴다(목록 갱신은 이벤트가 한다).

const TOAST_MS = 2_500; // 화면 표시 시간(UI 값)

export function Toast({ text, onDone }: { text: string; onDone(): void }) {
  const doneRef = useRef(onDone);
  doneRef.current = onDone;
  useEffect(() => {
    const t = setTimeout(() => doneRef.current(), TOAST_MS);
    return () => clearTimeout(t);
  }, [text]);
  return (
    <div className="toast" role="status" aria-live="polite">
      {text}
    </div>
  );
}

/** 다른 화면이 navigate(…, { state: { toast } })로 넘긴 안내를 한 번만 보여 준다. */
export function useToast(): [string | null, (text: string | null) => void] {
  const location = useLocation();
  const navigate = useNavigate();
  const passed = (location.state as { toast?: unknown } | null)?.toast;
  const [text, setText] = useState<string | null>(typeof passed === 'string' ? passed : null);

  useEffect(() => {
    // 새로고침·뒤로 가기에서 다시 뜨지 않도록 넘겨받은 state를 지운다.
    if (typeof passed === 'string') navigate(location.pathname, { replace: true, state: null });
  }, [passed, navigate, location.pathname]);

  return [text, setText];
}
