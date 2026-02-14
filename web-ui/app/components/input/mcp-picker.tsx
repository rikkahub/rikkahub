import * as React from "react";

import { LoaderCircle, Terminal } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { getDisplayName } from "~/lib/display";
import { extractErrorMessage } from "~/lib/error";
import { safeStringArray } from "~/lib/type-guards";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { McpServerConfig, McpToolOption } from "~/types";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Switch } from "~/components/ui/switch";

import { PickerErrorAlert } from "./picker-error-alert";

export interface McpPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function getEnabledToolsCount(tools: McpToolOption[] | undefined): {
  enabled: number;
  total: number;
} {
  if (!tools || tools.length === 0) {
    return { enabled: 0, total: 0 };
  }

  const total = tools.length;
  const enabled = tools.filter((tool) => tool.enable).length;
  return { enabled, total };
}

export function McpPickerButton({ disabled = false, className }: McpPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();

  const [updating, setUpdating] = React.useState(false);
  const [updatingServerId, setUpdatingServerId] = React.useState<string | null>(null);

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps } = usePickerPopover(canUse);

  const allServers = settings?.mcpServers ?? [];
  const knownServerIdSet = React.useMemo(
    () => new Set(allServers.map((server) => server.id)),
    [allServers],
  );
  const enabledServers = React.useMemo(
    () => allServers.filter((server) => server.commonOptions?.enable),
    [allServers],
  );
  const enabledServerIdSet = React.useMemo(
    () => new Set(enabledServers.map((server) => server.id)),
    [enabledServers],
  );

  const selectedServerIds = React.useMemo(
    () => safeStringArray(currentAssistant?.mcpServers),
    [currentAssistant?.mcpServers],
  );

  const selectedServerIdSet = React.useMemo(() => new Set(selectedServerIds), [selectedServerIds]);
  const selectedEnabledCount = React.useMemo(
    () => selectedServerIds.filter((serverId) => enabledServerIdSet.has(serverId)).length,
    [enabledServerIdSet, selectedServerIds],
  );

  React.useEffect(() => {
    if (!canUse) {
      popoverProps.onOpenChange(false);
    }
  }, [canUse]);

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
        setError(extractErrorMessage(updateError, t("mcp.update_failed")));
      } finally {
        setUpdating(false);
        setUpdatingServerId(null);
      }
    },
    [canUse, currentAssistant, t],
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
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
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
        >
          {updating ? (
            <LoaderCircle className="size-3.5 animate-spin" />
          ) : (
            <Terminal className="size-3.5" />
          )}
          {selectedEnabledCount > 0 ? (
            <span className="rounded-full bg-primary/10 px-1 py-0.5 text-[9px] text-primary">
              {selectedEnabledCount}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,22rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-3 py-2.5">
          <PopoverTitle className="text-sm">{t("mcp.title")}</PopoverTitle>
          <PopoverDescription className="text-[11px]">
            {t("mcp.description")}
          </PopoverDescription>
        </PopoverHeader>

        <div className="space-y-2 px-2.5 py-2.5">
          <PickerErrorAlert error={error} />

          <ScrollArea className="h-[32vh] pr-1.5">
            {enabledServers.length > 0 ? (
              <div className="space-y-1">
                {enabledServers.map((server) => {
                  const selected = selectedServerIdSet.has(server.id);
                  const switching = updatingServerId === server.id;
                  const tools = getEnabledToolsCount(server.commonOptions?.tools);

                  return (
                    <div
                      key={server.id}
                      className={cn(
                        "flex items-center gap-1.5 rounded-md border px-2 py-1.5 transition",
                        selected && "border-primary bg-primary/5",
                      )}
                    >
                      <div className="flex size-6 shrink-0 items-center justify-center rounded-full bg-muted">
                        {switching ? (
                          <LoaderCircle className="size-3 animate-spin" />
                        ) : (
                          <Terminal className="size-3" />
                        )}
                      </div>

                      <div className="min-w-0 flex-1">
                        <div className="truncate text-[11px] font-medium leading-tight">
                          {getDisplayName(server.commonOptions?.name, t("mcp.unnamed_server"))}
                        </div>
                        <div className="text-muted-foreground text-[10px] leading-tight">
                          {t("mcp.tools_enabled", {
                            enabled: tools.enabled,
                            total: tools.total,
                          })}
                        </div>
                      </div>

                      <Switch
                        size="sm"
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
                {t("mcp.empty")}
              </div>
            )}
          </ScrollArea>
        </div>
      </PopoverContent>
    </Popover>
  );
}
