import * as React from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import {
  BookHeart,
  BookX,
  Check,
  Clipboard,
  ClipboardPaste,
  Clock3,
  Globe,
  Loader2,
  Search,
  Wrench,
  X,
} from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import { Button } from "~/components/ui/button";
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
} from "~/components/ui/drawer";
import { useIsMobile } from "~/hooks/use-mobile";
import type { TextPart as UITextPart, ToolPart as UIToolPart } from "~/types";

import { ControlledChainOfThoughtStep } from "../chain-of-thought";

interface ToolPartProps {
  tool: UIToolPart;
  loading?: boolean;
  onToolApproval?: (toolCallId: string, approved: boolean, reason: string) => void | Promise<void>;
  isFirst?: boolean;
  isLast?: boolean;
}

const TOOL_NAMES = {
  MEMORY: "memory_tool",
  SEARCH_WEB: "search_web",
  SCRAPE_WEB: "scrape_web",
  GET_TIME_INFO: "get_time_info",
  CLIPBOARD: "clipboard_tool",
} as const;

const MEMORY_ACTIONS = {
  CREATE: "create",
  EDIT: "edit",
  DELETE: "delete",
} as const;

const CLIPBOARD_ACTIONS = {
  READ: "read",
  WRITE: "write",
} as const;

function safeJsonParse(input: string): unknown {
  if (!input.trim()) return {};
  try {
    return JSON.parse(input);
  } catch {
    return {};
  }
}

function toJsonString(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function getStringField(data: unknown, key: string): string | undefined {
  if (!data || typeof data !== "object" || Array.isArray(data)) return undefined;
  const value = (data as Record<string, unknown>)[key];
  return typeof value === "string" ? value : undefined;
}

function getArrayField(data: unknown, key: string): unknown[] {
  if (!data || typeof data !== "object" || Array.isArray(data)) return [];
  const value = (data as Record<string, unknown>)[key];
  return Array.isArray(value) ? value : [];
}

function getToolIcon(toolName: string, action?: string) {
  if (toolName === TOOL_NAMES.MEMORY) {
    if (action === MEMORY_ACTIONS.CREATE || action === MEMORY_ACTIONS.EDIT) {
      return BookHeart;
    }
    if (action === MEMORY_ACTIONS.DELETE) {
      return BookX;
    }
    return Wrench;
  }

  if (toolName === TOOL_NAMES.SEARCH_WEB) return Search;
  if (toolName === TOOL_NAMES.SCRAPE_WEB) return Globe;
  if (toolName === TOOL_NAMES.GET_TIME_INFO) return Clock3;

  if (toolName === TOOL_NAMES.CLIPBOARD) {
    if (action === CLIPBOARD_ACTIONS.WRITE) return ClipboardPaste;
    return Clipboard;
  }

  return Wrench;
}

function getToolTitle(toolName: string, args: unknown, t: TFunction): string {
  const action = getStringField(args, "action");

  if (toolName === TOOL_NAMES.MEMORY) {
    if (action === MEMORY_ACTIONS.CREATE) return t("tool_part.memory_create");
    if (action === MEMORY_ACTIONS.EDIT) return t("tool_part.memory_edit");
    if (action === MEMORY_ACTIONS.DELETE) return t("tool_part.memory_delete");
  }

  if (toolName === TOOL_NAMES.SEARCH_WEB) {
    const query = getStringField(args, "query") ?? "";
    return query ? t("tool_part.search_web_with_query", { query }) : t("tool_part.search_web");
  }

  if (toolName === TOOL_NAMES.SCRAPE_WEB) return t("tool_part.scrape_web");
  if (toolName === TOOL_NAMES.GET_TIME_INFO) return t("tool_part.get_time_info");

  if (toolName === TOOL_NAMES.CLIPBOARD) {
    if (action === CLIPBOARD_ACTIONS.READ) return t("tool_part.clipboard_read");
    if (action === CLIPBOARD_ACTIONS.WRITE) return t("tool_part.clipboard_write");
  }

  return t("tool_part.tool_call_with_name", { toolName });
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-64 overflow-auto rounded-md border bg-muted/30 p-3 text-xs">
      {toJsonString(value)}
    </pre>
  );
}

function SearchWebPreview({ args, content }: { args: unknown; content: unknown }) {
  const { t } = useTranslation("message");
  const query = getStringField(args, "query") ?? "";
  const answer = getStringField(content, "answer");
  const items = getArrayField(content, "items");

  return (
    <div className="space-y-3">
      <div className="text-sm">
        {t("tool_part.search_query_label", { query: query || t("tool_part.empty") })}
      </div>
      {answer && (
        <div className="rounded-lg border bg-primary/5 p-3">
          <Markdown content={answer} className="text-sm" />
        </div>
      )}

      {items.length > 0 ? (
        <div className="space-y-2">
          {items.map((item, index) => {
            if (!item || typeof item !== "object" || Array.isArray(item)) {
              return null;
            }

            const record = item as Record<string, unknown>;
            const url = typeof record.url === "string" ? record.url : "";
            const title = typeof record.title === "string" ? record.title : "";
            const text = typeof record.text === "string" ? record.text : "";

            if (!url) return null;

            return (
              <a
                key={`${url}-${index}`}
                className="block rounded-lg border border-muted bg-card p-3 hover:bg-muted/40"
                href={url}
                rel="noreferrer"
                target="_blank"
              >
                <div className="line-clamp-1 font-medium text-sm">{title || url}</div>
                {text && (
                  <div className="mt-1 line-clamp-3 text-muted-foreground text-xs">{text}</div>
                )}
                <div className="mt-2 line-clamp-1 text-primary text-xs">{url}</div>
              </a>
            );
          })}
        </div>
      ) : (
        <JsonBlock value={content} />
      )}
    </div>
  );
}

function ScrapeWebPreview({ content }: { content: unknown }) {
  const urls = getArrayField(content, "urls");

  if (urls.length === 0) {
    return <JsonBlock value={content} />;
  }

  return (
    <div className="space-y-3">
      {urls.map((item, index) => {
        if (!item || typeof item !== "object" || Array.isArray(item)) {
          return null;
        }

        const record = item as Record<string, unknown>;
        const url = typeof record.url === "string" ? record.url : "";
        const text = typeof record.content === "string" ? record.content : "";

        return (
          <div key={`${url}-${index}`} className="space-y-2 rounded-lg border p-3">
            <div className="line-clamp-1 text-muted-foreground text-xs">{url}</div>
            <div className="rounded-md border bg-muted/20 p-2">
              <Markdown content={text} className="text-sm" />
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function ToolPart({
  tool,
  loading = false,
  onToolApproval,
  isFirst,
  isLast,
}: ToolPartProps) {
  const { t } = useTranslation("message");
  const isMobile = useIsMobile();
  const [expanded, setExpanded] = React.useState(true);
  const [drawerOpen, setDrawerOpen] = React.useState(false);

  const args = React.useMemo(() => safeJsonParse(tool.input), [tool.input]);

  const outputText = React.useMemo(
    () =>
      tool.output
        .filter((part): part is UITextPart => part.type === "text")
        .map((part) => part.text)
        .join("\n"),
    [tool.output],
  );

  const outputContent = React.useMemo(() => safeJsonParse(outputText), [outputText]);

  const memoryAction = getStringField(args, "action");
  const title = getToolTitle(tool.toolName, args, t);
  const isPending = tool.approvalState.type === "pending";
  const isDenied = tool.approvalState.type === "denied";
  const deniedReason =
    tool.approvalState.type === "denied" ? (tool.approvalState.reason ?? "") : "";
  const isExecuted = tool.output.length > 0;

  const hasExtraContent =
    (tool.toolName === TOOL_NAMES.MEMORY &&
      (memoryAction === MEMORY_ACTIONS.CREATE || memoryAction === MEMORY_ACTIONS.EDIT) &&
      Boolean(getStringField(outputContent, "content"))) ||
    (tool.toolName === TOOL_NAMES.SEARCH_WEB &&
      (Boolean(getStringField(outputContent, "answer")) ||
        getArrayField(outputContent, "items").length > 0)) ||
    (tool.toolName === TOOL_NAMES.SCRAPE_WEB && Boolean(getStringField(args, "url"))) ||
    isDenied;

  const canOpenDrawer = isPending || isExecuted;
  const Icon = getToolIcon(tool.toolName, memoryAction);

  const handleApprove = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!onToolApproval) return;
    await onToolApproval(tool.toolCallId, true, "");
  };

  const handleDeny = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!onToolApproval) return;
    const reason = window.prompt(t("tool_part.deny_reason_prompt"), "");
    if (reason === null) return;
    await onToolApproval(tool.toolCallId, false, reason);
  };

  return (
    <>
      <ControlledChainOfThoughtStep
        expanded={expanded}
        onExpandedChange={setExpanded}
        isFirst={isFirst}
        isLast={isLast}
        icon={
          loading ? (
            <Loader2 className="h-4 w-4 animate-spin text-primary" />
          ) : (
            <Icon className="h-4 w-4 text-primary" />
          )
        }
        label={<span className="text-foreground line-clamp-2 text-sm font-medium">{title}</span>}
        extra={
          isPending && onToolApproval ? (
            <div className="flex items-center gap-1">
              <Button onClick={handleDeny} size="icon-xs" type="button" variant="secondary">
                <X className="h-3.5 w-3.5" />
              </Button>
              <Button onClick={handleApprove} size="icon-xs" type="button" variant="secondary">
                <Check className="h-3.5 w-3.5" />
              </Button>
            </div>
          ) : undefined
        }
        onClick={canOpenDrawer ? () => setDrawerOpen(true) : undefined}
      >
        {hasExtraContent && (
          <div className="space-y-1">
            {tool.toolName === TOOL_NAMES.MEMORY &&
              (memoryAction === MEMORY_ACTIONS.CREATE || memoryAction === MEMORY_ACTIONS.EDIT) && (
                <div className="line-clamp-3 text-muted-foreground text-xs">
                  {getStringField(outputContent, "content")}
                </div>
              )}

            {tool.toolName === TOOL_NAMES.SEARCH_WEB && getStringField(outputContent, "answer") && (
              <div className="line-clamp-3 text-muted-foreground text-xs">
                {getStringField(outputContent, "answer")}
              </div>
            )}

            {tool.toolName === TOOL_NAMES.SEARCH_WEB &&
              getArrayField(outputContent, "items").length > 0 && (
                <div className="text-muted-foreground text-xs">
                  {t("tool_part.search_results_count", {
                    count: getArrayField(outputContent, "items").length,
                  })}
                </div>
              )}

            {tool.toolName === TOOL_NAMES.SCRAPE_WEB && getStringField(args, "url") && (
              <div className="line-clamp-2 text-muted-foreground text-xs">
                {getStringField(args, "url")}
              </div>
            )}

            {isDenied && (
              <div className="text-destructive text-xs">
                {deniedReason
                  ? t("tool_part.denied_with_reason", { reason: deniedReason })
                  : t("tool_part.denied")}
              </div>
            )}
          </div>
        )}
      </ControlledChainOfThoughtStep>

      <Drawer
        direction={isMobile ? "bottom" : "right"}
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
      >
        <DrawerContent>
          <DrawerHeader>
            <DrawerTitle>{title}</DrawerTitle>
            <DrawerDescription>
              {t("tool_part.tool_name_label", { toolName: tool.toolName })}
            </DrawerDescription>
          </DrawerHeader>

          <div className="max-h-[72vh] space-y-4 overflow-y-auto px-4 pb-6">
            {tool.toolName === TOOL_NAMES.SEARCH_WEB && isExecuted ? (
              <SearchWebPreview args={args} content={outputContent} />
            ) : tool.toolName === TOOL_NAMES.SCRAPE_WEB && isExecuted ? (
              <ScrapeWebPreview content={outputContent} />
            ) : (
              <div className="space-y-3">
                <div>
                  <div className="mb-1 text-muted-foreground text-xs">
                    {t("tool_part.parameters")}
                  </div>
                  <JsonBlock value={args} />
                </div>
                {isExecuted && (
                  <div>
                    <div className="mb-1 text-muted-foreground text-xs">
                      {t("tool_part.result")}
                    </div>
                    <JsonBlock value={outputContent} />
                  </div>
                )}
                {!isExecuted && (
                  <div className="text-muted-foreground text-sm">{t("tool_part.not_executed")}</div>
                )}
              </div>
            )}
          </div>
        </DrawerContent>
      </Drawer>
    </>
  );
}
