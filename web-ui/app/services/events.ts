import { ApiError, sse } from "~/services/api";
import {
  getEventsConnectionCloseDisposition,
  shouldAcceptEventsCallback,
  shouldCancelReconnect,
  shouldScheduleReconnect,
} from "~/services/events-policy";

/**
 * Multiplexed SSE client for `/api/events`.
 *
 * A single shared connection carries several event types (settings, conversation list
 * invalidation, ...). Consumers subscribe by event name; the connection is opened on the
 * first subscriber, reused by all, reconnected on drop, and closed when nobody listens.
 *
 * @see app/src/main/java/me/rerere/rikkahub/web/routes/EventsRoutes.kt
 */

export const EVENT_SETTINGS = "settings";
export const EVENT_CONVERSATION_LIST_INVALIDATE = "conversation_list_invalidate";
export const EVENT_FOLDERS = "folders";

type EventListener = (data: unknown) => void;

const RECONNECT_DELAY_MS = 1000;

const listeners = new Map<string, Set<EventListener>>();
let abortController: AbortController | null = null;
let running = false;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

function hasListeners(): boolean {
  for (const set of listeners.values()) {
    if (set.size > 0) return true;
  }
  return false;
}

function dispatch(event: string, data: unknown) {
  const set = listeners.get(event);
  if (!set) return;
  for (const listener of set) {
    try {
      listener(data);
    } catch (error) {
      console.error(`Events listener for "${event}" failed:`, error);
    }
  }
}

function scheduleReconnect() {
  if (!shouldScheduleReconnect(reconnectTimer !== null)) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    startConnection();
  }, RECONNECT_DELAY_MS);
}

function startConnection() {
  if (running || !hasListeners()) return;
  running = true;

  const controller = new AbortController();
  abortController = controller;
  let unauthorized = false;

  void sse<unknown>(
    "events",
    {
      onMessage: ({ event, data }) => {
        if (!shouldAcceptEventsCallback(abortController, controller)) return;
        dispatch(event, data);
      },
      onError: (error) => {
        if (error instanceof ApiError && error.code === 401) {
          unauthorized = true;
        }
        console.error("Events SSE error:", error);
      },
      onClose: () => {
        const disposition = getEventsConnectionCloseDisposition({
          isCurrentConnection: abortController === controller,
          wasAborted: controller.signal.aborted,
          unauthorized,
          hasListeners: hasListeners(),
        });
        if (disposition === "ignore") return;

        running = false;
        abortController = null;
        if (disposition === "reconnect") {
          scheduleReconnect();
        }
      },
    },
    { signal: controller.signal },
  );
}

function stopIfIdle() {
  const listenersRemain = hasListeners();
  if (reconnectTimer !== null && shouldCancelReconnect(listenersRemain, true)) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (listenersRemain) return;
  abortController?.abort();
  abortController = null;
  running = false;
}

/**
 * Subscribe to a single event type on the shared `/api/events` connection.
 * Returns an unsubscribe function.
 */
export function subscribeToEvent<T>(eventType: string, listener: (data: T) => void): () => void {
  let set = listeners.get(eventType);
  if (!set) {
    set = new Set();
    listeners.set(eventType, set);
  }
  const wrapped = listener as EventListener;
  set.add(wrapped);
  startConnection();

  return () => {
    set.delete(wrapped);
    stopIfIdle();
  };
}
