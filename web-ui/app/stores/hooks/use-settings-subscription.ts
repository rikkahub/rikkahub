import { useEffect, useRef } from "react";

import { sse } from "~/services/api";
import { useSettingsStore } from "~/stores/app-store";
import type { Settings } from "~/types";

/**
 * Hook to subscribe to settings SSE stream (call once in root)
 */
export function useSettingsSubscription() {
  const setSettings = useSettingsStore((state) => state.setSettings);
  const abortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    abortControllerRef.current = new AbortController();

    sse<Settings>(
      "settings/stream",
      {
        onMessage: ({ data }) => {
          setSettings(data);
        },
        onError: (error) => {
          console.error("Settings SSE error:", error);
        },
      },
      { signal: abortControllerRef.current.signal },
    );

    return () => {
      abortControllerRef.current?.abort();
    };
  }, [setSettings]);
}
