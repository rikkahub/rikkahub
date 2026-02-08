import * as React from "react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import type { ProviderModel, ProviderProfile } from "~/types";

export interface UseCurrentModelResult {
  currentModelId: string | null;
  currentModel: ProviderModel | null;
  currentProvider: ProviderProfile | null;
}

export function useCurrentModel(): UseCurrentModelResult {
  const { settings, currentAssistant } = useCurrentAssistant();

  const currentModelId = currentAssistant?.chatModelId ?? settings?.chatModelId ?? null;

  const { currentModel, currentProvider } = React.useMemo(() => {
    if (!settings || !currentModelId) {
      return {
        currentModel: null,
        currentProvider: null,
      };
    }

    for (const provider of settings.providers) {
      const model = provider.models.find((item) => item.id === currentModelId);
      if (model) {
        return {
          currentModel: model,
          currentProvider: provider,
        };
      }
    }

    return {
      currentModel: null,
      currentProvider: null,
    };
  }, [currentModelId, settings]);

  return {
    currentModelId,
    currentModel,
    currentProvider,
  };
}
