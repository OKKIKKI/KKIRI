/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly KKIRI_API_BASE: string;
  readonly KKIRI_WS_BASE: string;
  readonly VITE_USE_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
