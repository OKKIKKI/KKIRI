import { Errors } from '../errors.js';
import { readMultipart, sendBinary, sendJson, type Router } from '../http.js';
import { getCar } from '../store/cars.js';
import { deliver } from '../store/messages.js';
import { sessionCar } from '../store/sessions.js';
import type { Message } from '../types.js';
import { isoNow, newId } from '../util.js';
import { carMember } from './common.js';
import { touchActivity } from '../store/lifecycle.js';

/** D-12(CONFIRMED): AAC m4a, 최대 15초·1MB. 서버는 형식을 검사하지 않고 크기·길이만 검사한다(범위 문서 BE 4절). */
const MAX_CLIP_BYTES = 1024 * 1024;
const MAX_CLIP_MS = 15_000;
const DEFAULT_MIME = 'audio/mp4';
/** multipart 경계·다른 필드 몫의 여유 */
const MULTIPART_OVERHEAD = 16 * 1024;

export function voiceRoutes(r: Router): void {
  /** R-21 음성 클립 업로드 (VOI-01). multipart: audio, target, durationMs → message.created(kind=VOICE) */
  r.add('POST', '/v1/sessions/:sessionId/voice-clips', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    const form = await readMultipart(req, MAX_CLIP_BYTES + MULTIPART_OVERHEAD);

    const audio = form.get('audio');
    if (!(audio instanceof Blob) || audio.size === 0) throw Errors.validation('audio 파일이 필요해요');
    if (audio.size > MAX_CLIP_BYTES) throw Errors.validation('음성 클립은 1MB까지예요');

    const target = form.get('target');
    if (typeof target !== 'string' || (target !== 'ALL' && !sessionCar(rec, target))) {
      throw Errors.validation('target은 "ALL" 또는 이 세션의 carId여야 해요');
    }
    const durationRaw = form.get('durationMs');
    const durationMs = typeof durationRaw === 'string' && /^\d+$/.test(durationRaw) ? Number(durationRaw) : NaN;
    if (!Number.isInteger(durationMs) || durationMs <= 0 || durationMs > MAX_CLIP_MS) {
      throw Errors.validation('durationMs는 1~15000 사이 정수여야 해요');
    }

    const messageId = newId('msg_');
    const mime = audio.type || DEFAULT_MIME;
    rec.clips.set(messageId, { data: Buffer.from(await audio.arrayBuffer()), mime });

    const msg: Message = {
      messageId,
      kind: 'VOICE',
      sender: { type: 'CAR', id: carId, name: getCar(carId)?.driverName ?? '', carId },
      target,
      text: null,
      // 명세 v1.1: clip.url은 /v1/로 시작하는 상대 경로(R-25)
      clip: { url: `/v1/sessions/${rec.sessionId}/voice-clips/${messageId}`, durationMs, mime },
      urgent: false,
      createdAt: isoNow(),
    };
    deliver(rec, msg);
    touchActivity(rec); // Q-006 입력(무전)
    sendJson(res, 200, { message: msg });
  });

  /** R-25 음성 클립 받기. 그 세션의 차량(carToken)만. 없으면 NOT_FOUND. */
  r.add('GET', '/v1/sessions/:sessionId/voice-clips/:messageId', ({ req, res, params }) => {
    const { rec } = carMember(req, params.sessionId);
    const clip = rec.clips.get(params.messageId);
    if (!clip) throw Errors.notFound('음성 클립이 없어요');
    sendBinary(res, clip.mime, clip.data);
  });
}
