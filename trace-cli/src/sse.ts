import type { SseEvent } from "./types";

export async function* parseSseStream(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<SseEvent> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let dataLines: string[] = [];
  let eventName: string | undefined;
  let lastEventId: string | undefined;
  let retryMillis: number | undefined;
  let sawData = false;

  const dispatch = (): SseEvent | undefined => {
    const event: SseEvent | undefined = sawData
      ? { data: dataLines.join("\n") }
      : undefined;
    if (event && lastEventId !== undefined) event.id = lastEventId;
    if (event && eventName) event.event = eventName;
    if (event && retryMillis !== undefined) event.retryMillis = retryMillis;
    dataLines = [];
    eventName = undefined;
    retryMillis = undefined;
    sawData = false;
    return event;
  };

  const processLine = (line: string): SseEvent | undefined => {
    if (line === "") return dispatch();
    if (line.startsWith(":")) return undefined;

    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    switch (field) {
      case "data":
        sawData = true;
        dataLines.push(value);
        break;
      case "event":
        eventName = value;
        break;
      case "id":
        if (!value.includes("\0")) lastEventId = value;
        break;
      case "retry": {
        const parsed = Number(value);
        if (Number.isSafeInteger(parsed) && parsed >= 0) retryMillis = parsed;
        break;
      }
    }
    return undefined;
  };

  const drainLines = function* (atEof: boolean): Generator<SseEvent> {
    while (buffer.length > 0) {
      const newline = buffer.search(/[\r\n]/);
      if (newline === -1) {
        if (!atEof) return;
        const event = processLine(buffer);
        buffer = "";
        if (event) yield event;
        return;
      }
      if (!atEof && buffer[newline] === "\r" && newline === buffer.length - 1) return;

      const line = buffer.slice(0, newline);
      const separatorLength = buffer[newline] === "\r" && buffer[newline + 1] === "\n" ? 2 : 1;
      buffer = buffer.slice(newline + separatorLength);
      const event = processLine(line);
      if (event) yield event;
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      yield* drainLines(false);
    }
    buffer += decoder.decode();
    yield* drainLines(true);
    const finalEvent = dispatch();
    if (finalEvent) yield finalEvent;
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Bun's HTTP DirectStream reader may expose releaseLock but throw from its native implementation.
    }
  }
}
