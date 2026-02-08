import ky, { type Options, HTTPError } from "ky";

interface ErrorResponse {
  error: string;
  code: number;
}

export class ApiError extends Error {
  code: number;

  constructor(message: string, code: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

const kyInstance = ky.create({
  prefixUrl: "/api",
  timeout: 30000,
});

async function handleError(error: unknown): Promise<never> {
  if (error instanceof HTTPError) {
    const { response } = error;
    let errorData: ErrorResponse | undefined;
    try {
      errorData = await response.json();
    } catch {
      // Ignore JSON parse error
    }
    const code = errorData?.code ?? response.status;
    const message = errorData?.error ?? error.message;
    throw new ApiError(message, code);
  }
  throw error;
}

/**
 * API client with unwrapped response data
 */
const api = {
  async get<T>(url: string, options?: Options): Promise<T> {
    try {
      return await kyInstance.get(url, options).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async post<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.post(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async postMultipart<T>(url: string, formData: FormData, options?: Options): Promise<T> {
    try {
      return await kyInstance.post(url, { ...options, body: formData }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async put<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.put(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async patch<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.patch(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async delete<T>(url: string, options?: Options): Promise<T> {
    try {
      return await kyInstance.delete(url, options).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
};

export interface SSEEvent<T> {
  event: string;
  data: T;
  id?: string;
}

export interface SSECallbacks<T> {
  onMessage: (event: SSEEvent<T>) => void;
  onError?: (error: Error) => void;
  onOpen?: () => void;
  onClose?: () => void;
}

/**
 * Create an SSE connection using ky (supports auth headers)
 */
async function sse<T>(
  url: string,
  callbacks: SSECallbacks<T>,
  options?: Options & { signal?: AbortSignal },
): Promise<void> {
  const response = await kyInstance.get(url, {
    ...options,
    headers: {
      ...options?.headers,
      Accept: "text/event-stream",
    },
    timeout: false,
  });

  callbacks.onOpen?.();

  const reader = response.body?.getReader();
  if (!reader) {
    throw new ApiError("Response body is not readable", 0);
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let currentEvent = "message";
  let currentData = "";
  let currentId: string | undefined;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        const trimmedLine = line.replace(/\r$/, "");
        if (trimmedLine.startsWith("event:")) {
          currentEvent = trimmedLine.slice(6).trim();
        } else if (trimmedLine.startsWith("data:")) {
          currentData += (currentData ? "\n" : "") + trimmedLine.slice(5).trim();
        } else if (trimmedLine.startsWith("id:")) {
          currentId = trimmedLine.slice(3).trim();
        } else if (trimmedLine === "") {
          if (currentData) {
            try {
              const data = JSON.parse(currentData) as T;
              callbacks.onMessage({ event: currentEvent, data, id: currentId });
            } catch {
              // Ignore JSON parse error
            }
          }
          currentEvent = "message";
          currentData = "";
          currentId = undefined;
        }
      }
    }
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      // Ignored: intentional abort
    } else {
      callbacks.onError?.(error instanceof Error ? error : new Error(String(error)));
    }
  } finally {
    reader.releaseLock();
    callbacks.onClose?.();
  }
}

export { sse };
export default api;
