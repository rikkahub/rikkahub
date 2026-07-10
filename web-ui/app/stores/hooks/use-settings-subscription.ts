import { useEffect } from "react";

import { EVENT_SETTINGS, subscribeToEvent } from "~/services/events";
import { useSettingsStore } from "~/stores/app-store";
import type { Settings } from "~/types";

/**
 * Hook to subscribe to settings updates on the shared events stream (call once in root).
 */
export function useSettingsSubscription() {
  const setSettings = useSettingsStore((state) => state.setSettings);

  useEffect(() => {
    return subscribeToEvent<Settings>(EVENT_SETTINGS, (data) => {
      setSettings(data);
    });
  }, [setSettings]);
}
