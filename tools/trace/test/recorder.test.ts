import { afterEach, describe, expect, test } from "bun:test";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { recordTrace } from "../src/recorder";
import type { LoadedTraceCase } from "../src/types";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  delete process.env.TRACE_TEST_API_KEY;
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, {
    recursive: true,
    force: true,
  })));
});

describe("recordTrace", () => {
  test("records a local SSE response without writing the API key", async () => {
    const authorizationHeaders: Array<string | null> = [];
    const server = Bun.serve({
      port: 0,
      fetch(request) {
        authorizationHeaders.push(request.headers.get("authorization"));
        return new Response([
          "event: response.output_text.delta\n",
          "data: {\"delta\":\"hello\"}\n\n",
          "event: response.completed\n",
          "data: {\"type\":\"response.completed\"}\n\n",
        ].join(""), {
          headers: { "Content-Type": "text/event-stream" },
        });
      },
    });

    try {
      const directory = await mkdtemp(join(tmpdir(), "rikkahub-trace-cli-"));
      temporaryDirectories.push(directory);
      const outputPath = join(directory, "events.jsonl");
      process.env.TRACE_TEST_API_KEY = "test-secret";
      const trace: LoadedTraceCase = {
        name: "local-test",
        provider: "openai-responses",
        model: "test-model",
        apiKeyEnv: "TRACE_TEST_API_KEY",
        baseUrl: server.url.toString(),
        endpoint: "/trace",
        headers: {},
        body: { input: "hello" },
        outputPath,
        timeoutMs: 5_000,
      };

      expect(await recordTrace(trace, false)).toBe(2);
      expect(authorizationHeaders).toEqual(["Bearer test-secret"]);
      const output = await readFile(outputPath, "utf8");
      expect(output).not.toContain("test-secret");
      expect(output.trim().split("\n").map((line) => JSON.parse(line))).toEqual([
        {
          event: "response.output_text.delta",
          data: "{\"delta\":\"hello\"}",
        },
        {
          event: "response.completed",
          data: "{\"type\":\"response.completed\"}",
        },
      ]);
    } finally {
      server.stop(true);
    }
  });
});
