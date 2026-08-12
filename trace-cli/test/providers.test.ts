import { describe, expect, test } from "bun:test";
import { buildProviderRequest, redactRequest } from "../src/providers";
import type { LoadedTraceCase, Provider } from "../src/types";

describe("buildProviderRequest", () => {
  test.each([
    ["openai-responses", "https://api.openai.com/v1/responses"],
    ["openai-chat", "https://api.openai.com/v1/chat/completions"],
    ["claude", "https://api.anthropic.com/v1/messages"],
    ["google", "https://generativelanguage.googleapis.com/v1beta/models/test-model:streamGenerateContent?alt=sse"],
  ] as const)("builds %s endpoint", (provider, expectedUrl) => {
    const request = buildProviderRequest(trace(provider), "secret");
    expect(request.url).toBe(expectedUrl);
    expect(request.headers.Accept).toBe("text/event-stream");
  });

  test("redacts authentication headers", () => {
    const request = buildProviderRequest(trace("openai-responses"), "secret");
    expect(redactRequest(request).headers.Authorization).toBe("<redacted>");
  });

  test("supports overriding provider authentication", () => {
    const input = trace("claude");
    input.auth = { header: "Authorization", scheme: "Bearer" };

    const request = buildProviderRequest(input, "secret");

    expect(request.headers.Authorization).toBe("Bearer secret");
    expect(request.headers["x-api-key"]).toBeUndefined();
  });
});

function trace(provider: Provider): LoadedTraceCase {
  return {
    name: "test",
    provider,
    model: "test-model",
    headers: {},
    body: provider === "google" ? { contents: [] } : { input: [] },
    outputPath: "/tmp/events.jsonl",
    timeoutMs: 1_000,
  };
}
