import type { IncomingMessage, ServerResponse } from 'node:http';
import { ApiError, Errors } from './errors.js';

const MAX_JSON_BYTES = 64 * 1024;

/** 명세 1.4절 오류 형식 */
export function sendError(res: ServerResponse, status: number, code: string, message: string, details: unknown = null): void {
  sendJson(res, status, { error: { code, message, details } });
}

export function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

export function sendNoContent(res: ServerResponse): void {
  res.writeHead(204);
  res.end();
}

/** 본문을 maxBytes까지 읽는다. 넘으면 VALIDATION_ERROR(명세 1.4에 413 코드가 없다). */
export async function readBody(req: IncomingMessage, maxBytes: number): Promise<Buffer> {
  const declared = Number(req.headers['content-length']);
  if (Number.isFinite(declared) && declared > maxBytes) throw Errors.validation('요청 본문이 너무 커요');
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const c of req) {
    const buf = c as Buffer;
    size += buf.length;
    if (size > maxBytes) throw Errors.validation('요청 본문이 너무 커요');
    chunks.push(buf);
  }
  return Buffer.concat(chunks);
}

/** JSON 본문 읽기. 본문이 없으면 null. 형식 오류는 VALIDATION_ERROR. */
export async function readJson(req: IncomingMessage): Promise<unknown> {
  const body = await readBody(req, MAX_JSON_BYTES);
  if (body.length === 0) return null;
  try {
    return JSON.parse(body.toString('utf-8'));
  } catch {
    throw Errors.validation('JSON 형식이 아니에요');
  }
}

/**
 * multipart/form-data 읽기. 새 라이브러리 없이 Node 내장 fetch의 Request.formData()를 쓴다.
 * 본문 전체를 maxBytes까지 메모리에 받은 뒤 해석한다(음성 클립 1MB 제한, D-12).
 */
export async function readMultipart(req: IncomingMessage, maxBytes: number): Promise<FormData> {
  const type = req.headers['content-type'] ?? '';
  if (!type.toLowerCase().startsWith('multipart/form-data')) throw Errors.validation('multipart/form-data로 보내야 해요');
  const body = await readBody(req, maxBytes);
  try {
    return await new Request('http://localhost/', { method: 'POST', headers: { 'content-type': type }, body }).formData();
  } catch {
    throw Errors.validation('multipart 형식이 아니에요');
  }
}

export function sendBinary(res: ServerResponse, contentType: string, data: Buffer): void {
  res.writeHead(200, { 'Content-Type': contentType, 'Content-Length': data.length, 'Cache-Control': 'no-store' });
  res.end(data);
}

// ── 라우터 ──────────────────────────────────────────────

export interface Ctx {
  req: IncomingMessage;
  res: ServerResponse;
  params: Record<string, string>;
  query: URLSearchParams;
}
export type Handler = (ctx: Ctx) => Promise<void> | void;

interface Route {
  method: string;
  parts: string[];
  handler: Handler;
}

/** `/v1/sessions/:sessionId` 형식의 경로를 매칭하는 최소 라우터 */
export class Router {
  private routes: Route[] = [];

  add(method: string, path: string, handler: Handler): void {
    this.routes.push({ method, parts: path.split('/').filter(Boolean), handler });
  }

  match(method: string, pathname: string): { handler: Handler; params: Record<string, string> } | null {
    const segs = pathname.split('/').filter(Boolean);
    for (const r of this.routes) {
      if (r.method !== method || r.parts.length !== segs.length) continue;
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < segs.length; i++) {
        const p = r.parts[i];
        if (p.startsWith(':')) params[p.slice(1)] = decodeURIComponent(segs[i]);
        else if (p !== segs[i]) { ok = false; break; }
      }
      if (ok) return { handler: r.handler, params };
    }
    return null;
  }
}

export function handleError(res: ServerResponse, err: unknown): void {
  if (err instanceof ApiError) {
    sendError(res, err.status, err.code, err.message, err.details);
    return;
  }
  console.error('[kkiri-backend] 처리되지 않은 오류', err);
  const e = Errors.internal();
  sendError(res, e.status, e.code, e.message);
}
