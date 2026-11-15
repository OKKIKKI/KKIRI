import { createMusicConnector, type MusicConnector, type MusicProvider } from '../adapters/music.js';
import { ApiError, Errors } from '../errors.js';
import { emit } from '../ws/hub.js';
import { findById, type SessionRecord } from './sessions.js';

let connector: MusicConnector = createMusicConnector();

/** 지금 쓰는 연결 방법(R-41 경로가 콜백을 가졌는지 볼 때) */
export function musicConnector(): MusicConnector {
  return connector;
}

/** 확인 스크립트 전용: 연결 방법을 바꾼다(가짜 외부 서비스). 서버 코드에서는 부르지 않는다 */
export function setMusicConnectorForTest(c: MusicConnector): void {
  connector = c;
}

/**
 * R-40 연결 시작. 이미 연결돼 있으면 바로 redirect로.
 * 목업처럼 바로 연결되는 경우 여기서 붙이고, 외부 로그인이 필요하면 그 주소를 돌려준다(R-41에서 붙음).
 */
export async function startMusic(rec: SessionRecord, redirect: URL): Promise<string> {
  if (rec.musicProvider) return redirect.toString();
  const { location, provider } = await connector.start(rec.sessionId, redirect);
  if (provider) await attachMusic(rec, provider);
  return location;
}

/**
 * R-41 외부 서비스 콜백. 연결 결과를 세션에 붙이고 웹으로 돌려보낼 주소를 돌려준다.
 * 그 사이 세션이 끝났거나 이미 연결돼 있으면 새 연결은 버린다(토큰을 남기지 않음).
 */
export async function finishMusicCallback(query: URLSearchParams): Promise<string> {
  if (!connector.callback) throw Errors.notFound();
  const { sessionId, redirect, provider } = await connector.callback(query);
  if (provider) {
    const found = findById(sessionId);
    if (found && 'rec' in found && !found.rec.musicProvider) await attachMusic(found.rec, provider);
    else provider.dispose();
  }
  return redirect;
}

/** 세션에 연결을 붙이고 지금 곡을 한 번 보낸 뒤, 필요하면 주기 조회를 시작한다 */
async function attachMusic(rec: SessionRecord, provider: MusicProvider): Promise<void> {
  rec.musicProvider = provider;
  rec.music.connected = true;
  // 연결 직후 재생 기기가 없어도(폰에서 아직 음악을 안 틂) 연결은 성공으로 두고 웹으로 돌려보낸다. 곡은 다음 조회 때 채운다
  await refreshNowPlayingSafe(rec, true);
  const every = connector.pollIntervalMs;
  if (rec.musicProvider && every && every > 0 && !rec.musicPoll) {
    rec.musicPoll = setInterval(() => void refreshNowPlayingSafe(rec), every);
  }
}

/**
 * 음악 연결이 끊겼을 때(토큰 갱신 실패 등): 연결 정보를 버리고 connected=false를 music.now_playing으로 알린다.
 * 웹은 이 이벤트로 "연결 안 됨"을 보고 다시 R-40을 부를 수 있다(명세 2절 Music·4.3 이벤트 그대로, 새 필드 없음).
 */
export function disconnectMusic(rec: SessionRecord): void {
  if (!rec.musicProvider) return;
  rec.musicProvider.dispose();
  rec.musicProvider = null;
  if (rec.musicPoll) clearInterval(rec.musicPoll);
  rec.musicPoll = null;
  rec.music.connected = false;
  rec.music.nowPlaying = null;
  console.warn(`[music] ${rec.sessionId} 음악 연결이 끊김 → connected=false`);
  if (rec.state !== 'ENDED') emit(rec, 'music.now_playing', { music: rec.music });
}

const isNotConnected = (e: unknown) => e instanceof ApiError && e.code === 'MUSIC_NOT_CONNECTED';

/**
 * 연결된 음악으로 작업 하나(R-43~R-45). 그 사이 연결이 끊긴 것이 드러나면(MUSIC_NOT_CONNECTED) 연결을 정리하고 오류는 그대로 던진다.
 */
export async function withMusic<T>(rec: SessionRecord, fn: (p: MusicProvider) => Promise<T>): Promise<T> {
  const p = providerOf(rec);
  try {
    return await fn(p);
  } catch (e) {
    if (isNotConnected(e) && rec.musicProvider === p) disconnectMusic(rec);
    throw e;
  }
}

/**
 * refreshNowPlaying을 실패해도 던지지 않게 감싼다(연결 직후·주기 조회·R-42 응답 전·R-43 성공 뒤).
 * 조회가 실패하면 마지막으로 알던 상태를 그대로 두고, 연결이 끊긴 것이면 disconnectMusic.
 */
export async function refreshNowPlayingSafe(rec: SessionRecord, force = false): Promise<void> {
  const p = rec.musicProvider;
  try {
    await refreshNowPlaying(rec, force);
  } catch (e) {
    if (isNotConnected(e) && p && rec.musicProvider === p) { disconnectMusic(rec); return; }
    // 토큰·본문은 남기지 않는다. 다음 주기에 다시 읽는다
    console.warn(`[music] ${rec.sessionId} 지금 재생 조회 실패: ${(e as Error).message}`);
    if (force && rec.musicProvider === p && rec.state !== 'ENDED') emit(rec, 'music.now_playing', { music: rec.music }); // 연결됨은 알린다
  }
}

export function providerOf(rec: SessionRecord): MusicProvider {
  if (!rec.musicProvider || !rec.music.connected) throw Errors.musicNotConnected();
  return rec.musicProvider;
}

/**
 * 지금 재생 중인 곡을 다시 읽어, 바뀌었으면 rec.music을 고치고 music.now_playing을 보낸다.
 * 다른 곡이 되면 trackCount(요약의 "함께 들은 곡")를 1 늘린다.
 * R-42·R-43·R-45 직후와, 연결 방법이 pollIntervalMs를 주면 그 주기로 부른다(명세 3.5, 클라이언트는 폴링하지 않음).
 */
export async function refreshNowPlaying(rec: SessionRecord, force = false): Promise<void> {
  if (!rec.musicProvider || rec.state === 'ENDED') return;
  const next = await rec.musicProvider.nowPlaying();
  if (!rec.musicProvider) return; // 조회하는 사이 세션이 끝남(removeSession이 provider를 비움)
  const prev = rec.music.nowPlaying;
  const songChanged = (next?.title ?? null) !== (prev?.title ?? null) || (next?.artist ?? null) !== (prev?.artist ?? null);
  const changed = songChanged || (next?.isPlaying ?? null) !== (prev?.isPlaying ?? null);
  if (songChanged && next) rec.trackCount += 1;
  rec.music.nowPlaying = next;
  if (changed || force) emit(rec, 'music.now_playing', { music: rec.music });
}
