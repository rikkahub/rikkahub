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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
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
    <Dialog open={open} onOpenChange={setOpen}>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className={cn(
          "rounded-full px-0 text-muted-foreground hover:text-foreground sm:h-8 sm:max-w-64 sm:justify-start sm:gap-2 sm:px-2",
          className,
        )}
        disabled={disabled || !currentAssistant}
        onClick={() => {
          setOpen(true);
        }}
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

      <DialogContent className="gap-0 p-0 sm:max-w-2xl">
        <DialogHeader className="border-b px-6 py-4">
          <DialogTitle>选择模型</DialogTitle>
          <DialogDescription>切换当前助手使用的聊天模型</DialogDescription>
        </DialogHeader>

        <div className="space-y-3 px-4 py-4">
          <div className="relative">
            <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
            <Input
              value={searchKeywords}
              onChange={(event) => {
                setSearchKeywords(event.target.value);
              }}
              placeholder="搜索模型"
              className="pl-8"
            />
          </div>

          {error ? (
            <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
              {error}
            </div>
          ) : null}

          <ScrollArea className="h-[60vh] pr-3">
            {sections.length === 0 ? (
              <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                没有可用模型
              </div>
            ) : (
              <div className="space-y-4 pb-2">
                {sections.map((section) => (
                  <div key={section.providerId} className="space-y-2">
                    <div className="text-muted-foreground flex items-center gap-2 text-xs font-medium">
                      <AIIcon
                        name={section.providerName}
                        size={14}
                        className="bg-transparent"
                        imageClassName="h-full w-full"
                      />
                      <span>{section.providerName}</span>
                    </div>
                    <div className="space-y-1.5">
                      {section.models.map((model) => {
                        const isSelected = model.id === currentModelId;
                        const isUpdating = model.id === updatingModelId;
                        const abilities = model.abilities ?? [];

                        return (
                          <button
                            key={model.id}
                            type="button"
                            className={cn(
                              "hover:bg-muted flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition",
                              isSelected && "border-primary bg-primary/5",
                            )}
                            disabled={disabled || updatingModelId !== null}
                            onClick={() => {
                              void handleSelectModel(model);
                            }}
                          >
                            <AIIcon name={model.modelId} size={28} />

                            <div className="min-w-0 flex-1">
                              <div className="truncate text-sm font-medium">
                                {getModelDisplayName(model)}
                              </div>
                              <div className="text-muted-foreground truncate text-xs">
                                {model.modelId}
                              </div>
                              <div className="mt-1 flex flex-wrap gap-1">
                                <Badge variant="outline" className="text-[10px]">
                                  {formatModality(model)}
                                </Badge>
                                {abilities.map((ability) => (
                                  <Badge key={ability} variant="secondary" className="text-[10px]">
                                    {getAbilityLabel(ability)}
                                  </Badge>
                                ))}
                              </div>
                            </div>

                            {isUpdating ? (
                              <LoaderCircle className="text-muted-foreground size-4 animate-spin" />
                            ) : isSelected ? (
                              <Check className="text-primary size-4" />
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
      </DialogContent>
    </Dialog>
  );
}
