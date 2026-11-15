import { createServer } from 'node:http';
import { config } from './config.js';
import { Errors } from './errors.js';
import { handleError, Router, sendJson } from './http.js';
import { attachRoutes } from './routes/attach.js';
import { authRoutes } from './routes/auth.js';
import { carRoutes } from './routes/cars.js';
import { driveRoutes } from './routes/drive.js';
import { messageRoutes } from './routes/messages.js';
import { musicRoutes } from './routes/music.js';
import { voiceRoutes } from './routes/voice.js';
import { waypointRoutes } from './routes/waypoints.js';
import { sessionRoutes } from './routes/sessions.js';
import { seedDeviceWarnings } from './store/users.js';
import { attachWs } from './ws/hub.js';

const router = new Router();
router.add('GET', '/health', ({ res }) => sendJson(res, 200, { status: 'ok', service: 'kkiri-backend' }));
carRoutes(router);
sessionRoutes(router);
messageRoutes(router);
voiceRoutes(router);
waypointRoutes(router);
authRoutes(router);
driveRoutes(router);
attachRoutes(router);
musicRoutes(router);

const server = createServer(async (req, res) => {
  // CORS는 KKIRI_WEB_BASE만 허용(범위 문서 6절). 차량 앱은 브라우저가 아니라 CORS와 무관.
  if (req.headers.origin === config.webBase) {
    res.setHeader('Access-Control-Allow-Origin', config.webBase);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  }
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  const url = new URL(req.url ?? '/', 'http://localhost');
  const started = Date.now();
  res.on('finish', () => {
    // 경로와 상태만 남긴다. 쿼리·본문·토큰은 로그에 남기지 않는다.
    console.log(`${req.method} ${url.pathname} ${res.statusCode} ${Date.now() - started}ms`);
  });

  const m = router.match(req.method ?? 'GET', url.pathname);
  if (!m) { handleError(res, Errors.notFound()); return; }
  try {
    await m.handler({ req, res, params: m.params, query: url.searchParams });
  } catch (err) {
    handleError(res, err);
  }
});

attachWs(server);

server.listen(config.port, () => {
  console.log(`[kkiri-backend] http://localhost:${config.port}`);
  // 시연 기기 deviceId 누락 방지(R-13·R-14). 개발용 값뿐이면 경고만 하고 계속 실행한다.
  for (const w of seedDeviceWarnings()) console.warn(w);
});
