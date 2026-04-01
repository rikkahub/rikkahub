import * as React from "react";

import { useMutation } from "@tanstack/react-query";
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
  MINIMAL: 512,
  LOW: 1024,
  MEDIUM: 16_000,
  HIGH: 32_000,
  XHIGH: 64_000,
} as const;

type ReasoningLevel = keyof typeof PRESET_BUDGETS;

interface ReasoningPreset {
  key: ReasoningLevel;
  label: string;
  description: string;
  budget: number;
}

const ALL_LEVELS: ReasoningLevel[] = ["OFF", "AUTO", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH"];

const OPENAI_O_LEVELS: ReasoningLevel[] = ["AUTO", "LOW", "MEDIUM", "HIGH"];

function getToolType(tool: ProviderModel["tools"] extends Array<infer T> ? T : unknown): string | null {
  if (!tool || typeof tool !== "object") {
    return null;
  }

  const value = "type" in tool ? tool.type : null;
  return typeof value === "string" ? value.trim().toLowerCase() : null;
}

function hasBuiltInSearch(tools: ProviderModel["tools"] | undefined): boolean {
  if (!tools || tools.length === 0) {
    return false;
  }

  return tools.some((tool) => getToolType(tool) === "search");
}

function isGpt52OrLater(modelId: string): boolean {
  const match = modelId.match(/gpt-5\.(\d+)/i);
  if (!match) {
    return false;
  }

  return Number.parseInt(match[1] ?? "", 10) >= 2;
}

function getSupportedLevels(model: ProviderModel | null): ReasoningLevel[] {
  if (!model?.modelId) {
    return ALL_LEVELS;
  }

  const id = model.modelId.toLowerCase();
  const hasCodex = id.includes("codex");
  const hasCodexMax = id.includes("codex-max");
  const hasPro = id.includes("-pro");

  let levels: ReasoningLevel[];

  if (/\bgpt[-.]?5[-.]?1/.test(id)) {
    if (hasCodexMax) {
      levels = ["AUTO", "MEDIUM", "HIGH", "XHIGH"];
    } else if (hasCodex) {
      levels = ["AUTO", "MEDIUM", "HIGH"];
    } else {
      levels = ["OFF", "AUTO", "LOW", "MEDIUM", "HIGH"];
    }
  } else if (/\bgpt[-.]?5(?![.\d])/.test(id)) {
    if (hasCodex) {
      levels = ["AUTO", "LOW", "MEDIUM", "HIGH"];
    } else if (hasPro) {
      levels = ["AUTO", "HIGH"];
    } else {
      levels = ["AUTO", "MINIMAL", "LOW", "MEDIUM", "HIGH"];
    }
  } else if (isGpt52OrLater(id)) {
    if (hasCodex) {
      levels = ["AUTO", "LOW", "MEDIUM", "HIGH", "XHIGH"];
    } else if (hasPro) {
      levels = ["AUTO", "MEDIUM", "HIGH", "XHIGH"];
    } else {
      levels = ["OFF", "AUTO", "LOW", "MEDIUM", "HIGH", "XHIGH"];
    }
  } else if (/^o\d+/.test(id) || /\bo\d+/.test(id)) {
    levels = OPENAI_O_LEVELS;
  } else if (/\bgpt[-.]?oss\b/.test(id)) {
    levels = OPENAI_O_LEVELS;
  } else {
    levels = ALL_LEVELS;
  }

  if (hasBuiltInSearch(model.tools) && /\bgpt[-.]?5(?![.\d])/.test(id)) {
    return levels.filter((level) => level !== "MINIMAL");
  }

  return levels;
}

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

export function ReasoningPickerButton({ disabled = false, className }: ReasoningPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();
  const { currentModel } = useCurrentModel();

  const [customValue, setCustomValue] = React.useState("");
  const [customExpanded, setCustomExpanded] = React.useState(false);

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const canReasoning = isReasoningModel(currentModel);
  const { open, error, setError, popoverProps } = usePickerPopover(canUse);
  const supportedLevels = React.useMemo(() => getSupportedLevels(currentModel), [currentModel]);

  const reasoningPresets = React.useMemo<ReasoningPreset[]>(() => {
    const allPresets: Record<ReasoningLevel, () => ReasoningPreset> = {
      OFF: () => ({
        key: "OFF",
        label: t("reasoning.presets.off.label"),
        description: t("reasoning.presets.off.description"),
        budget: PRESET_BUDGETS.OFF,
      }),
      AUTO: () => ({
        key: "AUTO",
        label: t("reasoning.presets.auto.label"),
        description: t("reasoning.presets.auto.description"),
        budget: PRESET_BUDGETS.AUTO,
      }),
      MINIMAL: () => ({
        key: "MINIMAL",
        label: t("reasoning.presets.minimal.label"),
        description: t("reasoning.presets.minimal.description"),
        budget: PRESET_BUDGETS.MINIMAL,
      }),
      LOW: () => ({
        key: "LOW",
        label: t("reasoning.presets.low.label"),
        description: t("reasoning.presets.low.description"),
        budget: PRESET_BUDGETS.LOW,
      }),
      MEDIUM: () => ({
        key: "MEDIUM",
        label: t("reasoning.presets.medium.label"),
        description: t("reasoning.presets.medium.description"),
        budget: PRESET_BUDGETS.MEDIUM,
      }),
      HIGH: () => ({
        key: "HIGH",
        label: t("reasoning.presets.high.label"),
        description: t("reasoning.presets.high.description"),
        budget: PRESET_BUDGETS.HIGH,
      }),
      XHIGH: () => ({
        key: "XHIGH",
        label: t("reasoning.presets.xhigh.label"),
        description: t("reasoning.presets.xhigh.description"),
        budget: PRESET_BUDGETS.XHIGH,
      }),
    };
    return supportedLevels.map((level) => allPresets[level]());
  }, [t, supportedLevels]);

  const currentBudget = currentAssistant?.thinkingBudget ?? PRESET_BUDGETS.AUTO;
  const currentPreset = React.useMemo<ReasoningPreset | null>(
    () =>
      reasoningPresets.reduce<ReasoningPreset | null>((closest, preset) => {
        if (!closest) {
          return preset;
        }

        return Math.abs(currentBudget - preset.budget) < Math.abs(currentBudget - closest.budget)
          ? preset
          : closest;
      }, null),
    [currentBudget, reasoningPresets],
  );

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

  const updateThinkingBudgetMutation = useMutation({
    mutationFn: ({
      assistantId,
      thinkingBudget,
    }: {
      assistantId: string;
      thinkingBudget: number;
    }) =>
      api.post<{ status: string }>("settings/assistant/thinking-budget", {
        assistantId,
        thinkingBudget,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("reasoning.update_failed")));
    },
    onSuccess: () => setError(null),
  });

  const loading = updateThinkingBudgetMutation.isPending;

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
          <span>{currentPreset?.label ?? reasoningPresets[0]?.label ?? ""}</span>
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

          <div className="flex flex-wrap gap-2">
            {reasoningPresets.map((preset) => {
              const selected = preset.key === currentPreset?.key;
              const switching =
                updateThinkingBudgetMutation.isPending &&
                updateThinkingBudgetMutation.variables?.thinkingBudget === preset.budget;

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
                    if (!currentAssistant) return;
                    updateThinkingBudgetMutation.mutate({
                      assistantId: currentAssistant.id,
                      thinkingBudget: preset.budget,
                    });
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
            {currentPreset?.description ?? ""}
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
                      if (!currentAssistant) return;
                      updateThinkingBudgetMutation.mutate({
                        assistantId: currentAssistant.id,
                        thinkingBudget: parsedValue,
                      });
                    }}
                  >
                    {t("reasoning.apply")}
                  </Button>
                </div>
                <div className="text-muted-foreground text-xs">{t("reasoning.examples")}</div>
              </>
            ) : null}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
