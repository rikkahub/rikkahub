export type EventsConnectionCloseDisposition = "ignore" | "idle" | "reconnect";

export function getEventsConnectionCloseDisposition({
  isCurrentConnection,
  wasAborted,
  unauthorized,
  hasListeners,
}: {
  isCurrentConnection: boolean;
  wasAborted: boolean;
  unauthorized: boolean;
  hasListeners: boolean;
}): EventsConnectionCloseDisposition {
  if (!isCurrentConnection) return "ignore";
  if (!wasAborted && !unauthorized && hasListeners) return "reconnect";
  return "idle";
}

export function shouldAcceptEventsCallback(
  currentConnection: object | null,
  callbackConnection: object,
): boolean {
  return currentConnection === callbackConnection;
}

export function shouldScheduleReconnect(reconnectScheduled: boolean): boolean {
  return !reconnectScheduled;
}

export function shouldCancelReconnect(hasListeners: boolean, reconnectScheduled: boolean): boolean {
  return !hasListeners && reconnectScheduled;
}
