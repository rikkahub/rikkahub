import * as React from "react";

import { Check, ChevronDown, LoaderCircle, Search } from "lucide-react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { ModelAbility, ProviderModel } from "~/types";
import { AIIcon } from "~/components/ui/ai-icon";
import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";

export interface ModelListProps {
  disabled?: boolean;
  className?: string;
  onChanged?: (model: ProviderModel) => void;
}

interface ModelSection {
  providerId: string;
  providerName: string;
  models: ProviderModel[];
}

function normalizeKeyword(value: string) {
  return value.trim().toLowerCase();
}

function getModelDisplayName(model: ProviderModel): string {
  const name = model.displayName.trim();
  if (name.length > 0) {
    return name;
  }

  const modelId = model.modelId.trim();
  if (modelId.length > 0) {
    return modelId;
  }

  return "未命名模型";
}

function formatModality(model: ProviderModel): string {
  const input = (model.inputModalities ?? []).join("+") || "TEXT";
  const output = (model.outputModalities ?? []).join("+") || "TEXT";
  return `${input} -> ${output}`;
}

function getAbilityLabel(ability: ModelAbility): string {
  if (ability === "TOOL") {
    return "工具";
  }

  return "推理";
}

export function ModelList({ disabled = false, className, onChanged }: ModelListProps) {
  const { settings, currentAssistant } = useCurrentAssistant();

  const [open, setOpen] = React.useState(false);
  const [searchKeywords, setSearchKeywords] = React.useState("");
  const [updatingModelId, setUpdatingModelId] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const currentModelId = currentAssistant?.chatModelId ?? settings?.chatModelId ?? null;

  const sections = React.useMemo<ModelSection[]>(() => {
    if (!settings) {
      return [];
    }

    const keyword = normalizeKeyword(searchKeywords);

    return settings.providers
      .filter((provider) => provider.enabled)
      .map((provider) => {
        const models = provider.models.filter((model) => {
          if (model.type !== "CHAT") {
            return false;
          }

          if (keyword.length === 0) {
            return true;
          }

          const displayName = getModelDisplayName(model).toLowerCase();
          const modelId = model.modelId.toLowerCase();
          return displayName.includes(keyword) || modelId.includes(keyword);
        });

        return {
          providerId: provider.id,
          providerName: provider.name,
          models,
        };
      })
      .filter((section) => section.models.length > 0);
  }, [searchKeywords, settings]);

  const allModels = React.useMemo(() => sections.flatMap((section) => section.models), [sections]);

  const currentModel = React.useMemo(
    () => allModels.find((model) => model.id === currentModelId) ?? null,
    [allModels, currentModelId],
  );

  const currentModelLabel = currentModel ? getModelDisplayName(currentModel) : "选择模型";

  React.useEffect(() => {
    if (!open) {
      setSearchKeywords("");
      setError(null);
    }
  }, [open]);

  React.useEffect(() => {
    if (!disabled) {
      return;
    }

    setOpen(false);
  }, [disabled]);

  const handleSelectModel = React.useCallback(
    async (model: ProviderModel) => {
      if (disabled || !currentAssistant) {
        return;
      }

      if (model.id === currentModelId) {
        setOpen(false);
        return;
      }

      setUpdatingModelId(model.id);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/assistant/model", {
          assistantId: currentAssistant.id,
          modelId: model.id,
        });
        onChanged?.(model);
        setOpen(false);
      } catch (changeError) {
        const message = changeError instanceof Error ? changeError.message : "切换模型失败";
        setError(message);
      } finally {
        setUpdatingModelId(null);
      }
    },
    [currentAssistant, currentModelId, disabled, onChanged],
  );

  return (
    <Popover
      open={open}
      onOpenChange={(nextOpen) => {
        if (disabled || !currentAssistant) {
          setOpen(false);
          return;
        }

        setOpen(nextOpen);
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className={cn(
            "rounded-full px-0 text-muted-foreground hover:text-foreground sm:h-8 sm:max-w-64 sm:justify-start sm:gap-2 sm:px-2",
            className,
          )}
          disabled={disabled || !currentAssistant}
        >
          <AIIcon
            name={currentModel?.modelId ?? "auto"}
            size={16}
            className="bg-transparent"
            imageClassName="h-full w-full"
          />
          <span className="hidden min-w-0 flex-1 truncate text-left sm:block">
            {currentModelLabel}
          </span>
          <ChevronDown className="hidden size-3.5 shrink-0 sm:block" />
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(96vw,30rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-4 py-3">
          <PopoverTitle className="text-sm">选择模型</PopoverTitle>
          <PopoverDescription className="text-xs">切换当前助手使用的聊天模型</PopoverDescription>
        </PopoverHeader>

        <div className="space-y-2 px-3 py-3">
          <div className="relative">
            <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2" />
            <Input
              value={searchKeywords}
              onChange={(event) => {
                setSearchKeywords(event.target.value);
              }}
              placeholder="搜索模型"
              className="h-8 pl-7 text-xs"
            />
          </div>

          {error ? (
            <div className="rounded-md border border-destructive/30 bg-destructive/10 px-2.5 py-1.5 text-[11px] text-destructive">
              {error}
            </div>
          ) : null}

          <ScrollArea className="h-[52vh] pr-2">
            {sections.length === 0 ? (
              <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                没有可用模型
              </div>
            ) : (
              <div className="space-y-3 pb-1">
                {sections.map((section) => (
                  <div key={section.providerId} className="space-y-1.5">
                    <div className="text-muted-foreground flex items-center gap-1.5 text-[11px] font-medium">
                      <AIIcon
                        name={section.providerName}
                        size={12}
                        className="bg-transparent"
                        imageClassName="h-full w-full"
                      />
                      <span>{section.providerName}</span>
                    </div>
                    <div className="space-y-1">
                      {section.models.map((model) => {
                        const isSelected = model.id === currentModelId;
                        const isUpdating = model.id === updatingModelId;
                        const abilities = model.abilities ?? [];

                        return (
                          <button
                            key={model.id}
                            type="button"
                            className={cn(
                              "hover:bg-muted flex w-full items-center gap-2 rounded-md border px-2.5 py-1.5 text-left transition",
                              isSelected && "border-primary bg-primary/5",
                            )}
                            disabled={disabled || updatingModelId !== null}
                            onClick={() => {
                              void handleSelectModel(model);
                            }}
                          >
                            <AIIcon name={model.modelId} size={24} />

                            <div className="min-w-0 flex-1">
                              <div className="truncate text-xs font-medium leading-tight">
                                {getModelDisplayName(model)}
                              </div>
                              <div className="text-muted-foreground truncate text-[11px] leading-tight">
                                {model.modelId}
                              </div>
                              <div className="mt-0.5 flex flex-wrap gap-1">
                                <Badge variant="outline" className="px-1 py-0 text-[9px]">
                                  {formatModality(model)}
                                </Badge>
                                {abilities.map((ability) => (
                                  <Badge
                                    key={ability}
                                    variant="secondary"
                                    className="px-1 py-0 text-[9px]"
                                  >
                                    {getAbilityLabel(ability)}
                                  </Badge>
                                ))}
                              </div>
                            </div>

                            {isUpdating ? (
                              <LoaderCircle className="text-muted-foreground size-3.5 animate-spin" />
                            ) : isSelected ? (
                              <Check className="text-primary size-3.5" />
                            ) : null}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </ScrollArea>
        </div>
      </PopoverContent>
    </Popover>
  );
}
