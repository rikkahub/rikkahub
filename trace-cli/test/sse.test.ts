import { describe, expect, test } from "bun:test";
import { parseSseStream } from "../src/sse";

describe("parseSseStream", () => {
  test("parses chunked CRLF events and preserves the last event id", async () => {
    const events = await collect([
      "id: event-1\r",
      "\nevent: response.output_text.delta\r\ndata: {\"delta\":\"Hel",
      "lo\"}\r\nretry: 1500\r\n\r\n",
      ": heartbeat\n",
      "data: [DONE]\n\n",
    ]);

    expect(events).toEqual([
      {
        id: "event-1",
        event: "response.output_text.delta",
        data: "{\"delta\":\"Hello\"}",
        retryMillis: 1500,
      },
      {
        id: "event-1",
        data: "[DONE]",
      },
    ]);
  });

  test("joins multiple data fields and dispatches the final unterminated event", async () => {
    const events = await collect([
      "event: message\ndata: first\ndata: second\n\n",
      "data: final",
    ]);

    expect(events).toEqual([
      { event: "message", data: "first\nsecond" },
      { data: "final" },
    ]);
  });

  test("does not leak an event name across an empty event", async () => {
    const events = await collect([
      "event: stale\n\n",
      "data: next\n\n",
    ]);

    expect(events).toEqual([{ data: "next" }]);
  });

  test("supports runtime readers without releaseLock", async () => {
    const chunks = [new TextEncoder().encode("data: done\n\n")];
    const stream = {
      getReader() {
        return {
          async read() {
            const value = chunks.shift();
            return value ? { done: false, value } : { done: true, value: undefined };
          },
          releaseLock: undefined,
        };
      },
    } as unknown as ReadableStream<Uint8Array>;

    expect(await Array.fromAsync(parseSseStream(stream))).toEqual([{ data: "done" }]);
  });

  test("ignores runtime releaseLock failures after consuming the stream", async () => {
    const chunks = [new TextEncoder().encode("data: done\n\n")];
    const stream = {
      getReader() {
        return {
          async read() {
            const value = chunks.shift();
            return value ? { done: false, value } : { done: true, value: undefined };
          },
          releaseLock() {
            throw new TypeError("undefined is not a function");
          },
        };
      },
    } as unknown as ReadableStream<Uint8Array>;

    expect(await Array.fromAsync(parseSseStream(stream))).toEqual([{ data: "done" }]);
  });
});

async function collect(chunks: string[]) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
  return Array.fromAsync(parseSseStream(stream));
}
