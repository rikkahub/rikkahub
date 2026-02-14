import { useTranslation } from "react-i18next";

import { AIIcon } from "~/components/ui/ai-icon";
import { UIAvatar } from "~/components/ui/ui-avatar";
import { useSettingsStore } from "~/stores";
import type { AssistantProfile, MessageDto, ProviderModel } from "~/types";

export interface ChatMessageAvatarRowProps {
  message: MessageDto;
  previousRole?: string | null;
  hasMessageContent: boolean;
  loading: boolean;
  assistant?: AssistantProfile | null;
  model?: ProviderModel | null;
}

function formatMessageTimestamp(createdAt: string, locale?: string): string | null {
  const timestamp = Date.parse(createdAt);
  if (Number.isNaN(timestamp)) return null;

  return new Intl.DateTimeFormat(locale || undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(timestamp);
}

export function ChatMessageAvatarRow({
  message,
  previousRole,
  hasMessageContent,
  loading,
  assistant,
  model,
}: ChatMessageAvatarRowProps) {
  const { t, i18n } = useTranslation(["common", "page"]);
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);

  if (!hasMessageContent) {
    return null;
  }

  const createdAtLabel = formatMessageTimestamp(message.createdAt, i18n.language);

  if (message.role === "USER") {
    if (previousRole === "USER" || !displaySetting?.showUserAvatar) {
      return null;
    }

    const userName =
      displaySetting.userNickname.trim() ||
      t("page:conversations.user.default_name", { defaultValue: "User" });

    return (
      <div className="flex w-full justify-end px-1">
        <div className="flex items-center gap-2">
          <div className="min-w-0 text-right">
            <div className="truncate text-sm font-medium text-foreground/90">{userName}</div>
            {createdAtLabel ? (
              <div className="truncate text-xs text-muted-foreground/80">{createdAtLabel}</div>
            ) : null}
          </div>
          <UIAvatar name={userName} avatar={displaySetting.userAvatar} className="size-9" />
        </div>
      </div>
    );
  }

  if (message.role !== "ASSISTANT" || previousRole === "ASSISTANT" || !model) {
    return null;
  }

  const showModelIcon = displaySetting?.showModelIcon !== false;
  const showModelName = displaySetting?.showModelName === true;
  if (!showModelIcon && !showModelName) {
    return null;
  }

  const useAssistantAvatar = assistant?.useAssistantAvatar === true;
  const defaultAssistantName = t("common:quick_jump.role_assistant", { defaultValue: "Assistant" });
  const assistantName = assistant?.name?.trim() || defaultAssistantName;
  const modelName = model.displayName.trim() || model.modelId.trim() || defaultAssistantName;
  const title = useAssistantAvatar ? assistantName : modelName;

  return (
    <div className="flex w-full justify-start px-1">
      <div className="flex min-w-0 items-center gap-2">
        {showModelIcon ? (
          useAssistantAvatar ? (
            <UIAvatar name={assistantName} avatar={assistant?.avatar} className="size-9" />
          ) : (
            <AIIcon
              name={model.modelId}
              size={36}
              loading={loading}
              className="bg-secondary"
              imageClassName="h-[72%] w-[72%]"
            />
          )
        ) : null}
        {showModelName ? (
          <div className="min-w-0">
            <div className="truncate text-sm font-medium text-foreground/90">{title}</div>
            {createdAtLabel ? (
              <div className="truncate text-xs text-muted-foreground/80">{createdAtLabel}</div>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
