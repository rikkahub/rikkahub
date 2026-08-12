export const PROVIDERS = [
  "openai-responses",
  "openai-chat",
  "claude",
  "google",
] as const;

export type Provider = (typeof PROVIDERS)[number];

export interface ApiKeyAuth {
  header: string;
  scheme?: string;
}

export interface TraceDefaults {
  outputRoot?: string;
  timeoutMs?: number;
  headers?: Record<string, string>;
}

export interface TraceCase {
  name: string;
  provider: Provider;
  model?: string;
  apiKeyEnv?: string;
  auth?: ApiKeyAuth;
  baseUrl?: string;
  endpoint?: string;
  output?: string;
  timeoutMs?: number;
  headers: Record<string, string>;
  body: Record<string, unknown>;
}

export interface TraceConfig {
  version: 1;
  defaults: TraceDefaults;
  traces: TraceCase[];
}

export interface LoadedTraceCase extends TraceCase {
  outputPath: string;
  timeoutMs: number;
}

/** 与 ai 模块的 me.rerere.ai.provider.stream.SseEvent 字段保持一致。 */
export interface SseEvent {
  id?: string;
  event?: string;
  data: string;
  retryMillis?: number;
}

export interface ProviderRequest {
  url: string;
  headers: Record<string, string>;
  body: Record<string, unknown>;
}
