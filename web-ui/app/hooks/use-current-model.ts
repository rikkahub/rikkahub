import * as React from "react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import type { ConversationDto, ProviderModel, ProviderProfile } from "~/types";

export interface UseCurrentModelResult {
  currentModelId: string | null;
  currentModel: ProviderModel | null;
  currentProvider: ProviderProfile | null;
}

/** 会话开始后用会话上固定的模型，否则用助手当前的模型 */
export function useCurrentModel(conversation?: ConversationDto | null): UseCurrentModelResult {
  const { settings, currentAssistant } = useCurrentAssistant();

  const conversationModelId = conversation?.config?.chatModelId ?? null;
  const assistantModelId = currentAssistant?.chatModelId ?? settings?.chatModelId ?? null;

  return React.useMemo(() => {
    if (settings) {
      // 固定的模型被删除后退回助手当前的模型
      for (const modelId of [conversationModelId, assistantModelId]) {
        if (!modelId) continue;
        for (const provider of settings.providers) {
          const model = provider.models.find((item) => item.id === modelId);
          if (model) {
            return {
              currentModelId: model.id,
              currentModel: model,
              currentProvider: provider,
            };
          }
        }
      }
    }

    return {
      currentModelId: null,
      currentModel: null,
      currentProvider: null,
    };
  }, [assistantModelId, conversationModelId, settings]);
}
