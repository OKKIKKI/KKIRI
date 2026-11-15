import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // 명세 1.2절 이름(KKIRI_API_BASE, KKIRI_WS_BASE)과 범위 문서의 목업 전환(VITE_USE_MOCK)을 함께 쓴다.
  // 이 접두사의 값은 브라우저 번들에 들어가므로 비밀 값을 두지 않는다.
  envPrefix: ['KKIRI_', 'VITE_'],
  server: {
    port: 5173,
    strictPort: true,
    host: true,
    // 목업은 docs/fixtures/를 복사하지 않고 직접 읽는다(수정은 PM만).
    fs: { allow: ['.', '../docs/fixtures'] },
  },
});
