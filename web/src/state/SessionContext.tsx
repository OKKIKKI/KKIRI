import { createContext, useContext, useEffect, useReducer, useState, type ReactNode } from 'react';
import { api, ApiError, type ConnectionStatus } from '../api';
import { clearMessages, loadMessages, saveMessages } from './messageCache';
import { initialSessionState, sessionReducer, type SessionViewState } from './sessionReducer';
import type { GuestAuth } from './tokens';

/**
 * 연결을 더 이어 갈 수 없는 이유.
 * - ended: R-07이 SESSION_ENDED (session.ended를 놓친 채 끝난 세션)
 * - notFound: R-07이 SESSION_NOT_FOUND·UNAUTHORIZED·FORBIDDEN
 */
export type StopReason = 'ended' | 'notFound' | null;

export interface SessionContextValue extends SessionViewState {
  sessionId: string;
  auth: GuestAuth;
  status: ConnectionStatus;
  stopReason: StopReason;
}

const SessionContext = createContext<SessionContextValue | null>(null);

const STOP_CODES: Record<string, StopReason> = {
  SESSION_ENDED: 'ended',
  SESSION_NOT_FOUND: 'notFound',
  UNAUTHORIZED: 'notFound',
  FORBIDDEN: 'notFound',
};

export function SessionProvider(props: { sessionId: string; auth: GuestAuth; children: ReactNode }) {
  const { sessionId, auth, children } = props;
  const [state, dispatch] = useReducer(sessionReducer, sessionId, (id) => ({
    ...initialSessionState,
    messages: loadMessages(id), // 새로고침 전에 받은 메시지
  }));
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  const [stopReason, setStopReason] = useState<StopReason>(null);
  const finished = state.ended !== null || stopReason !== null;

  // 받은 메시지 보관. 세션이 끝났거나 더 이을 수 없으면 지운다(비회원 정보 파기).
  useEffect(() => {
    if (finished) clearMessages(sessionId);
    else saveMessages(sessionId, state.messages);
  }, [sessionId, state.messages, finished]);

  useEffect(() => {
    const conn = api.connectSession(sessionId, auth.guestToken, {
      onEnvelope: dispatch,
      onStatus: setStatus,
      // 브라우저 WebSocket은 업그레이드 거부 사유(401/403/404/410)를 알 수 없으므로
      // 끊길 때마다 R-07로 세션이 아직 유효한지 확인한다. 네트워크 오류면 계속 재연결한다.
      async canReconnect() {
        try {
          await api.getSession(sessionId, auth.guestToken);
          return true;
        } catch (e) {
          const reason = e instanceof ApiError ? STOP_CODES[e.code] : undefined;
          if (reason) {
            setStopReason(reason);
            return false;
          }
          return true;
        }
      },
    });
    return () => conn.close();
  }, [sessionId, auth.guestToken]);

  const value: SessionContextValue = { ...state, sessionId, auth, status, stopReason };
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const v = useContext(SessionContext);
  if (!v) throw new Error('useSession은 SessionProvider 안에서만 쓴다');
  return v;
}
