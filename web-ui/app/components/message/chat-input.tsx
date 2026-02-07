import * as React from "react";

import {
  File,
  Image,
  LoaderCircle,
  Mic,
  Plus,
  Send,
  Square,
  Video,
  X,
} from "lucide-react";

import { useChatInputStore, useSettingsStore } from "~/stores";
import { Button } from "~/components/ui/button";
import { Textarea } from "~/components/ui/textarea";
import { resolveFileUrl } from "~/lib/files";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { UIMessagePart, UploadFilesResponseDto } from "~/types";

export interface ChatInputProps {
  conversationId: string | null;
  disabled?: boolean;
  isGenerating?: boolean;
  onSubmitted?: () => void;
  className?: string;
}

function toMessagePart(file: UploadFilesResponseDto["files"][number]): UIMessagePart {
  if (file.mime.startsWith("image/")) {
    return {
      type: "image",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  if (file.mime.startsWith("video/")) {
    return {
      type: "video",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  if (file.mime.startsWith("audio/")) {
    return {
      type: "audio",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  return {
    type: "document",
    url: file.url,
    fileName: file.fileName,
    mime: file.mime,
    metadata: { fileId: file.id },
  };
}

function partLabel(part: UIMessagePart): string {
  switch (part.type) {
    case "document":
      return part.fileName;
    case "image":
      return "图片";
    case "video":
      return "视频";
    case "audio":
      return "音频";
    default:
      return "附件";
  }
}

function partIcon(part: UIMessagePart) {
  switch (part.type) {
    case "image":
      return <Image className="size-3.5" />;
    case "video":
      return <Video className="size-3.5" />;
    case "audio":
      return <Mic className="size-3.5" />;
    case "document":
      return <File className="size-3.5" />;
    default:
      return <File className="size-3.5" />;
  }
}


function getPartFileId(part: UIMessagePart): number | null {
  const value = part.metadata?.fileId;
  return typeof value === "number" ? value : null;
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
  const addParts = useChatInputStore((state) => state.addParts);
  const removePartAt = useChatInputStore((state) => state.removePartAt);
  const getSubmitParts = useChatInputStore((state) => state.getSubmitParts);
  const clearDraft = useChatInputStore((state) => state.clearDraft);

  const imageInputRef = React.useRef<HTMLInputElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const uploadMenuRef = React.useRef<HTMLDivElement | null>(null);

  const [submitting, setSubmitting] = React.useState(false);
  const [uploading, setUploading] = React.useState(false);
  const [uploadMenuOpen, setUploadMenuOpen] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const text = draft?.text ?? "";
  const attachments = draft?.parts ?? [];
  const isEmpty = text.trim().length === 0 && attachments.length === 0;

  const canStop = Boolean(conversationId) && isGenerating && !disabled;
  const canSend = Boolean(conversationId) && !isGenerating && !disabled && !isEmpty;
  const canUpload = Boolean(conversationId) && !disabled && !isGenerating && !uploading && !submitting;
  const actionDisabled = submitting || uploading || (!canStop && !canSend);

  React.useEffect(() => {
    if (!uploadMenuOpen) return;

    const handlePointerDown = (event: MouseEvent) => {
      if (!uploadMenuRef.current) return;
      if (!uploadMenuRef.current.contains(event.target as Node)) {
        setUploadMenuOpen(false);
      }
    };

    document.addEventListener("mousedown", handlePointerDown);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
    };
  }, [uploadMenuOpen]);

  React.useEffect(() => {
    if (!canUpload) {
      setUploadMenuOpen(false);
    }
  }, [canUpload]);

  const uploadFiles = React.useCallback(
    async (fileList: FileList | null) => {
      if (!conversationId || !fileList || fileList.length === 0) {
        return;
      }

      const formData = new FormData();
      Array.from(fileList).forEach((file) => {
        formData.append("files", file, file.name);
      });

      setUploading(true);
      setError(null);
      try {
        const response = await api.postMultipart<UploadFilesResponseDto>("files/upload", formData);
        const parts = response.files.map(toMessagePart);
        addParts(conversationId, parts);
      } catch (uploadError) {
        const message = uploadError instanceof Error ? uploadError.message : "上传失败";
        setError(message);
      } finally {
        setUploading(false);
      }
    },
    [addParts, conversationId],
  );

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

  const handleImageInputChange = React.useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      await uploadFiles(event.target.files);
      event.currentTarget.value = "";
    },
    [uploadFiles],
  );

  const handleFileInputChange = React.useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      await uploadFiles(event.target.files);
      event.currentTarget.value = "";
    },
    [uploadFiles],
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
          {attachments.length > 0 ? (
            <div className="flex flex-wrap gap-2 px-2 pt-1">
              {attachments.map((part, index) => {
                const key = `${part.type}-${index}`;
                return (
                  <div
                    key={key}
                    className="group inline-flex max-w-[220px] items-center gap-1 rounded-full border bg-background/80 px-2 py-1 text-xs"
                  >
                    {part.type === "image" ? (
                      <img
                        alt="upload"
                        className="size-5 rounded object-cover"
                        src={resolveFileUrl(part.url)}
                      />
                    ) : (
                      partIcon(part)
                    )}
                    <span className="truncate">{partLabel(part)}</span>
                    <button
                      className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                      onClick={async () => {
                        if (!conversationId || disabled || isGenerating || submitting) return;

                        const fileId = getPartFileId(part);
                        if (fileId != null) {
                          try {
                            await api.delete<{ status: string }>(`files/${fileId}`);
                          } catch (deleteError) {
                            const message = deleteError instanceof Error ? deleteError.message : "删除附件失败";
                            setError(message);
                            return;
                          }
                        }

                        removePartAt(conversationId, index);
                      }}
                      type="button"
                    >
                      <X className="size-3" />
                    </button>
                  </div>
                );
              })}
            </div>
          ) : null}

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
            <div className="relative" ref={uploadMenuRef}>
              <input
                ref={fileInputRef}
                className="hidden"
                accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.csv,.json"
                multiple
                onChange={handleFileInputChange}
                type="file"
              />
              <input
                ref={imageInputRef}
                accept="image/*"
                className="hidden"
                multiple
                onChange={handleImageInputChange}
                type="file"
              />
              <Button
                variant="ghost"
                size="icon"
                disabled={!canUpload}
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
                onClick={() => {
                  setUploadMenuOpen((open) => !open);
                }}
              >
                <Plus className={cn("size-4 transition-transform", uploadMenuOpen && "rotate-45")} />
              </Button>

              {uploadMenuOpen ? (
                <div className="absolute bottom-10 left-0 z-20 min-w-36 rounded-lg border bg-popover p-1 shadow-md">
                  <button
                    className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
                    type="button"
                    onClick={() => {
                      imageInputRef.current?.click();
                      setUploadMenuOpen(false);
                    }}
                  >
                    <Image className="size-4" />
                    上传图片
                  </button>
                  <button
                    className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
                    type="button"
                    onClick={() => {
                      fileInputRef.current?.click();
                      setUploadMenuOpen(false);
                    }}
                  >
                    <File className="size-4" />
                    上传文档
                  </button>
                </div>
              ) : null}
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
              {submitting || uploading ? (
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
