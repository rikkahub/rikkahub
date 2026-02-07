import * as React from "react";

import { Image, LoaderCircle, Mic, Paperclip, Send, Sparkles, Square } from "lucide-react";

import { Button } from "~/components/ui/button";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import { useChatInputStore } from "~/stores";
import { useSettingsStore } from "~/stores";

export interface ChatInputProps {
  conversationId: string | null;
  disabled?: boolean;
  isGenerating?: boolean;
  onSubmitted?: () => void;
  className?: string;
}

export function ChatInput({
  conversationId,
  disabled = false,
  isGenerating = false,
  onSubmitted,
  className,
}: ChatInputProps) {
  const sendOnEnter = useSettingsStore((state) => state.settings?.displaySetting.sendOnEnter ?? true);
  const draft = useChatInputStore(
    React.useCallback(
      (state) => (conversationId ? state.drafts[conversationId] : undefined),
      [conversationId],
    ),
  );
  const setText = useChatInputStore((state) => state.setText);
  const getSubmitParts = useChatInputStore((state) => state.getSubmitParts);
  const clearDraft = useChatInputStore((state) => state.clearDraft);

  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const text = draft?.text ?? "";
  const hasAttachments = (draft?.parts.length ?? 0) > 0;
  const isEmpty = text.trim().length === 0 && !hasAttachments;

  const canStop = Boolean(conversationId) && isGenerating && !disabled;
  const canSend = Boolean(conversationId) && !isGenerating && !disabled && !isEmpty;
  const actionDisabled = submitting || (!canStop && !canSend);

  const handlePrimaryAction = React.useCallback(async () => {
    if (!conversationId || actionDisabled) {
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      if (isGenerating) {
        await api.post<{ status: string }>(`conversations/${conversationId}/stop`);
        return;
      }

      const parts = getSubmitParts(conversationId);
      if (parts.length === 0) {
        return;
      }

      await api.post<{ status: string }>(`conversations/${conversationId}/messages`, {
        content: text,
        parts,
      });

      clearDraft(conversationId);
      onSubmitted?.();
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : "发送失败";
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }, [actionDisabled, clearDraft, conversationId, getSubmitParts, isGenerating, onSubmitted, text]);

  const handleTextChange = React.useCallback(
    (event: React.ChangeEvent<HTMLTextAreaElement>) => {
      if (!conversationId) return;

      setText(conversationId, event.target.value);
      if (error) {
        setError(null);
      }
    },
    [conversationId, error, setText],
  );

  const handleKeyDown = React.useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.key !== "Enter") return;
      if (!sendOnEnter || isGenerating) return;
      if (event.shiftKey || event.nativeEvent.isComposing) return;

      event.preventDefault();
      void handlePrimaryAction();
    },
    [handlePrimaryAction, isGenerating, sendOnEnter],
  );

  const sendHint = sendOnEnter ? "按 Enter 发送，Shift + Enter 换行" : "按 Enter 换行";
  const placeholder = conversationId ? "输入消息..." : "请先选择会话";

  return (
    <div
      className={cn(
        "bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60",
        className,
      )}
    >
      <div className="mx-auto w-full max-w-3xl px-4 py-4">
        <div className="relative flex flex-col gap-2 rounded-2xl border bg-muted/50 p-2 shadow-sm transition-shadow focus-within:shadow-md focus-within:ring-1 focus-within:ring-ring">
          <Textarea
            value={text}
            onChange={handleTextChange}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            disabled={!conversationId || disabled}
            className="min-h-[60px] max-h-[200px] resize-none border-0 bg-transparent p-2 text-sm shadow-none focus-visible:ring-0"
            rows={2}
          />
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon"
                disabled
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              >
                <Paperclip className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                disabled
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              >
                <Image className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                disabled
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              >
                <Mic className="size-4" />
              </Button>
              <div className="mx-2 h-4 w-px bg-border" />
              <Button
                variant="ghost"
                size="sm"
                disabled
                className="h-8 gap-1.5 rounded-full px-3 text-xs text-muted-foreground hover:text-foreground"
              >
                <Sparkles className="size-3.5" />
                <span>增强</span>
              </Button>
            </div>
            <Button
              onClick={() => {
                void handlePrimaryAction();
              }}
              disabled={actionDisabled}
              size="icon"
              className={cn(
                "size-9 rounded-full shadow-sm",
                isGenerating && !submitting
                  ? "bg-destructive text-destructive-foreground hover:bg-destructive/90"
                  : "bg-primary text-primary-foreground hover:bg-primary/90",
              )}
            >
              {submitting ? (
                <LoaderCircle className="size-4 animate-spin" />
              ) : isGenerating ? (
                <Square className="size-4" />
              ) : (
                <Send className="size-4" />
              )}
            </Button>
          </div>
        </div>
        <p className="mt-2 text-center text-xs text-muted-foreground">{sendHint}</p>
        {error ? <p className="mt-1 text-center text-xs text-destructive">{error}</p> : null}
      </div>
    </div>
  );
}
