import * as React from "react";

import { ChevronDown, Earth, LoaderCircle, Search } from "lucide-react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { useCurrentModel } from "~/hooks/use-current-model";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { BuiltInTool, ProviderModel, SearchServiceOption } from "~/types";
import { AIIcon } from "~/components/ui/ai-icon";
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

const SEARCH_TOOL_NAME = "search";

const SEARCH_SERVICE_LABELS: Record<string, string> = {
  bing_local: "Bing",
  rikkahub: "RikkaHub",
  zhipu: "智谱",
  tavily: "Tavily",
  exa: "Exa",
  searxng: "SearXNG",
  linkup: "LinkUp",
  brave: "Brave",
  metaso: "秘塔",
  ollama: "Ollama",
  perplexity: "Perplexity",
  firecrawl: "Firecrawl",
  jina: "Jina",
  bocha: "博查",
};

export interface SearchPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function getToolType(tool: BuiltInTool | string | null | undefined): string | null {
  if (!tool) {
    return null;
  }

  if (typeof tool === "string") {
    return tool.trim().toLowerCase();
  }

  const value = tool.type;
  if (typeof value === "string") {
    return value.trim().toLowerCase();
  }

  return null;
}

function hasBuiltInSearch(tools: ProviderModel["tools"] | undefined): boolean {
  if (!tools || tools.length === 0) {
    return false;
  }

  return tools.some((tool) => getToolType(tool) === SEARCH_TOOL_NAME);
}

function isGeminiModel(model: ProviderModel | null): boolean {
  if (!model) {
    return false;
  }

  return model.modelId.toLowerCase().includes("gemini");
}

function getServiceType(service: SearchServiceOption): string | null {
  if (typeof service.type !== "string") {
    return null;
  }

  const value = service.type.trim().toLowerCase();
  return value.length > 0 ? value : null;
}

function getServiceLabel(service: SearchServiceOption): string {
  const type = getServiceType(service);
  if (!type) {
    return "Search";
  }

  return SEARCH_SERVICE_LABELS[type] ?? type;
}

export function SearchPickerButton({ disabled = false, className }: SearchPickerButtonProps) {
  const { settings, currentAssistant } = useCurrentAssistant();
  const { currentModel } = useCurrentModel();

  const [updatingSearchEnabled, setUpdatingSearchEnabled] = React.useState(false);
  const [updatingBuiltInSearch, setUpdatingBuiltInSearch] = React.useState(false);
  const [updatingServiceIndex, setUpdatingServiceIndex] = React.useState<number | null>(null);

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps } = usePickerPopover(canUse);

  const builtInSearchEnabled = hasBuiltInSearch(currentModel?.tools);
  const searchEnabled = settings?.enableWebSearch ?? false;
  const currentService = settings?.searchServices?.[settings.searchServiceSelected] ?? null;
  const checked = searchEnabled || builtInSearchEnabled;
  const loading = updatingSearchEnabled || updatingBuiltInSearch || updatingServiceIndex !== null;

  React.useEffect(() => {
    if (!canUse) {
      popoverProps.onOpenChange(false);
    }
  }, [canUse]);

  const handleToggleSearchEnabled = React.useCallback(
    async (enabled: boolean) => {
      if (!canUse) {
        return;
      }

      setUpdatingSearchEnabled(true);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/search/enabled", { enabled });
      } catch (toggleError) {
        setError(extractErrorMessage(toggleError, "更新网络搜索失败"));
      } finally {
        setUpdatingSearchEnabled(false);
      }
    },
    [canUse],
  );

  const handleSelectService = React.useCallback(
    async (index: number) => {
      if (!canUse || !settings) {
        return;
      }

      if (index === settings.searchServiceSelected) {
        return;
      }

      setUpdatingServiceIndex(index);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/search/service", { index });
      } catch (serviceError) {
        setError(extractErrorMessage(serviceError, "切换搜索服务失败"));
      } finally {
        setUpdatingServiceIndex(null);
      }
    },
    [canUse, settings],
  );

  const handleToggleBuiltInSearch = React.useCallback(
    async (enabled: boolean) => {
      if (!canUse || !currentModel) {
        return;
      }

      setUpdatingBuiltInSearch(true);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/model/built-in-tool", {
          modelId: currentModel.id,
          tool: SEARCH_TOOL_NAME,
          enabled,
        });
      } catch (toolError) {
        setError(extractErrorMessage(toolError, "更新内置搜索失败"));
      } finally {
        setUpdatingBuiltInSearch(false);
      }
    },
    [canUse, currentModel],
  );

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canUse || loading}
          className={cn(
            "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
            checked && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          {updatingSearchEnabled || updatingBuiltInSearch ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : searchEnabled && currentService ? (
            <AIIcon
              name={getServiceLabel(currentService)}
              size={16}
              className="bg-transparent"
              imageClassName="h-full w-full"
            />
          ) : builtInSearchEnabled ? (
            <Search className="size-4" />
          ) : (
            <Earth className="size-4" />
          )}
          <span className="hidden sm:block">
            <ChevronDown className="size-3.5" />
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,28rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-6 py-4">
          <PopoverTitle>网络搜索</PopoverTitle>
          <PopoverDescription>配置联网搜索与搜索服务</PopoverDescription>
        </PopoverHeader>

        <div className="space-y-4 px-4 py-4">
          <PickerErrorAlert error={error} />

          {isGeminiModel(currentModel) ? (
            <div className="flex items-center gap-3 rounded-lg border px-3 py-3">
              <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-muted">
                <Search className="size-4" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium">模型内置搜索</div>
                <div className="text-muted-foreground text-xs">使用模型原生搜索能力</div>
              </div>
              <Switch
                checked={builtInSearchEnabled}
                disabled={
                  disabled ||
                  updatingBuiltInSearch ||
                  updatingSearchEnabled ||
                  updatingServiceIndex !== null
                }
                onCheckedChange={(nextChecked) => {
                  void handleToggleBuiltInSearch(nextChecked);
                }}
              />
            </div>
          ) : null}

          {!builtInSearchEnabled ? (
            <>
              <div className="flex items-center gap-3 rounded-lg border px-3 py-3">
                <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-muted">
                  <Earth className="size-4" />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">联网搜索</div>
                  <div className="text-muted-foreground text-xs">
                    {searchEnabled ? "已启用" : "已关闭"}
                  </div>
                </div>
                <Switch
                  checked={searchEnabled}
                  disabled={
                    disabled ||
                    updatingSearchEnabled ||
                    updatingBuiltInSearch ||
                    updatingServiceIndex !== null
                  }
                  onCheckedChange={(nextChecked) => {
                    void handleToggleSearchEnabled(nextChecked);
                  }}
                />
              </div>

              <ScrollArea className="h-[16rem] pr-3">
                {settings?.searchServices?.length ? (
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                    {settings.searchServices.map((service, index) => {
                      const selected = index === settings.searchServiceSelected;
                      const switching = updatingServiceIndex === index;

                      return (
                        <button
                          key={service.id}
                          type="button"
                          className={cn(
                            "hover:bg-muted flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition",
                            selected && "border-primary bg-primary/5",
                          )}
                          disabled={
                            disabled ||
                            updatingSearchEnabled ||
                            updatingBuiltInSearch ||
                            updatingServiceIndex !== null
                          }
                          onClick={() => {
                            void handleSelectService(index);
                          }}
                        >
                          <AIIcon
                            name={getServiceLabel(service)}
                            size={20}
                            className="bg-transparent"
                            imageClassName="h-full w-full"
                          />
                          <div className="min-w-0 flex-1">
                            <div className="truncate text-sm font-medium">
                              {getServiceLabel(service)}
                            </div>
                            <div className="text-muted-foreground truncate text-xs">
                              {getServiceType(service) ?? "unknown"}
                            </div>
                          </div>
                          {switching ? <LoaderCircle className="size-3.5 animate-spin" /> : null}
                        </button>
                      );
                    })}
                  </div>
                ) : (
                  <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                    暂无可用搜索服务
                  </div>
                )}
              </ScrollArea>
            </>
          ) : (
            <div className="rounded-md border border-primary/20 bg-primary/5 px-3 py-2 text-xs text-primary">
              当前已启用模型内置搜索，应用搜索服务设置暂不生效。
            </div>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
