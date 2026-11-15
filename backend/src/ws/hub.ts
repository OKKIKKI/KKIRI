import type { IncomingMessage, Server } from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocket, WebSocketServer } from 'ws';
import { principalOf } from '../auth.js';
import { config } from '../config.js';
import { actorFor, findById, sessionCar, toSession, trackParticipantConnection, type SessionRecord } from '../store/sessions.js';
import type { You } from '../types.js';
import { isoNow } from '../util.js';

interface Conn {
  socket: WebSocket;
  sessionId: string;
  you: You;
}

const bySession = new Map<string, Set<Conn>>();

function envelope(rec: SessionRecord, type: string, seq: number, payload: unknown): string {
  return JSON.stringify({ type, sessionId: rec.sessionId, seq, ts: isoNow(), payload });
}

/**
 * 세션 전체에 이벤트를 보낸다(명세 4.2·4.3). seq는 이벤트마다 1씩 증가.
 * payload를 함수로 주면 연결(받는 쪽)마다 다른 payload를 만들 수 있다. null이면 그 연결에는 보내지 않는다.
 */
export function emit(rec: SessionRecord, type: string, payload: unknown | ((you: You) => unknown | null)): void {
  rec.seq += 1;
  const conns = bySession.get(rec.sessionId);
  if (!conns) return;
  for (const c of conns) {
    if (c.socket.readyState !== WebSocket.OPEN) continue;
    const body = typeof payload === 'function' ? (payload as (you: You) => unknown | null)(c.you) : payload;
    if (body === null) continue;
    c.socket.send(envelope(rec, type, rec.seq, body));
  }
}

function sendSnapshot(rec: SessionRecord, c: Conn): void {
  // 스냅샷은 연결 하나에만 가므로 seq를 올리지 않고 "지금까지의 마지막 seq"를 싣는다.
  c.socket.send(envelope(rec, 'session.snapshot', rec.seq, { session: toSession(rec), you: c.you }));
}

function reject(socket: Duplex, status: number, text: string): void {
  socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

/** 명세 4.1: GET {KKIRI_WS_BASE}/v1/ws?sessionId=&token= */
export function attachWs(server: Server): void {
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req: IncomingMessage, socket: Duplex, head: Buffer) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (url.pathname !== '/v1/ws') return reject(socket, 404, 'Not Found');
    const sessionId = url.searchParams.get('sessionId');
    const principal = principalOf(url.searchParams.get('token'));
    if (!principal) return reject(socket, 401, 'Unauthorized');
    if (!sessionId) return reject(socket, 400, 'Bad Request');
    const found = findById(sessionId);
    if (!found) return reject(socket, 404, 'Not Found');
    if ('ended' in found) return reject(socket, 410, 'Gone');
    const you = actorFor(found.rec, principal);
    if (!you) return reject(socket, 403, 'Forbidden');

    wss.handleUpgrade(req, socket, head, (ws) => {
      const rec = found.rec;
      const conn: Conn = { socket: ws, sessionId, you };
      if (you.actorType === 'CAR') carConnected(rec, you.carId);
      let set = bySession.get(sessionId);
      if (!set) bySession.set(sessionId, (set = new Set()));
      set.add(conn);

      // 클라이언트 → 서버는 {"type":"ping"}만 받는다. 상태 변경은 전부 REST(명세 4.1).
      ws.on('message', () => { /* ping 포함, 내용으로 상태를 바꾸지 않는다 */ });
      if (you.participantId) trackParticipantConnection(rec, you.participantId, 1);
      ws.on('close', () => {
        set!.delete(conn);
        if (set!.size === 0) bySession.delete(sessionId);
        if (you.participantId) trackParticipantConnection(rec, you.participantId, -1);
        if (you.actorType === 'CAR' && carConnCount(sessionId, you.carId) === 0) carDropped(rec, you.carId);
      });

      sendSnapshot(found.rec, conn);
    });
  });
}

// ── 차량 끊김 (명세 4.1: 30초 이상 끊기면 DISCONNECTED) ─────────────

/** `${sessionId}|${carId}` → DISCONNECTED 판정 타이머 */
const carDropTimers = new Map<string, NodeJS.Timeout>();

function carConnCount(sessionId: string, carId: string): number {
  let n = 0;
  for (const c of bySession.get(sessionId) ?? []) if (c.you.actorType === 'CAR' && c.you.carId === carId) n++;
  return n;
}

/** 차량 WS가 붙음: 대기 중인 판정을 취소하고, DISCONNECTED였으면 CONNECTED로 되돌린다. */
function carConnected(rec: SessionRecord, carId: string): void {
  const key = `${rec.sessionId}|${carId}`;
  const t = carDropTimers.get(key);
  if (t) { clearTimeout(t); rec.timers.delete(t); carDropTimers.delete(key); }
  const car = sessionCar(rec, carId);
  if (car && car.status === 'DISCONNECTED') {
    car.status = 'CONNECTED';
    emit(rec, 'car.status_changed', { carId, status: car.status, driveState: car.driveState });
  }
}

/**
 * 차량의 마지막 WS가 끊김: CAR_DISCONNECT_SEC 안에 다시 붙지 않으면 DISCONNECTED로 알린다.
 * 시동 OFF(R-08)로 이미 PAUSED인 차는 그대로 둔다(꺼진 차는 끊기는 게 정상).
 */
function carDropped(rec: SessionRecord, carId: string): void {
  if (rec.state === 'ENDED') return;
  const key = `${rec.sessionId}|${carId}`;
  const t = setTimeout(() => {
    carDropTimers.delete(key);
    rec.timers.delete(t);
    if (rec.state === 'ENDED' || carConnCount(rec.sessionId, carId) > 0) return;
    const car = sessionCar(rec, carId);
    if (!car || car.status !== 'CONNECTED') return;
    car.status = 'DISCONNECTED';
    emit(rec, 'car.status_changed', { carId, status: car.status, driveState: car.driveState });
  }, config.carDisconnectSec * 1000);
  carDropTimers.set(key, t);
  rec.timers.add(t);
}

/** 세션이 끝날 때 그 세션의 연결을 모두 닫는다(SES-04에서 사용). */
export function closeSession(sessionId: string): void {
  const set = bySession.get(sessionId);
  if (!set) return;
  for (const c of set) c.socket.close(1000, 'session ended');
  bySession.delete(sessionId);
}
