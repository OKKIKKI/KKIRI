import type { Message } from '../api';

// 받은 메시지를 탭의 sessionStorage에 보관해 새로고침해도 목록을 다시 그린다(2026-11-11 팀원 결정).
// - 스냅샷에는 메시지가 없고 이벤트 재전송도 없어서(명세 2절, 4.1) 서버에서 다시 받을 방법이 없다.
// - 보관하는 것은 message.created 이벤트로 받은 그대로다. 끊긴 동안 온 메시지는 여기에도 없다.
// - 비회원 정보 규칙(docs/scope/WEB.md 6절): localStorage·쿠키 금지, sessionStorage만. 세션이 끝나면 지운다.

const key = (sessionId: string) => `kkiri.messages.${sessionId}`;

export function loadMessages(sessionId: string): Message[] {
  try {
    const raw = sessionStorage.getItem(key(sessionId));
    if (!raw) return [];
    const v: unknown = JSON.parse(raw);
    return Array.isArray(v) ? (v as Message[]) : [];
  } catch {
    return [];
  }
}

export function saveMessages(sessionId: string, messages: Message[]): void {
  try {
    if (messages.length === 0) sessionStorage.removeItem(key(sessionId));
    else sessionStorage.setItem(key(sessionId), JSON.stringify(messages));
  } catch (e) {
    // 용량 초과·저장 금지 등. 화면은 메모리 상태로 계속 동작한다(새로고침 때만 사라짐).
    console.warn('[messageCache] 저장 실패', e);
  }
}

export function clearMessages(sessionId: string): void {
  try {
    sessionStorage.removeItem(key(sessionId));
  } catch {
    /* 탭을 닫으면 브라우저가 지운다 */
  }
}
