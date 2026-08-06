import type { ApiKeyAuth, LoadedTraceCase, Provider, ProviderRequest } from "./types";

const PROVIDER_DEFAULTS: Record<Provider, { baseUrl: string; apiKeyEnv: string }> = {
  "openai-responses": {
    baseUrl: "https://api.openai.com/v1",
    apiKeyEnv: "OPENAI_API_KEY",
  },
  "openai-chat": {
    baseUrl: "https://api.openai.com/v1",
    apiKeyEnv: "OPENAI_API_KEY",
  },
  claude: {
    baseUrl: "https://api.anthropic.com/v1",
    apiKeyEnv: "ANTHROPIC_API_KEY",
  },
  google: {
    baseUrl: "https://generativelanguage.googleapis.com/v1beta",
    apiKeyEnv: "GEMINI_API_KEY",
  },
};

export function defaultApiKeyEnv(provider: Provider): string {
  return PROVIDER_DEFAULTS[provider].apiKeyEnv;
}

export function buildProviderRequest(trace: LoadedTraceCase, apiKey: string): ProviderRequest {
  const defaults = PROVIDER_DEFAULTS[trace.provider];
  const baseUrl = (trace.baseUrl ?? defaults.baseUrl).replace(/\/$/, "");
  const body = structuredClone(trace.body);
  const headers: Record<string, string> = {
    Accept: "text/event-stream",
    "Content-Type": "application/json",
    "User-Agent": "rikkahub-trace-cli/0.1.0",
    ...trace.headers,
  };

  let defaultEndpoint: string;
  let defaultAuth: ApiKeyAuth;
  const endpointModel = trace.model ?? asNonEmptyString(body.model);
  switch (trace.provider) {
    case "openai-responses":
      requireModel(body, trace.model);
      body.stream = true;
      defaultAuth = { header: "Authorization", scheme: "Bearer" };
      defaultEndpoint = "/responses";
      break;
    case "openai-chat":
      requireModel(body, trace.model);
      body.stream = true;
      defaultAuth = { header: "Authorization", scheme: "Bearer" };
      defaultEndpoint = "/chat/completions";
      break;
    case "claude":
      requireModel(body, trace.model);
      body.stream = true;
      defaultAuth = { header: "x-api-key" };
      headers["anthropic-version"] ??= "2023-06-01";
      defaultEndpoint = "/messages";
      break;
    case "google": {
      const model = endpointModel;
      if (!model) throw new Error(`${trace.name}: Google trace requires model`);
      delete body.model;
      defaultAuth = { header: "x-goog-api-key" };
      defaultEndpoint = `/models/${encodeURIComponent(model)}:streamGenerateContent?alt=sse`;
      break;
    }
  }

  const auth = trace.auth ?? defaultAuth;
  headers[auth.header] = auth.scheme ? `${auth.scheme} ${apiKey}` : apiKey;

  return {
    url: resolveEndpoint(baseUrl, trace.endpoint ?? defaultEndpoint, endpointModel),
    headers,
    body,
  };
}

export function redactRequest(request: ProviderRequest): ProviderRequest {
  const headers = Object.fromEntries(
    Object.entries(request.headers).map(([name, value]) => [
      name,
      /authorization|api-key/i.test(name) ? "<redacted>" : value,
    ]),
  );
  return { ...request, headers };
}

function requireModel(body: Record<string, unknown>, configuredModel?: string): void {
  const model = configuredModel ?? asNonEmptyString(body.model);
  if (!model) throw new Error("Trace requires model in the case or request body");
  body.model = model;
}

function asNonEmptyString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function resolveEndpoint(baseUrl: string, endpoint: string, model?: string): string {
  const expanded = endpoint.replaceAll("{model}", encodeURIComponent(model ?? ""));
  if (/^https?:\/\//.test(expanded)) return expanded;
  return `${baseUrl}/${expanded.replace(/^\//, "")}`;
}
