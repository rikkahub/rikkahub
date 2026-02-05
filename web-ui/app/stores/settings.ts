import { useEffect, useRef } from "react";
import { create } from "zustand";
import { sse } from "~/services/api";
import type { Settings } from "~/types";

interface SettingsStore {
  settings: Settings | null;
  setSettings: (settings: Settings) => void;
}

export const useSettingsStore = create<SettingsStore>((set) => ({
  settings: null,
  setSettings: (settings) => set({ settings }),
}));

/**
 * Hook to subscribe to settings SSE stream (call once in root)
 */
export function useSettingsSubscription() {
  const setSettings = useSettingsStore((s) => s.setSettings);
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
      { signal: abortControllerRef.current.signal }
    );

    return () => {
      abortControllerRef.current?.abort();
    };
  }, [setSettings]);
}
