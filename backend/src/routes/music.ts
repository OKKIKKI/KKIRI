import type { IncomingMessage } from 'node:http';
import type { MusicAction } from '../adapters/music.js';
import { config } from '../config.js';
import { Errors } from '../errors.js';
import { readJson, sendJson, sendNoContent, type Router } from '../http.js';
import { touchActivity } from '../store/lifecycle.js';
import { finishMusicCallback, providerOf, refreshNowPlayingSafe, startMusic, withMusic } from '../store/music.js';
import { asObject, optBoolean, reqString } from '../validate.js';
import { emit } from '../ws/hub.js';
import { carMember, member, safeRedirect } from './common.js';

const ACTIONS: MusicAction[] = ['NEXT', 'PREVIOUS', 'PAUSE', 'PLAY'];

/** R-43~R-45는 "참가자"(웹, guestToken)만. */
function participant(req: IncomingMessage, sessionId: string, queryToken: string | null = null) {
  const { rec, you } = member(req, sessionId, queryToken);
  if (you.actorType !== 'PARTICIPANT' || !you.participantId) throw Errors.forbidden();
  const par = rec.participants.find((p) => p.participantId === you.participantId)!;
  return { rec, you, par };
}

export function musicRoutes(r: Router): void {
  /**
   * R-40 Spotify 연결 시작 (MUS-01). 리더 또는 호스트 차 소속 참가자(1차는 운전자 폰 확인 없음).
   * 연결 방법(adapters/music.ts MusicConnector)에 따라: 목업은 바로 연결하고 redirect로, 실제 Spotify(D-09 확정 후)는 Spotify 로그인 주소로 보낸다.
   * 브라우저 주소 이동이라 헤더를 못 실으므로 쿼리 `token=<guestToken>`도 받는다(명세 v1.5, Q-015). 헤더가 있으면 헤더 우선.
   * R-40은 Q-006 "입력"이 아니다.
   */
  r.add('GET', '/v1/sessions/:sessionId/music/spotify/start', async ({ req, res, params, query }) => {
    const { rec, you } = participant(req, params.sessionId, query.get('token'));
    if (!(you.role === 'LEADER' || you.carId === rec.hostCarId)) throw Errors.forbidden();
    const redirect = safeRedirect(query.get('redirect'));
    const location = await startMusic(rec, redirect);
    res.writeHead(302, { Location: location, 'Cache-Control': 'no-store' });
    res.end();
  });

  /**
   * R-41 Spotify 콜백(호출자: Spotify). 연결 방법에 콜백이 있을 때만(실제 Spotify, D-09). 목업은 R-40에서 바로 연결하므로 404.
   * 성공·실패 모두 R-40 때 받은 redirect(웹)로 302. 실패면 연결 없이 돌아가고 웹은 R-42로 "연결 안 됨"을 본다.
   */
  r.add('GET', '/v1/music/spotify/callback', async ({ res, query }) => {
    const location = await finishMusicCallback(query);
    res.writeHead(302, { Location: location, 'Cache-Control': 'no-store' });
    res.end();
  });

  /** R-42 음악 상태 (MUS-03). 참가자·차량. 입력 아님. */
  r.add('GET', '/v1/sessions/:sessionId/music', async ({ req, res, params }) => {
    const { rec } = member(req, params.sessionId);
    await refreshNowPlayingSafe(rec); // 조회가 실패해도 마지막으로 알던 상태로 응답한다
    sendJson(res, 200, { music: rec.music });
  });

  /** R-43 재생 제어 (MUS-02). 잠금이면 MUSIC_LOCKED, 같은 참가자 NEXT/PREVIOUS 연타 제한. */
  r.add('POST', '/v1/sessions/:sessionId/music/control', async ({ req, res, params }) => {
    const { rec, par } = participant(req, params.sessionId);
    const body = asObject(await readJson(req));
    const action = reqString(body, 'action') as MusicAction;
    if (!ACTIONS.includes(action)) throw Errors.validation('action은 NEXT, PREVIOUS, PAUSE, PLAY 중 하나예요');
    providerOf(rec); // 연결 안 됨이 잠금보다 먼저(기존 순서 유지)
    if (rec.music.locked) throw Errors.musicLocked();
    if (action === 'NEXT' || action === 'PREVIOUS') {
      const now = Date.now();
      const last = rec.lastControlAt.get(par.participantId);
      if (last !== undefined && now - last < config.musicControlLimitSec * 1000) throw Errors.controlRateLimited();
      rec.lastControlAt.set(par.participantId, now);
    }
    await withMusic(rec, (p) => p.control(action));
    await refreshNowPlayingSafe(rec); // 제어는 성공. 곡 다시 읽기가 실패해도 성공으로 응답하고 주기 조회로 반영
    touchActivity(rec); // Q-006 입력(음악)
    sendNoContent(res);
  });

  /** R-44 곡 검색 (MUS-04). 최대 10개. */
  r.add('GET', '/v1/sessions/:sessionId/music/search', async ({ req, res, params, query }) => {
    const { rec } = participant(req, params.sessionId);
    const q = (query.get('q') ?? '').trim();
    if (q === '') throw Errors.validation('q가 필요해요');
    const tracks = (await withMusic(rec, (p) => p.search(q))).slice(0, 10);
    touchActivity(rec); // Q-006 입력(음악)
    sendJson(res, 200, { tracks });
  });

  /** R-45 다음 곡으로 넣기 (MUS-04). 잠금이면 MUSIC_LOCKED(명세 v1.5). 이벤트 music.queue_added. */
  r.add('POST', '/v1/sessions/:sessionId/music/queue', async ({ req, res, params }) => {
    const { rec, par } = participant(req, params.sessionId);
    const body = asObject(await readJson(req));
    const track = { uri: reqString(body, 'uri'), title: reqString(body, 'title'), artist: reqString(body, 'artist') };
    providerOf(rec); // 연결 안 됨이 잠금보다 먼저(기존 순서 유지)
    if (rec.music.locked) throw Errors.musicLocked();
    await withMusic(rec, (p) => p.queue(track));
    emit(rec, 'music.queue_added', { title: track.title, artist: track.artist, addedBy: { name: par.name } });
    touchActivity(rec); // Q-006 입력(음악)
    sendNoContent(res);
  });

  /** R-46 음악 잠금 (MUS-06). 호스트 차만. 연결 여부와 무관하게 설정한다. */
  r.add('PUT', '/v1/sessions/:sessionId/music/lock', async ({ req, res, params }) => {
    const { rec, carId } = carMember(req, params.sessionId);
    if (carId !== rec.hostCarId) throw Errors.forbidden();
    const body = asObject(await readJson(req));
    const locked = optBoolean(body, 'locked');
    if (locked === null) throw Errors.validation('locked가 필요해요');
    rec.music.locked = locked;
    emit(rec, 'music.lock_changed', { locked });
    touchActivity(rec); // Q-006 입력(음악)
    sendNoContent(res);
  });
}
