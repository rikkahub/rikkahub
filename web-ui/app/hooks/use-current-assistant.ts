import * as React from "react";

import { useSettingsStore } from "~/stores";
import type { AssistantProfile, Settings } from "~/types";

export interface UseCurrentAssistantResult {
  settings: Settings | null;
  assistants: AssistantProfile[];
  currentAssistantId: string | null;
  currentAssistant: AssistantProfile | null;
}

export function useCurrentAssistant(): UseCurrentAssistantResult {
  const settings = useSettingsStore((state) => state.settings);

  const assistants = settings?.assistants ?? [];
  const currentAssistantId = settings?.assistantId ?? null;

  const currentAssistant = React.useMemo(() => {
    if (assistants.length === 0) {
      return null;
    }

    return (
      assistants.find((assistant) => assistant.id === currentAssistantId) ?? assistants[0] ?? null
    );
  }, [assistants, currentAssistantId]);

  return {
    settings,
    assistants,
    currentAssistantId,
    currentAssistant,
  };
}
