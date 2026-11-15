// 토큰 보관 규칙(docs/scope/WEB.md 6절):
// - 비회원 정보(guestToken)는 sessionStorage에만. 요약 화면(WEB-04)에서 지운다.
// - userToken만 localStorage 허용.
// 저장소 접근은 사생활 보호 모드 등에서 예외가 날 수 있어 모두 try/catch로 감싼다.

export interface GuestAuth {
  guestToken: string;
  participantId: string;
}

const guestKey = (sessionId: string) => `kkiri.guest.${sessionId}`;
const USER_TOKEN_KEY = 'kkiri.userToken';

export function saveGuestAuth(sessionId: string, auth: GuestAuth): void {
  try {
    sessionStorage.setItem(guestKey(sessionId), JSON.stringify(auth));
  } catch (e) {
    console.warn('[tokens] sessionStorage 저장 실패', e);
  }
}

export function loadGuestAuth(sessionId: string): GuestAuth | null {
  try {
    const raw = sessionStorage.getItem(guestKey(sessionId));
    if (!raw) return null;
    const v = JSON.parse(raw) as Partial<GuestAuth>;
    return typeof v.guestToken === 'string' && typeof v.participantId === 'string'
      ? { guestToken: v.guestToken, participantId: v.participantId }
      : null;
  } catch {
    return null;
  }
}

export function clearGuestAuth(sessionId: string): void {
  try {
    sessionStorage.removeItem(guestKey(sessionId));
  } catch {
    /* 지울 수 없으면 탭을 닫을 때 브라우저가 지운다 */
  }
}

export function loadUserToken(): string | null {
  try {
    return localStorage.getItem(USER_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function saveUserToken(token: string): void {
  try {
    localStorage.setItem(USER_TOKEN_KEY, token);
  } catch (e) {
    console.warn('[tokens] localStorage 저장 실패', e);
  }
}

export function clearUserToken(): void {
  try {
    localStorage.removeItem(USER_TOKEN_KEY);
  } catch {
    /* 무시 */
  }
}

// WEB-07에서 미리 연 세션 id. 세션 안 정보라 sessionStorage에 둔다(탭을 닫으면 사라짐).
const MY_SESSION_KEY = 'kkiri.me.sessionId';

export function saveMySessionId(sessionId: string): void {
  try {
    sessionStorage.setItem(MY_SESSION_KEY, sessionId);
  } catch (e) {
    console.warn('[tokens] sessionStorage 저장 실패', e);
  }
}

export function loadMySessionId(): string | null {
  try {
    return sessionStorage.getItem(MY_SESSION_KEY);
  } catch {
    return null;
  }
}

export function clearMySessionId(): void {
  try {
    sessionStorage.removeItem(MY_SESSION_KEY);
  } catch {
    /* 무시 */
  }
}
