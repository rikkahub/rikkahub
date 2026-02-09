import * as React from "react";

import {
  ArrowDown,
  ArrowUp,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Copy,
  Ellipsis,
  GitFork,
  Pencil,
  RefreshCw,
  Trash2,
  Zap,
} from "lucide-react";

import { useSettingsStore } from "~/stores";
import type { MessageDto, MessageNodeDto, TokenUsage, UIMessagePart } from "~/types";

import { cn } from "~/lib/utils";
import { Button } from "~/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { ChatMessageAnnotationsRow } from "./chat-message-annotations";
import { MessageParts } from "./message-part";

interface ChatMessageProps {
  node: MessageNodeDto;
  message: MessageDto;
  loading?: boolean;
  isLastMessage?: boolean;
  onEdit?: (message: MessageDto) => void | Promise<void>;
  onRegenerate?: (messageId: string) => void | Promise<void>;
  onSelectBranch?: (nodeId: string, selectIndex: number) => void | Promise<void>;
  onDelete?: (messageId: string) => void | Promise<void>;
  onFork?: (messageId: string) => void | Promise<void>;
  onToolApproval?: (toolCallId: string, approved: boolean, reason: string) => void | Promise<void>;
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

function hasEditableContent(parts: UIMessagePart[]): boolean {
  return parts.some(
    (part) =>
      part.type === "text" ||
      part.type === "image" ||
      part.type === "video" ||
      part.type === "audio" ||
      part.type === "document",
  );
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
  node,
  message,
  loading,
  alignRight,
  onEdit,
  onRegenerate,
  onSelectBranch,
  onDelete,
  onFork,
}: {
  node: MessageNodeDto;
  message: MessageDto;
  loading: boolean;
  alignRight: boolean;
  onEdit?: (message: MessageDto) => void | Promise<void>;
  onRegenerate?: (messageId: string) => void | Promise<void>;
  onSelectBranch?: (nodeId: string, selectIndex: number) => void | Promise<void>;
  onDelete?: (messageId: string) => void | Promise<void>;
  onFork?: (messageId: string) => void | Promise<void>;
}) {
  const [regenerating, setRegenerating] = React.useState(false);
  const [switchingBranch, setSwitchingBranch] = React.useState(false);
  const [deleting, setDeleting] = React.useState(false);
  const [forking, setForking] = React.useState(false);

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

  const handleSwitchBranch = React.useCallback(
    async (selectIndex: number) => {
      if (!onSelectBranch) return;
      if (selectIndex < 0 || selectIndex > node.messages.length - 1) return;
      if (selectIndex === node.selectIndex) return;

      setSwitchingBranch(true);
      try {
        await onSelectBranch(node.id, selectIndex);
      } finally {
        setSwitchingBranch(false);
      }
    },
    [node.id, node.messages.length, node.selectIndex, onSelectBranch],
  );

  const handleDelete = React.useCallback(async () => {
    if (!onDelete) return;

    const confirmed = window.confirm("确认删除这条消息吗？");
    if (!confirmed) return;

    setDeleting(true);
    try {
      await onDelete(message.id);
    } finally {
      setDeleting(false);
    }
  }, [message.id, onDelete]);

  const handleFork = React.useCallback(async () => {
    if (!onFork) return;

    setForking(true);
    try {
      await onFork(message.id);
    } finally {
      setForking(false);
    }
  }, [message.id, onFork]);

  const canSwitchBranch = Boolean(onSelectBranch) && node.messages.length > 1;
  const canEdit =
    Boolean(onEdit) &&
    (message.role === "USER" || message.role === "ASSISTANT") &&
    hasEditableContent(message.parts);
  const actionDisabled = loading || switchingBranch || regenerating || deleting || forking;

  return (
    <div
      className={cn(
        "flex w-full items-center gap-1 px-1",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      <Button
        aria-label="复制消息"
        disabled={actionDisabled}
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

      {canEdit && (
        <Button
          aria-label="编辑消息"
          disabled={actionDisabled}
          onClick={() => {
            void onEdit?.(message);
          }}
          size="icon-xs"
          title="编辑"
          type="button"
          variant="ghost"
        >
          <Pencil className="size-3.5" />
        </Button>
      )}

      {onRegenerate && (
        <Button
          aria-label="重新生成"
          disabled={actionDisabled}
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

      {canSwitchBranch && (
        <>
          <Button
            aria-label="上一分支"
            disabled={actionDisabled || node.selectIndex <= 0}
            onClick={() => {
              void handleSwitchBranch(node.selectIndex - 1);
            }}
            size="icon-xs"
            title="上一分支"
            type="button"
            variant="ghost"
          >
            <ChevronLeft className="size-3.5" />
          </Button>
          <span className="text-[11px] text-muted-foreground">
            {node.selectIndex + 1}/{node.messages.length}
          </span>
          <Button
            aria-label="下一分支"
            disabled={actionDisabled || node.selectIndex >= node.messages.length - 1}
            onClick={() => {
              void handleSwitchBranch(node.selectIndex + 1);
            }}
            size="icon-xs"
            title="下一分支"
            type="button"
            variant="ghost"
          >
            <ChevronRight className="size-3.5" />
          </Button>
        </>
      )}

      {onDelete && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              aria-label="更多操作"
              disabled={actionDisabled}
              size="icon-xs"
              title="更多操作"
              type="button"
              variant="ghost"
            >
              <Ellipsis className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align={alignRight ? "end" : "start"}>
            {onFork && (
              <DropdownMenuItem
                disabled={actionDisabled}
                onSelect={() => {
                  void handleFork();
                }}
              >
                <GitFork className="size-3.5" />
                创建分叉
              </DropdownMenuItem>
            )}
            <DropdownMenuItem
              variant="destructive"
              disabled={actionDisabled}
              onSelect={() => {
                void handleDelete();
              }}
            >
              <Trash2 className="size-3.5" />
              删除
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
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
        "flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-1 text-[11px] text-muted-foreground/50",
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
  node,
  message,
  loading = false,
  isLastMessage = false,
  onEdit,
  onRegenerate,
  onSelectBranch,
  onDelete,
  onFork,
  onToolApproval,
}: ChatMessageProps) {
  const isUser = message.role === "USER";
  const hasMessageContent = message.parts.some(hasRenderablePart);
  const showActions = isLastMessage ? !loading : hasMessageContent;

  return (
    <div className={cn("flex flex-col gap-4", isUser ? "items-end" : "items-start")}>
      <div className={cn("flex w-full", isUser ? "justify-end" : "justify-start")}>
        <div
          className={cn(
            "flex flex-col gap-2 text-sm",
            isUser ? "max-w-[85%] rounded-lg bg-muted px-4 py-3" : "w-full",
          )}
        >
          <MessageParts parts={message.parts} loading={loading} onToolApproval={onToolApproval} />
        </div>
      </div>

      {showActions && (
        <ChatMessageActionsRow
          node={node}
          message={message}
          loading={loading}
          alignRight={isUser}
          onEdit={onEdit}
          onRegenerate={onRegenerate}
          onSelectBranch={onSelectBranch}
          onDelete={onDelete}
          onFork={onFork}
        />
      )}

      <ChatMessageAnnotationsRow annotations={message.annotations} alignRight={isUser} />

      <ChatMessageNerdLineRow message={message} alignRight={isUser} />
    </div>
  );
}
