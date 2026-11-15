import { USE_MOCK } from './client';
import type { KkiriApi } from './KkiriApi';
import { mockApi } from './mock';
import { realApi } from './real';

// VITE_USE_MOCK=1 이면 docs/fixtures/ 재생, 아니면 실제 서버(KKIRI_API_BASE, KKIRI_WS_BASE).
export const api: KkiriApi = USE_MOCK ? mockApi : realApi;

export { ApiError } from './client';
export type { ConnectionStatus, KkiriApi } from './KkiriApi';
export type * from './types';
