import * as React from "react";

import { ChevronDown, Lightbulb, LightbulbOff, LoaderCircle, Sparkles } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { useCurrentModel } from "~/hooks/use-current-model";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
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

import { PickerErrorAlert } from "./picker-error-alert";

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

const REASONING_PRESET_BUDGETS: Array<Pick<ReasoningPreset, "key" | "budget">> = [
  { key: "OFF", budget: PRESET_BUDGETS.OFF },
  { key: "AUTO", budget: PRESET_BUDGETS.AUTO },
  { key: "LOW", budget: PRESET_BUDGETS.LOW },
  { key: "MEDIUM", budget: PRESET_BUDGETS.MEDIUM },
  { key: "HIGH", budget: PRESET_BUDGETS.HIGH },
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
  let closest = REASONING_PRESET_BUDGETS[0];
  let minDistance = Number.POSITIVE_INFINITY;

  for (const preset of REASONING_PRESET_BUDGETS) {
    const distance = Math.abs(value - preset.budget);
    if (distance < minDistance) {
      minDistance = distance;
      closest = preset;
    }
  }

  return closest.key;
}

export function ReasoningPickerButton({ disabled = false, className }: ReasoningPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();
  const { currentModel } = useCurrentModel();

  const [updatingBudget, setUpdatingBudget] = React.useState<number | null>(null);
  const [customValue, setCustomValue] = React.useState("");
  const [customExpanded, setCustomExpanded] = React.useState(false);

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const canReasoning = isReasoningModel(currentModel);
  const { open, error, setError, popoverProps } = usePickerPopover(canUse);
  const reasoningPresets = React.useMemo<ReasoningPreset[]>(
    () => [
      {
        key: "OFF",
        label: t("reasoning.presets.off.label"),
        description: t("reasoning.presets.off.description"),
        budget: PRESET_BUDGETS.OFF,
      },
      {
        key: "AUTO",
        label: t("reasoning.presets.auto.label"),
        description: t("reasoning.presets.auto.description"),
        budget: PRESET_BUDGETS.AUTO,
      },
      {
        key: "LOW",
        label: t("reasoning.presets.low.label"),
        description: t("reasoning.presets.low.description"),
        budget: PRESET_BUDGETS.LOW,
      },
      {
        key: "MEDIUM",
        label: t("reasoning.presets.medium.label"),
        description: t("reasoning.presets.medium.description"),
        budget: PRESET_BUDGETS.MEDIUM,
      },
      {
        key: "HIGH",
        label: t("reasoning.presets.high.label"),
        description: t("reasoning.presets.high.description"),
        budget: PRESET_BUDGETS.HIGH,
      },
    ],
    [t],
  );

  const currentBudget = currentAssistant?.thinkingBudget ?? PRESET_BUDGETS.AUTO;
  const currentLevel = getReasoningLevel(currentBudget);
  const currentPreset =
    reasoningPresets.find((preset) => preset.key === currentLevel) ?? reasoningPresets[0];
  const loading = updatingBudget !== null;

  React.useEffect(() => {
    if (!canUse || !canReasoning) {
      popoverProps.onOpenChange(false);
    }
  }, [canReasoning, canUse]);

  React.useEffect(() => {
    if (open) {
      setCustomValue(String(currentBudget));
      setCustomExpanded(false);
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
        setError(extractErrorMessage(updateError, t("reasoning.update_failed")));
      } finally {
        setUpdatingBudget(null);
      }
    },
    [canUse, currentAssistant, t],
  );

  if (!canReasoning) {
    return null;
  }

  return (
    <Popover {...popoverProps}>
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
          <PopoverTitle>{t("reasoning.title")}</PopoverTitle>
          <PopoverDescription>{t("reasoning.description")}</PopoverDescription>
        </PopoverHeader>

        <div className="max-h-[70svh] space-y-3 overflow-y-auto px-4 py-4">
          <PickerErrorAlert error={error} />

          <div className="grid grid-cols-3 gap-2">
            {reasoningPresets.map((preset) => {
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
              <span>{t("reasoning.custom_budget")}</span>
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
                    placeholder={t("reasoning.custom_budget_placeholder")}
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
                        setError(t("reasoning.invalid_integer"));
                        return;
                      }

                      void updateThinkingBudget(parsedValue);
                    }}
                  >
                    {t("reasoning.apply")}
                  </Button>
                </div>
                <div className="text-muted-foreground text-xs">
                  {t("reasoning.examples")}
                </div>
              </>
            ) : null}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
