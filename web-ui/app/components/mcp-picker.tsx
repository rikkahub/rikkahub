import * as React from "react";

import { ChevronDown, LoaderCircle, Terminal } from "lucide-react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { McpServerConfig, McpToolOption } from "~/types";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Switch } from "~/components/ui/switch";

export interface McpPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function getAssistantMcpServerIds(source: unknown): string[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is string => typeof item === "string" && item.length > 0);
}

function getEnabledToolsCount(tools: McpToolOption[] | undefined): { enabled: number; total: number } {
  if (!tools || tools.length === 0) {
    return { enabled: 0, total: 0 };
  }

  const total = tools.length;
  const enabled = tools.filter((tool) => tool.enable).length;
  return { enabled, total };
}

function getServerName(server: McpServerConfig): string {
  const name = server.commonOptions?.name?.trim();
  if (name) {
    return name;
  }

  return "Unnamed MCP Server";
}

export function McpPickerButton({ disabled = false, className }: McpPickerButtonProps) {
  const { settings, currentAssistant } = useCurrentAssistant();

  const [open, setOpen] = React.useState(false);
  const [updating, setUpdating] = React.useState(false);
  const [updatingServerId, setUpdatingServerId] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const canUse = Boolean(settings && currentAssistant && !disabled);

  const allServers = settings?.mcpServers ?? [];
  const knownServerIdSet = React.useMemo(() => new Set(allServers.map((server) => server.id)), [allServers]);
  const enabledServers = React.useMemo(
    () => allServers.filter((server) => server.commonOptions?.enable),
    [allServers],
  );
  const enabledServerIdSet = React.useMemo(
    () => new Set(enabledServers.map((server) => server.id)),
    [enabledServers],
  );

  const selectedServerIds = React.useMemo(
    () => getAssistantMcpServerIds(currentAssistant?.mcpServers),
    [currentAssistant?.mcpServers],
  );

  const selectedServerIdSet = React.useMemo(() => new Set(selectedServerIds), [selectedServerIds]);
  const selectedEnabledCount = React.useMemo(
    () => selectedServerIds.filter((serverId) => enabledServerIdSet.has(serverId)).length,
    [enabledServerIdSet, selectedServerIds],
  );

  React.useEffect(() => {
    if (!canUse) {
      setOpen(false);
    }
  }, [canUse]);

  React.useEffect(() => {
    if (!open) {
      setError(null);
    }
  }, [open]);

  const updateSelectedServers = React.useCallback(
    async (nextServerIds: string[]) => {
      if (!canUse || !currentAssistant) {
        return;
      }

      setUpdating(true);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/assistant/mcp", {
          assistantId: currentAssistant.id,
          mcpServerIds: nextServerIds,
        });
      } catch (updateError) {
        const message = updateError instanceof Error ? updateError.message : "更新 MCP 设置失败";
        setError(message);
      } finally {
        setUpdating(false);
        setUpdatingServerId(null);
      }
    },
    [canUse, currentAssistant],
  );

  const handleToggleServer = React.useCallback(
    async (serverId: string, enabled: boolean) => {
      if (!canUse) {
        return;
      }

      setUpdatingServerId(serverId);
      const nextServerIds = new Set(
        selectedServerIds.filter((selectedServerId) => knownServerIdSet.has(selectedServerId)),
      );

      if (enabled) {
        nextServerIds.add(serverId);
      } else {
        nextServerIds.delete(serverId);
      }

      await updateSelectedServers(Array.from(nextServerIds));
    },
    [canUse, knownServerIdSet, selectedServerIds, updateSelectedServers],
  );

  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        disabled={!canUse || updating}
        className={cn(
          "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
          selectedEnabledCount > 0 && "text-primary hover:bg-primary/10",
          className,
        )}
        onClick={() => {
          setOpen(true);
        }}
      >
        {updating ? <LoaderCircle className="size-4 animate-spin" /> : <Terminal className="size-4" />}
        {selectedEnabledCount > 0 ? (
          <span className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
            {selectedEnabledCount}
          </span>
        ) : null}
        <span className="hidden sm:block">
          <ChevronDown className="size-3.5" />
        </span>
      </Button>

      <Dialog
        open={open}
        onOpenChange={(nextOpen) => {
          if (!canUse) {
            setOpen(false);
            return;
          }

          setOpen(nextOpen);
        }}
      >
        <DialogContent className="max-h-[80svh] gap-0 p-0 sm:max-w-xl">
          <DialogHeader className="border-b px-6 py-4">
            <DialogTitle>MCP</DialogTitle>
            <DialogDescription>选择当前助手可用的 MCP 服务器</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 px-4 py-4">
            {error ? (
              <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                {error}
              </div>
            ) : null}

            <ScrollArea className="h-[45vh] pr-3">
              {enabledServers.length > 0 ? (
                <div className="space-y-2">
                  {enabledServers.map((server) => {
                    const selected = selectedServerIdSet.has(server.id);
                    const switching = updatingServerId === server.id;
                    const tools = getEnabledToolsCount(server.commonOptions?.tools);

                    return (
                      <div
                        key={server.id}
                        className={cn(
                          "flex items-center gap-3 rounded-lg border px-3 py-3 transition",
                          selected && "border-primary bg-primary/5",
                        )}
                      >
                        <div className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-muted">
                          {switching ? (
                            <LoaderCircle className="size-4 animate-spin" />
                          ) : (
                            <Terminal className="size-4" />
                          )}
                        </div>

                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm font-medium">{getServerName(server)}</div>
                          <div className="text-muted-foreground mt-0.5 text-xs">
                            {tools.enabled}/{tools.total} tools enabled
                          </div>
                        </div>

                        <Switch
                          checked={selected}
                          disabled={disabled || updating}
                          onCheckedChange={(nextChecked) => {
                            void handleToggleServer(server.id, nextChecked);
                          }}
                        />
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                  暂无可用 MCP 服务器
                </div>
              )}
            </ScrollArea>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
