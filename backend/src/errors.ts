/** 명세 1.4절 오류 코드. 코드·HTTP 상태는 명세 표 그대로 쓴다. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: unknown = null,
  ) {
    super(message);
  }
}

export const Errors = {
  unauthorized: () => new ApiError(401, 'UNAUTHORIZED', '인증이 필요해요'),
  forbidden: () => new ApiError(403, 'FORBIDDEN', '권한이 없어요'),
  notFound: (message = '없는 경로예요') => new ApiError(404, 'NOT_FOUND', message),
  validation: (message: string) => new ApiError(400, 'VALIDATION_ERROR', message),
  sessionNotFound: () => new ApiError(404, 'SESSION_NOT_FOUND', '세션을 찾을 수 없어요'),
  sessionEnded: () => new ApiError(410, 'SESSION_ENDED', '이미 끝난 세션이에요'),
  qrExpired: () => new ApiError(410, 'QR_EXPIRED', 'QR이 만료됐어요'),
  joinCodeInvalid: () => new ApiError(404, 'JOIN_CODE_INVALID', '세션을 찾을 수 없어요'),
  urgentRateLimited: (retryAfterSec: number) =>
    new ApiError(429, 'URGENT_RATE_LIMITED', `${retryAfterSec}초 뒤에 다시 보낼 수 있어요`, { retryAfterSec }),
  nothingToUndo: () => new ApiError(409, 'NOTHING_TO_UNDO', '되돌릴 경유지 변경이 없어요'),
  proposalClosed: () => new ApiError(409, 'PROPOSAL_CLOSED', '이미 처리된 제안이에요'),
  musicLocked: () => new ApiError(423, 'MUSIC_LOCKED', '운전자가 음악을 잠갔어요'),
  musicNotConnected: () => new ApiError(409, 'MUSIC_NOT_CONNECTED', 'Spotify가 연결되지 않았어요'),
  musicNoActiveDevice: () => new ApiError(409, 'MUSIC_NO_ACTIVE_DEVICE', 'Spotify가 재생 중인 기기가 없어요. 폰에서 Spotify를 먼저 틀어 주세요'),
  musicPremiumRequired: () => new ApiError(409, 'MUSIC_PREMIUM_REQUIRED', 'Spotify Premium 계정만 제어할 수 있어요'),
  controlRateLimited: () => new ApiError(429, 'CONTROL_RATE_LIMITED', '잠시 후 다시 눌러 주세요'),
  internal: () => new ApiError(500, 'INTERNAL', '서버 오류가 발생했어요'),
} as const;
