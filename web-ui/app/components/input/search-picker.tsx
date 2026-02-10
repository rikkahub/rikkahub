import * as React from "react";

import { ChevronDown, Earth, LoaderCircle, Search } from "lucide-react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { BuiltInTool, ProviderModel, SearchServiceOption } from "~/types";
import { AIIcon } from "~/components/ui/ai-icon";
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

  const [open, setOpen] = React.useState(false);
  const [updatingSearchEnabled, setUpdatingSearchEnabled] = React.useState(false);
  const [updatingBuiltInSearch, setUpdatingBuiltInSearch] = React.useState(false);
  const [updatingServiceIndex, setUpdatingServiceIndex] = React.useState<number | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const currentModelId = currentAssistant?.chatModelId ?? settings?.chatModelId ?? null;

  const currentModel = React.useMemo(() => {
    if (!settings || !currentModelId) {
      return null;
    }

    for (const provider of settings.providers) {
      const model = provider.models.find((item) => item.id === currentModelId);
      if (model) {
        return model;
      }
    }

    return null;
  }, [currentModelId, settings]);

  const builtInSearchEnabled = hasBuiltInSearch(currentModel?.tools);
  const searchEnabled = settings?.enableWebSearch ?? false;
  const currentService = settings?.searchServices?.[settings.searchServiceSelected] ?? null;
  const canUse = Boolean(settings && currentAssistant && !disabled);
  const checked = searchEnabled || builtInSearchEnabled;
  const loading = updatingSearchEnabled || updatingBuiltInSearch || updatingServiceIndex !== null;

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
        const message = toggleError instanceof Error ? toggleError.message : "更新网络搜索失败";
        setError(message);
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
        const message = serviceError instanceof Error ? serviceError.message : "切换搜索服务失败";
        setError(message);
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
        const message = toolError instanceof Error ? toolError.message : "更新内置搜索失败";
        setError(message);
      } finally {
        setUpdatingBuiltInSearch(false);
      }
    },
    [canUse, currentModel],
  );

  return (
    <>
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
        onClick={() => {
          setOpen(true);
        }}
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
            <DialogTitle>网络搜索</DialogTitle>
            <DialogDescription>配置联网搜索与搜索服务</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 px-4 py-4">
            {error ? (
              <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                {error}
              </div>
            ) : null}

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

                <ScrollArea className="h-[45vh] pr-3">
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
        </DialogContent>
      </Dialog>
    </>
  );
}
