import * as React from "react";

import { File, Image, LoaderCircle, Mic, Plus, Send, Square, Video, X, Zap } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { ModelList } from "~/components/input/model-list";
import { ReasoningPickerButton } from "~/components/input/reasoning-picker";
import { SearchPickerButton } from "~/components/input/search-picker";
import { McpPickerButton } from "~/components/input/mcp-picker";
import { useSettingsStore } from "~/stores";
import { Button } from "~/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { Textarea } from "~/components/ui/textarea";
import { resolveFileUrl } from "~/lib/files";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { UIMessagePart, UploadFilesResponseDto } from "~/types";

export interface ChatInputProps {
  value: string;
  attachments: UIMessagePart[];
  ready?: boolean;
  disabled?: boolean;
  isGenerating?: boolean;
  isEditing?: boolean;
  onValueChange: (value: string) => void;
  onAddParts: (parts: UIMessagePart[]) => void;
  shouldDeleteFileOnRemove?: (part: UIMessagePart) => boolean;
  onRemovePart: (index: number, part: UIMessagePart) => Promise<void> | void;
  onSend: () => Promise<void> | void;
  onStop?: () => Promise<void> | void;
  onCancelEdit?: () => void;
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

function partLabel(part: UIMessagePart, t: (key: string) => string): string {
  switch (part.type) {
    case "document":
      return part.fileName;
    case "image":
      return t("chat.attachment_image");
    case "video":
      return t("chat.attachment_video");
    case "audio":
      return t("chat.attachment_audio");
    default:
      return t("chat.attachment_file");
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

function hasFilesInDataTransfer(dataTransfer: DataTransfer | null): boolean {
  if (!dataTransfer) return false;
  if (dataTransfer.files.length > 0) return true;
  return Array.from(dataTransfer.items).some((item) => item.kind === "file");
}

export function ChatInput({
  value,
  attachments,
  ready = true,
  disabled = false,
  isGenerating = false,
  isEditing = false,
  onValueChange,
  onAddParts,
  shouldDeleteFileOnRemove,
  onRemovePart,
  onSend,
  onStop,
  onCancelEdit,
  className,
}: ChatInputProps) {
  const { t } = useTranslation();
  const sendOnEnter = useSettingsStore(
    (state) => state.settings?.displaySetting.sendOnEnter ?? true,
  );
  const { currentAssistant } = useCurrentAssistant();

  const quickMessages = React.useMemo(() => {
    const source = currentAssistant?.quickMessages;
    if (!Array.isArray(source)) {
      return [] as QuickMessageOption[];
    }

    return source
      .map((item) => {
        const title = typeof item?.title === "string" ? item.title.trim() : "";
        const content = typeof item?.content === "string" ? item.content.trim() : "";
        if (!content) {
          return null;
        }

        return {
          title: title || t("chat.quick_message_default_title"),
          content,
        };
      })
      .filter((item): item is QuickMessageOption => item !== null);
  }, [currentAssistant?.quickMessages, t]);

  const imageInputRef = React.useRef<HTMLInputElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const [submitting, setSubmitting] = React.useState(false);
  const [uploading, setUploading] = React.useState(false);
  const [uploadMenuOpen, setUploadMenuOpen] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [dragActive, setDragActive] = React.useState(false);
  const dragDepthRef = React.useRef(0);

  const isEmpty = value.trim().length === 0 && attachments.length === 0;

  const canStop = ready && Boolean(onStop) && isGenerating && !disabled;
  const canSend = ready && !isGenerating && !disabled && !isEmpty;
  const canUpload = ready && !disabled && !isGenerating && !uploading && !submitting;
  const canSwitchModel = ready && !disabled && !isGenerating && !uploading && !submitting;
  const canUseQuickMessage = ready && !disabled && !uploading && !submitting;
  const actionDisabled = submitting || uploading || (!canStop && !canSend);

  React.useEffect(() => {
    if (!canUpload) {
      setUploadMenuOpen(false);
      setDragActive(false);
      dragDepthRef.current = 0;
    }
  }, [canUpload]);

  const uploadFiles = React.useCallback(
    async (fileList: FileList | null) => {
      if (!ready || !fileList || fileList.length === 0) {
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
        onAddParts(parts);
      } catch (uploadError) {
        const message = uploadError instanceof Error ? uploadError.message : t("chat.upload_failed");
        setError(message);
      } finally {
        setUploading(false);
      }
    },
    [onAddParts, ready, t],
  );

  const handlePrimaryAction = React.useCallback(async () => {
    if (actionDisabled) {
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      if (canStop) {
        await onStop?.();
        return;
      }

      if (canSend) {
        await onSend();
      }
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : t("chat.send_failed");
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }, [actionDisabled, canSend, canStop, onSend, onStop, t]);

  const handleTextChange = React.useCallback(
    (event: React.ChangeEvent<HTMLTextAreaElement>) => {
      onValueChange(event.target.value);
      if (error) {
        setError(null);
      }
    },
    [error, onValueChange],
  );

  const handleQuickMessageSelect = React.useCallback(
    (content: string) => {
      if (!canUseQuickMessage || !content) {
        return;
      }

      const needLineBreak = value.length > 0 && !value.endsWith("\n");
      onValueChange(`${value}${needLineBreak ? "\n" : ""}${content}`);
      if (error) {
        setError(null);
      }
      textareaRef.current?.focus();
    },
    [canUseQuickMessage, error, onValueChange, value],
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

  const handleDragEnter = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!canUpload || !hasFilesInDataTransfer(event.dataTransfer)) return;
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current += 1;
      setDragActive(true);
    },
    [canUpload],
  );

  const handleDragOver = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!canUpload || !hasFilesInDataTransfer(event.dataTransfer)) return;
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "copy";
      if (!dragActive) {
        setDragActive(true);
      }
    },
    [canUpload, dragActive],
  );

  const handleDragLeave = React.useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) {
      setDragActive(false);
    }
  }, []);

  const handleDrop = React.useCallback(
    async (event: React.DragEvent<HTMLDivElement>) => {
      if (!hasFilesInDataTransfer(event.dataTransfer)) return;
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current = 0;
      setDragActive(false);
      if (!canUpload) return;
      await uploadFiles(event.dataTransfer.files);
    },
    [canUpload, uploadFiles],
  );

  const sendHint = sendOnEnter ? t("chat.send_hint_enter") : t("chat.send_hint_newline");
  const placeholder = ready ? t("chat.placeholder_ready") : t("chat.placeholder_not_ready");

  return (
    <div
      className={cn(
        "bg-background/95 backdrop-blur supports-backdrop-filter:bg-background/60",
        className,
      )}
    >
      <div className="mx-auto w-full max-w-3xl px-4 py-4">
        <div
          className={cn(
            "relative flex flex-col gap-2 rounded-lg border bg-muted/50 p-2 shadow-sm transition-shadow focus-within:shadow-md focus-within:ring-1 focus-within:ring-ring",
            dragActive && "border-primary/40 bg-primary/5 ring-2 ring-primary/30",
          )}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={(event) => {
            void handleDrop(event);
          }}
        >
          {dragActive ? (
            <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center rounded-2xl border-2 border-dashed border-primary/50 bg-background/80 px-4 text-center text-sm font-medium text-primary">
              {t("chat.drop_to_upload")}
            </div>
          ) : null}
          {isEditing ? (
            <div className="flex items-center justify-between rounded-xl border border-primary/30 bg-primary/5 px-3 py-2 text-xs">
              <span className="text-primary">{t("chat.editing_tip")}</span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={onCancelEdit}
                disabled={submitting || uploading}
              >
                {t("chat.cancel_edit")}
              </Button>
            </div>
          ) : null}

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
                    <span className="truncate">{partLabel(part, t)}</span>
                    <button
                      className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                      onClick={async () => {
                        if (!ready || disabled || isGenerating || submitting) return;

                        const fileId = getPartFileId(part);
                        if (fileId != null && (shouldDeleteFileOnRemove?.(part) ?? true)) {
                          try {
                            await api.delete<{ status: string }>(`files/${fileId}`);
                          } catch (deleteError) {
                            const message =
                              deleteError instanceof Error
                                ? deleteError.message
                                : t("chat.delete_attachment_failed");
                            setError(message);
                            return;
                          }
                        }

                        await onRemovePart(index, part);
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
            ref={textareaRef}
            value={value}
            onChange={handleTextChange}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            disabled={!ready || disabled}
            className="min-h-[60px] max-h-[200px] resize-none border-0 bg-transparent p-2 text-sm shadow-none focus-visible:ring-0"
            rows={2}
          />
          <div className="flex items-center justify-between gap-2">
            <div className="flex min-w-0 items-center gap-1">
              <DropdownMenu open={uploadMenuOpen} onOpenChange={setUploadMenuOpen}>
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
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={!canUpload}
                    className="size-8 rounded-full text-muted-foreground hover:text-foreground"
                  >
                    <Plus
                      className={cn("size-4 transition-transform", uploadMenuOpen && "rotate-45")}
                    />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent className="min-w-36" side="top" align="start">
                  <DropdownMenuItem
                    onClick={() => {
                      imageInputRef.current?.click();
                    }}
                  >
                    <Image className="size-4" />
                    {t("chat.upload_image")}
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    onClick={() => {
                      fileInputRef.current?.click();
                    }}
                  >
                    <File className="size-4" />
                    {t("chat.upload_document")}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
              <QuickMessageButton
                quickMessages={quickMessages}
                disabled={!canUseQuickMessage}
                onSelect={handleQuickMessageSelect}
              />
              <ModelList disabled={!canSwitchModel} className="max-w-64" />
              <ReasoningPickerButton disabled={!canSwitchModel} />
              <McpPickerButton disabled={!canSwitchModel} />
              <SearchPickerButton disabled={!canSwitchModel} />
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

type QuickMessageOption = {
  title: string;
  content: string;
};

interface QuickMessageButtonProps {
  quickMessages: QuickMessageOption[];
  disabled?: boolean;
  onSelect: (content: string) => void;
}

function QuickMessageButton({
  quickMessages,
  disabled = false,
  onSelect,
}: QuickMessageButtonProps) {
  if (quickMessages.length === 0) {
    return null;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          disabled={disabled}
          className="size-8 rounded-full text-muted-foreground hover:text-foreground"
        >
          <Zap className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-72" side="top" align="start">
        {quickMessages.map((quickMessage, index) => {
          const key = `${quickMessage.title}-${index}`;
          return (
            <DropdownMenuItem
              key={key}
              className="items-start"
              onClick={() => {
                onSelect(quickMessage.content);
              }}
            >
              <div className="min-w-0">
                <div className="truncate text-sm font-medium">{quickMessage.title}</div>
                <div className="text-muted-foreground mt-0.5 line-clamp-2 text-xs">
                  {quickMessage.content}
                </div>
              </div>
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
