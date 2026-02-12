import * as React from "react";

import { ChevronDown, Lightbulb, LightbulbOff, LoaderCircle, Sparkles } from "lucide-react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { ProviderModel } from "~/types";
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

const PRESET_BUDGETS = {
  OFF: 0,
  AUTO: -1,
  LOW: 1024,
  MEDIUM: 16_000,
  HIGH: 32_000,
} as const;

type ReasoningLevel = keyof typeof PRESET_BUDGETS;

interface ReasoningPreset {
  key: ReasoningLevel;
  label: string;
  description: string;
  budget: number;
}

const REASONING_PRESETS: ReasoningPreset[] = [
  {
    key: "OFF",
    label: "关闭",
    description: "关闭推理功能，模型将直接回答问题。",
    budget: PRESET_BUDGETS.OFF,
  },
  {
    key: "AUTO",
    label: "自动",
    description: "让模型自动决定推理级别。",
    budget: PRESET_BUDGETS.AUTO,
  },
  {
    key: "LOW",
    label: "轻度推理",
    description: "模型将使用少量推理来回答问题。",
    budget: PRESET_BUDGETS.LOW,
  },
  {
    key: "MEDIUM",
    label: "中度推理",
    description: "模型将使用更多推理来回答问题。",
    budget: PRESET_BUDGETS.MEDIUM,
  },
  {
    key: "HIGH",
    label: "重度推理",
    description: "模型将使用大量推理来回答问题。",
    budget: PRESET_BUDGETS.HIGH,
  },
];

export interface ReasoningPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function isReasoningModel(model: ProviderModel | null): boolean {
  if (!model) {
    return false;
  }

  return (model.abilities ?? []).includes("REASONING");
}

function getReasoningLevel(budget: number | null | undefined): ReasoningLevel {
  const value = budget ?? PRESET_BUDGETS.AUTO;
  let closest = REASONING_PRESETS[0];
  let minDistance = Number.POSITIVE_INFINITY;

  for (const preset of REASONING_PRESETS) {
    const distance = Math.abs(value - preset.budget);
    if (distance < minDistance) {
      minDistance = distance;
      closest = preset;
    }
  }

  return closest.key;
}

function getCurrentModel(
  settings: { providers: { models: ProviderModel[] }[] } | null,
  modelId: string | null,
): ProviderModel | null {
  if (!settings || !modelId) {
    return null;
  }

  for (const provider of settings.providers) {
    const model = provider.models.find((item) => item.id === modelId);
    if (model) {
      return model;
    }
  }

  return null;
}

export function ReasoningPickerButton({ disabled = false, className }: ReasoningPickerButtonProps) {
  const { settings, currentAssistant } = useCurrentAssistant();

  const [open, setOpen] = React.useState(false);
  const [updatingBudget, setUpdatingBudget] = React.useState<number | null>(null);
  const [customValue, setCustomValue] = React.useState("");
  const [customExpanded, setCustomExpanded] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const currentModelId = currentAssistant?.chatModelId ?? settings?.chatModelId ?? null;
  const currentModel = React.useMemo(
    () => getCurrentModel(settings, currentModelId),
    [currentModelId, settings],
  );

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const canReasoning = isReasoningModel(currentModel);

  const currentBudget = currentAssistant?.thinkingBudget ?? PRESET_BUDGETS.AUTO;
  const currentLevel = getReasoningLevel(currentBudget);
  const currentPreset =
    REASONING_PRESETS.find((preset) => preset.key === currentLevel) ?? REASONING_PRESETS[0];
  const loading = updatingBudget !== null;

  React.useEffect(() => {
    if (!canUse || !canReasoning) {
      setOpen(false);
    }
  }, [canReasoning, canUse]);

  React.useEffect(() => {
    if (open) {
      setCustomValue(String(currentBudget));
      setCustomExpanded(false);
      setError(null);
    }
  }, [currentBudget, open]);

  const updateThinkingBudget = React.useCallback(
    async (thinkingBudget: number) => {
      if (!canUse || !currentAssistant) {
        return;
      }

      setUpdatingBudget(thinkingBudget);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/assistant/thinking-budget", {
          assistantId: currentAssistant.id,
          thinkingBudget,
        });
      } catch (updateError) {
        const message = updateError instanceof Error ? updateError.message : "更新推理预算失败";
        setError(message);
      } finally {
        setUpdatingBudget(null);
      }
    },
    [canUse, currentAssistant],
  );

  if (!canReasoning) {
    return null;
  }

  return (
    <Popover
      open={open}
      onOpenChange={(nextOpen) => {
        if (!canUse) {
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
          disabled={!canUse || loading}
          className={cn(
            "h-8 rounded-full px-2.5 text-sm font-normal text-muted-foreground hover:text-foreground",
            className,
          )}
        >
          <span>{currentPreset.label}</span>
          <span className="hidden sm:block">
            {loading ? (
              <LoaderCircle className="size-3.5 animate-spin" />
            ) : (
              <ChevronDown className="size-3.5" />
            )}
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,24rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-6 py-4">
          <PopoverTitle>推理</PopoverTitle>
          <PopoverDescription>配置当前助手的推理强度和预算</PopoverDescription>
        </PopoverHeader>

        <div className="max-h-[70svh] space-y-3 overflow-y-auto px-4 py-4">
          {error ? (
            <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
              {error}
            </div>
          ) : null}

          <div className="grid grid-cols-3 gap-2">
            {REASONING_PRESETS.map((preset) => {
              const selected = preset.key === currentLevel;
              const switching = updatingBudget === preset.budget;

              return (
                <Button
                  key={preset.key}
                  type="button"
                  size="sm"
                  variant={selected ? "default" : "outline"}
                  className={cn(
                    "h-8 w-full justify-start rounded-full px-2 text-xs",
                    selected && "shadow-none",
                  )}
                  disabled={disabled || loading}
                  onClick={() => {
                    void updateThinkingBudget(preset.budget);
                  }}
                >
                  {preset.key === "OFF" ? (
                    <LightbulbOff className="size-3.5" />
                  ) : preset.key === "AUTO" ? (
                    <Sparkles className="size-3.5" />
                  ) : (
                    <Lightbulb className="size-3.5" />
                  )}
                  <span className="truncate">{preset.label}</span>
                  <span className="ml-auto flex size-3.5 items-center justify-center">
                    {switching ? <LoaderCircle className="size-3.5 animate-spin" /> : null}
                  </span>
                </Button>
              );
            })}
          </div>

          <div className="text-muted-foreground h-4 truncate text-xs">
            {currentPreset.description}
          </div>

          <div className="space-y-2 px-1 py-1">
            <button
              type="button"
              className="hover:bg-muted flex h-8 w-full items-center justify-between rounded-md px-2 text-left text-xs font-medium transition"
              onClick={() => {
                setCustomExpanded((prev) => !prev);
              }}
            >
              <span>自定义推理预算</span>
              <ChevronDown
                className={cn("size-3.5 transition-transform", customExpanded && "rotate-180")}
              />
            </button>

            {customExpanded ? (
              <>
                <div className="flex items-center gap-2">
                  <Input
                    className="h-8"
                    value={customValue}
                    onChange={(event) => {
                      setCustomValue(event.target.value);
                    }}
                    placeholder="输入预算 token 数"
                    inputMode="numeric"
                  />
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    disabled={disabled || loading}
                    onClick={() => {
                      const parsedValue = Number.parseInt(customValue.trim(), 10);
                      if (Number.isNaN(parsedValue)) {
                        setError("请输入有效的整数");
                        return;
                      }

                      void updateThinkingBudget(parsedValue);
                    }}
                  >
                    应用
                  </Button>
                </div>
                <div className="text-muted-foreground text-xs">
                  示例：0（关闭）、-1（自动）、1024、16000、32000
                </div>
              </>
            ) : null}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
