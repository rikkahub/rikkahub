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

const REASONING_PRESET_BUDGETS: Array<Pick<ReasoningPreset, "key" | "budget">> = [
  { key: "OFF", budget: PRESET_BUDGETS.OFF },
  { key: "AUTO", budget: PRESET_BUDGETS.AUTO },
  { key: "MINIMAL", budget: PRESET_BUDGETS.MINIMAL },
  { key: "LOW", budget: PRESET_BUDGETS.LOW },
  { key: "MEDIUM", budget: PRESET_BUDGETS.MEDIUM },
  { key: "HIGH", budget: PRESET_BUDGETS.HIGH },
  { key: "XHIGH", budget: PRESET_BUDGETS.XHIGH },
];

const ALL_LEVELS: ReasoningLevel[] = ["OFF", "AUTO", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH"];

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

function tokenizeModelId(modelId: string): string[] {
  const tokens: string[] = [];
  const input = modelId.toLowerCase();
  let index = 0;

  while (index < input.length) {
    const ch = input[index];
    if (/[a-z]/.test(ch)) {
      const start = index;
      index += 1;
      while (index < input.length && /[a-z]/.test(input[index])) {
        index += 1;
      }
      tokens.push(input.slice(start, index));
      continue;
    }

    if (/[0-9]/.test(ch)) {
      const start = index;
      index += 1;
      while (index < input.length && /[0-9]/.test(input[index])) {
        index += 1;
      }
      tokens.push(input.slice(start, index));
      continue;
    }

    tokens.push(ch);
    index += 1;
  }

  return tokens;
}

function matchesTokenSequence(tokens: string[], specs: Array<string | RegExp>): boolean {
  let specIndex = 0;
  for (const token of tokens) {
    const spec = specs[specIndex];
    const matched = typeof spec === "string" ? token === spec : spec.test(token);
    if (!matched) continue;
    specIndex += 1;
    if (specIndex === specs.length) {
      return true;
    }
  }
  return false;
}

/**
 * 返回模型支持的 reasoning 级别列表（客户端侧）。
 * 需与 Kotlin 端 ModelRegistry.supportedReasoningLevels() 保持同步。
 */
function getSupportedLevels(model: ProviderModel | null): ReasoningLevel[] {
  if (!model) return ALL_LEVELS;

  const id = model.modelId.toLowerCase();
  const tokens = tokenizeModelId(id);

  if (matchesTokenSequence(tokens, [/^o$/i, /^\d+$/])) {
    return ["OFF", "AUTO", "LOW", "MEDIUM", "HIGH"];
  }

  const isGpt5Base =
    matchesTokenSequence(tokens, ["gpt", "5"]) &&
    !matchesTokenSequence(tokens, ["gpt", "5", "."]) &&
    !matchesTokenSequence(tokens, ["gpt", "5", "chat"]);
  const isGpt51Plus =
    matchesTokenSequence(tokens, ["gpt", "5", "1"]) ||
    matchesTokenSequence(tokens, ["gpt", "5", "2"]) ||
    matchesTokenSequence(tokens, ["gpt", "5", "3"]) ||
    matchesTokenSequence(tokens, ["gpt", "5", "4"]);
  const isPro = id.includes("pro");
  const isCodexMax = id.includes("codex-max");
  const isCodex = id.includes("codex");
  const isOss = matchesTokenSequence(tokens, ["gpt", "oss"]);

  if (isGpt5Base && isPro) return ["AUTO", "HIGH"];
  if (isGpt5Base && isCodex) return ["AUTO", "LOW", "MEDIUM", "HIGH"];
  if (isGpt5Base) return ["OFF", "AUTO", "MINIMAL", "LOW", "MEDIUM", "HIGH"];

  if (isGpt51Plus && isCodexMax) return ["AUTO", "MEDIUM", "HIGH", "XHIGH"];
  if (isGpt51Plus && isCodex) return ["AUTO", "LOW", "MEDIUM", "HIGH", "XHIGH"];
  if (isGpt51Plus && isPro) return ["AUTO", "MEDIUM", "HIGH", "XHIGH"];
  if (isGpt51Plus) return ["OFF", "AUTO", "LOW", "MEDIUM", "HIGH", "XHIGH"];

  if (isOss) return ["AUTO", "LOW", "MEDIUM", "HIGH"];

  return ALL_LEVELS;
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

function normalizeBudget(model: ProviderModel | null, budget: number | null | undefined): number {
  const value = budget ?? PRESET_BUDGETS.AUTO;
  if (value === PRESET_BUDGETS.AUTO || !model) {
    return value;
  }

  const supportedLevels = getSupportedLevels(model);
  if (ALL_LEVELS.every((level) => supportedLevels.includes(level))) {
    return value;
  }

  if (value === PRESET_BUDGETS.OFF && supportedLevels.includes("OFF")) {
    return value;
  }

  const enabledSupportedLevels = supportedLevels
    .filter((level) => level !== "AUTO" && level !== "OFF");
  const candidateLevels =
    enabledSupportedLevels.length > 0
      ? enabledSupportedLevels
      : supportedLevels.filter((level) => level !== "AUTO");

  if (candidateLevels.length === 0) {
    return value;
  }

  const normalizedLevel = candidateLevels.reduce((closest, level) => {
    const currentDistance = Math.abs(PRESET_BUDGETS[level] - value);
    const closestDistance = Math.abs(PRESET_BUDGETS[closest] - value);
    return currentDistance < closestDistance ? level : closest;
  });
  return PRESET_BUDGETS[normalizedLevel];
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
        key: "MINIMAL",
        label: t("reasoning.presets.minimal.label"),
        description: t("reasoning.presets.minimal.description"),
        budget: PRESET_BUDGETS.MINIMAL,
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
      {
        key: "XHIGH",
        label: t("reasoning.presets.xhigh.label"),
        description: t("reasoning.presets.xhigh.description"),
        budget: PRESET_BUDGETS.XHIGH,
      },
    ],
    [t],
  );

  const supportedLevels = React.useMemo(
    () => getSupportedLevels(currentModel),
    [currentModel],
  );
  const filteredPresets = React.useMemo(
    () => reasoningPresets.filter((preset) => supportedLevels.includes(preset.key)),
    [reasoningPresets, supportedLevels],
  );

  const currentBudget = currentAssistant?.thinkingBudget ?? PRESET_BUDGETS.AUTO;
  const normalizedCurrentBudget = React.useMemo(
    () => normalizeBudget(currentModel, currentBudget),
    [currentBudget, currentModel],
  );
  const currentLevel = getReasoningLevel(normalizedCurrentBudget);
  const currentPreset =
    filteredPresets.find((preset) => preset.key === currentLevel) ??
    filteredPresets[0] ??
    reasoningPresets[0];

  React.useEffect(() => {
    if (!canUse || !canReasoning) {
      popoverProps.onOpenChange(false);
    }
  }, [canReasoning, canUse]);

  React.useEffect(() => {
    if (open) {
      setCustomValue(String(normalizedCurrentBudget));
      setCustomExpanded(false);
    }
  }, [normalizedCurrentBudget, open]);

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
            {filteredPresets.map((preset) => {
              const selected = preset.key === currentLevel;
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
                      if (!currentAssistant) return;
                      const nextBudget = normalizeBudget(currentModel, parsedValue);
                      updateThinkingBudgetMutation.mutate({
                        assistantId: currentAssistant.id,
                        thinkingBudget: nextBudget,
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
