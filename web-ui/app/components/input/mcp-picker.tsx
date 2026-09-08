import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import { LoaderCircle, Terminal } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { getDisplayName } from "~/lib/display";
import { extractErrorMessage } from "~/lib/error";
import { safeStringArray } from "~/lib/type-guards";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { McpToolOption } from "~/types";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";
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

  const updateMcpMutation = useMutation({
    mutationFn: ({
      nextServerIds,
      assistantId,
    }: {
      nextServerIds: string[];
      assistantId: string;
      serverId: string;
    }) =>
      api.post<{ status: string }>("settings/assistant/mcp", {
        assistantId,
        mcpServerIds: nextServerIds,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("mcp.update_failed")));
    },
    onSuccess: () => {
      setError(null);
    },
  });

  const handleToggleServer = React.useCallback(
    (serverId: string, enabled: boolean) => {
      if (!canUse || !currentAssistant) {
        return;
      }

      const nextServerIds = new Set(
        selectedServerIds.filter((selectedServerId) => knownServerIdSet.has(selectedServerId)),
      );

      if (enabled) {
        nextServerIds.add(serverId);
      } else {
        nextServerIds.delete(serverId);
      }

      updateMcpMutation.mutate({
        nextServerIds: Array.from(nextServerIds),
        assistantId: currentAssistant.id,
        serverId,
      });
    },
    [canUse, currentAssistant, knownServerIdSet, selectedServerIds, updateMcpMutation],
  );

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          aria-label={t("mcp.title")}
          title={t("mcp.title")}
          variant="ghost"
          size="sm"
          disabled={!canUse || updateMcpMutation.isPending}
          className={cn(
            "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
            selectedEnabledCount > 0 && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          {updateMcpMutation.isPending ? (
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

      <PopoverContent side="top" align="end" sideOffset={8} className="w-[min(92vw,20rem)] max-h-[var(--radix-popover-content-available-height)] overflow-y-auto space-y-3 p-4">
        <PopoverTitle className="text-sm">{t("mcp.title")}</PopoverTitle>

        <div className="space-y-3">
          <PickerErrorAlert error={error} />

          <div className="max-h-64 overflow-y-auto">
            {enabledServers.length > 0 ? (
              <div className="space-y-1">
                {enabledServers.map((server) => {
                  const selected = selectedServerIdSet.has(server.id);
                  const switching =
                    updateMcpMutation.isPending &&
                    updateMcpMutation.variables?.serverId === server.id;
                  const tools = getEnabledToolsCount(server.commonOptions?.tools);

                  return (
                    <div
                      key={server.id}
                      className={cn(
                        "flex items-center gap-2 rounded-lg px-2 py-2 transition hover:bg-muted",
                        selected && "bg-primary/5",
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
                        <div className="truncate text-sm font-medium leading-tight">
                          {getDisplayName(server.commonOptions?.name, t("mcp.unnamed_server"))}
                        </div>
                        <div className="text-muted-foreground text-xs leading-tight">
                          {t("mcp.tools_enabled", {
                            enabled: tools.enabled,
                            total: tools.total,
                          })}
                        </div>
                      </div>

                      <Switch
                        size="sm"
                        checked={selected}
                        disabled={disabled || updateMcpMutation.isPending}
                        onCheckedChange={(nextChecked) => {
                          handleToggleServer(server.id, nextChecked);
                        }}
                      />
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t("mcp.empty")}
              </div>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
