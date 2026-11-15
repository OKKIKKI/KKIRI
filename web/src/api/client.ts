import type { ErrorBody } from './types';

// 서버 주소는 환경변수로만 받는다(명세 1.2절). 코드에 주소를 적지 않는다.
export const API_BASE: string = import.meta.env.KKIRI_API_BASE;
export const WS_BASE: string = import.meta.env.KKIRI_WS_BASE;
export const USE_MOCK: boolean = import.meta.env.VITE_USE_MOCK === '1';

/** 서버가 명세 1.4 형식으로 돌려준 오류. `code`는 명세 표의 값 그대로다. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> | null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

function isErrorBody(v: unknown): v is ErrorBody {
  const e = (v as ErrorBody | null)?.error;
  return typeof e === 'object' && e !== null && typeof e.code === 'string';
}

interface RequestOptions {
  token?: string | null;
  body?: unknown;
}

/**
 * REST 호출. 성공이면 본문(JSON, 204면 undefined), 명세 형식의 오류면 ApiError를 던진다.
 * 네트워크 실패나 형식에 맞지 않는 오류 응답은 일반 Error로 던진다(화면에서는 "잠시 후 다시 시도해 주세요").
 */
export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;

  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });

  if (res.status === 204) return undefined as T;

  let data: unknown = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }

  if (!res.ok) {
    if (isErrorBody(data)) {
      const { code, message, details } = data.error;
      throw new ApiError(res.status, code, message, details);
    }
    throw new Error(`HTTP ${res.status} ${method} ${path}`);
  }
  return data as T;
}
