import * as React from "react";

import { ArrowDown, ArrowUp, Clock3, Copy, RefreshCw, Zap } from "lucide-react";

import { useSettingsStore } from "~/stores/settings";
import type { MessageDto, TokenUsage, UIMessagePart } from "~/types";

import { cn } from "~/lib/utils";
import { Button } from "~/components/ui/button";
import { MessageParts } from "./message-part";

interface ChatMessageProps {
  message: MessageDto;
  loading?: boolean;
  isLastMessage?: boolean;
  onRegenerate?: (messageId: string) => void | Promise<void>;
  onToolApproval?: (
    toolCallId: string,
    approved: boolean,
    reason: string,
  ) => void | Promise<void>;
}

function hasRenderablePart(part: UIMessagePart): boolean {
  switch (part.type) {
    case "text":
      return part.text.trim().length > 0;
    case "image":
    case "video":
    case "audio":
      return part.url.trim().length > 0;
    case "document":
      return part.url.trim().length > 0 || part.fileName.trim().length > 0;
    case "reasoning":
      return part.reasoning.trim().length > 0;
    case "tool":
      return true;
  }
}

function formatPartForCopy(part: UIMessagePart): string | null {
  switch (part.type) {
    case "text":
      return part.text;
    case "image":
      return `[图片] ${part.url}`;
    case "video":
      return `[视频] ${part.url}`;
    case "audio":
      return `[音频] ${part.url}`;
    case "document":
      return `[文档] ${part.fileName}`;
    case "reasoning":
      return part.reasoning;
    case "tool":
      return `[工具] ${part.toolName}`;
  }
}

function buildCopyText(parts: UIMessagePart[]): string {
  return parts
    .map(formatPartForCopy)
    .filter((value): value is string => Boolean(value && value.trim().length > 0))
    .join("\n\n")
    .trim();
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat().format(value);
}

function getDurationMs(createdAt: string, finishedAt?: string | null): number | null {
  const start = Date.parse(createdAt);
  if (Number.isNaN(start)) return null;

  const end = finishedAt ? Date.parse(finishedAt) : Date.now();
  if (Number.isNaN(end) || end <= start) return null;

  return end - start;
}

function getNerdStats(usage: TokenUsage, createdAt: string, finishedAt?: string | null) {
  const stats: Array<{ key: string; icon: React.ReactNode; label: string }> = [];

  stats.push({
    key: "prompt",
    icon: <ArrowUp className="size-3" />,
    label:
      usage.cachedTokens > 0
        ? `${formatNumber(usage.promptTokens)} tokens (${formatNumber(usage.cachedTokens)} cached)`
        : `${formatNumber(usage.promptTokens)} tokens`,
  });

  stats.push({
    key: "completion",
    icon: <ArrowDown className="size-3" />,
    label: `${formatNumber(usage.completionTokens)} tokens`,
  });

  const durationMs = getDurationMs(createdAt, finishedAt);
  if (durationMs && usage.completionTokens > 0) {
    const durationSeconds = durationMs / 1000;
    const tps = usage.completionTokens / durationSeconds;

    stats.push({
      key: "speed",
      icon: <Zap className="size-3" />,
      label: `${tps.toFixed(1)} tok/s`,
    });

    stats.push({
      key: "duration",
      icon: <Clock3 className="size-3" />,
      label: `${durationSeconds.toFixed(1)}s`,
    });
  }

  return stats;
}

function ChatMessageActionsRow({
  message,
  loading,
  alignRight,
  onRegenerate,
}: {
  message: MessageDto;
  loading: boolean;
  alignRight: boolean;
  onRegenerate?: (messageId: string) => void | Promise<void>;
}) {
  const [regenerating, setRegenerating] = React.useState(false);

  const handleCopy = React.useCallback(async () => {
    const text = buildCopyText(message.parts);
    if (!text || typeof navigator === "undefined" || !navigator.clipboard) return;
    await navigator.clipboard.writeText(text);
  }, [message.parts]);

  const handleRegenerate = React.useCallback(async () => {
    if (!onRegenerate) return;

    if (message.role === "USER") {
      const confirmed = window.confirm("将从这条用户消息重新生成，确认继续吗？");
      if (!confirmed) return;
    }

    setRegenerating(true);
    try {
      await onRegenerate(message.id);
    } finally {
      setRegenerating(false);
    }
  }, [message.id, message.role, onRegenerate]);

  return (
    <div
      className={cn(
        "flex w-full items-center gap-1 px-1",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      <Button
        aria-label="复制消息"
        onClick={() => {
          void handleCopy();
        }}
        size="icon-xs"
        title="复制"
        type="button"
        variant="ghost"
      >
        <Copy className="size-3.5" />
      </Button>

      {onRegenerate && (
        <Button
          aria-label="重新生成"
          disabled={loading || regenerating}
          onClick={() => {
            void handleRegenerate();
          }}
          size="icon-xs"
          title="重新生成"
          type="button"
          variant="ghost"
        >
          <RefreshCw className={cn("size-3.5", regenerating && "animate-spin")} />
        </Button>
      )}
    </div>
  );
}

function ChatMessageNerdLineRow({
  message,
  alignRight,
}: {
  message: MessageDto;
  alignRight: boolean;
}) {
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);

  if (!displaySetting?.showTokenUsage || !message.usage) {
    return null;
  }

  const stats = getNerdStats(message.usage, message.createdAt, message.finishedAt);
  if (stats.length === 0) return null;

  return (
    <div
      className={cn(
        "flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-1 text-[11px] text-muted-foreground",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      {stats.map((item) => (
        <div key={item.key} className="inline-flex items-center gap-1">
          {item.icon}
          <span>{item.label}</span>
        </div>
      ))}
    </div>
  );
}

export function ChatMessage({
  message,
  loading = false,
  isLastMessage = false,
  onRegenerate,
  onToolApproval,
}: ChatMessageProps) {
  const isUser = message.role === "USER";
  const hasMessageContent = message.parts.some(hasRenderablePart);
  const showActions = isLastMessage ? !loading : hasMessageContent;

  return (
    <div className={cn("flex flex-col gap-1", isUser ? "items-end" : "items-start")}>
      <div className={cn("flex w-full", isUser ? "justify-end" : "justify-start")}>
        <div
          className={cn(
            "flex flex-col gap-2 text-sm",
            isUser ? "max-w-[85%] rounded-2xl bg-muted px-4 py-3" : "w-full",
          )}
        >
          <MessageParts
            parts={message.parts}
            loading={loading}
            onToolApproval={onToolApproval}
          />
        </div>
      </div>

      {showActions && (
        <ChatMessageActionsRow
          message={message}
          loading={loading}
          alignRight={isUser}
          onRegenerate={onRegenerate}
        />
      )}

      <ChatMessageNerdLineRow message={message} alignRight={isUser} />
    </div>
  );
}

