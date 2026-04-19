import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import { Brain, BrainCircuit, ChevronDown, Lightbulb, LightbulbOff, LoaderCircle, Sparkles } from "lucide-react";
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
import { Slider } from "~/components/ui/slider";

import { PickerErrorAlert } from "./picker-error-alert";

type ReasoningLevel = "off" | "auto" | "low" | "medium" | "high" | "xhigh";

const REASONING_LEVELS: ReasoningLevel[] = ["off", "auto", "low", "medium", "high", "xhigh"];

interface ReasoningPreset {
  key: ReasoningLevel;
  label: string;
  description: string;
}

export interface ReasoningPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function isReasoningModel(model: ProviderModel | null): boolean {
  if (!model) return false;
  return (model.abilities ?? []).includes("REASONING");
}

function ReasoningIcon({ level, className }: { level: ReasoningLevel; className?: string }) {
  const props = { className: cn("size-4", className) };
  switch (level) {
    case "off":    return <LightbulbOff {...props} />;
    case "auto":   return <Sparkles {...props} />;
    case "low":    return <Lightbulb {...props} />;
    case "medium": return <Lightbulb {...props} />;
    case "high":   return <BrainCircuit {...props} />;
    case "xhigh":  return <Brain {...props} />;
  }
}

export function ReasoningPickerButton({ disabled = false, className }: ReasoningPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();
  const { currentModel } = useCurrentModel();

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const canReasoning = isReasoningModel(currentModel);
  const { open, error, setError, popoverProps } = usePickerPopover(canUse);

  const reasoningPresets = React.useMemo<ReasoningPreset[]>(
    () => [
      { key: "off",    label: t("reasoning.presets.off.label"),    description: t("reasoning.presets.off.description") },
      { key: "auto",   label: t("reasoning.presets.auto.label"),   description: t("reasoning.presets.auto.description") },
      { key: "low",    label: t("reasoning.presets.low.label"),    description: t("reasoning.presets.low.description") },
      { key: "medium", label: t("reasoning.presets.medium.label"), description: t("reasoning.presets.medium.description") },
      { key: "high",   label: t("reasoning.presets.high.label"),   description: t("reasoning.presets.high.description") },
      { key: "xhigh",  label: t("reasoning.presets.xhigh.label"),  description: t("reasoning.presets.xhigh.description") },
    ],
    [t],
  );

  const currentLevel = ((currentAssistant?.reasoningLevel as ReasoningLevel | null | undefined) ?? "auto");
  const currentIndex = Math.max(0, REASONING_LEVELS.indexOf(currentLevel));
  const currentPreset = reasoningPresets.find((p) => p.key === currentLevel) ?? reasoningPresets[1];

  const [localIndex, setLocalIndex] = React.useState(currentIndex);

  React.useEffect(() => {
    setLocalIndex(currentIndex);
  }, [currentIndex]);

  React.useEffect(() => {
    if (!canUse || !canReasoning) {
      popoverProps.onOpenChange(false);
    }
  }, [canReasoning, canUse]);

  React.useEffect(() => {
    if (open) {
      setLocalIndex(currentIndex);
    }
  }, [open]);

  const updateReasoningLevelMutation = useMutation({
    mutationFn: ({ assistantId, reasoningLevel }: { assistantId: string; reasoningLevel: ReasoningLevel }) =>
      api.post<{ status: string }>("settings/assistant/thinking-budget", {
        assistantId,
        reasoningLevel,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("reasoning.update_failed")));
      setLocalIndex(currentIndex);
    },
    onSuccess: () => setError(null),
  });

  const loading = updateReasoningLevelMutation.isPending;
  const localLevel = REASONING_LEVELS[localIndex] ?? currentLevel;
  const localPreset = reasoningPresets.find((p) => p.key === localLevel) ?? currentPreset;
  const isEnabled = localLevel !== "off";

  if (!canReasoning) return null;

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
          <ReasoningIcon level={currentLevel} className="size-3.5" />
          <span className="hidden sm:block">{currentPreset.label}</span>
          <span className="hidden sm:block">
            {loading ? (
              <LoaderCircle className="size-3.5 animate-spin" />
            ) : (
              <ChevronDown className="size-3.5" />
            )}
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,22rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-6 py-4">
          <PopoverTitle>{t("reasoning.title")}</PopoverTitle>
          <PopoverDescription>{t("reasoning.description")}</PopoverDescription>
        </PopoverHeader>

        <div className="space-y-5 px-6 py-5">
          <PickerErrorAlert error={error} />

          {/* Current level display */}
          <div className="flex flex-col items-center gap-1.5">
            <ReasoningIcon
              level={localLevel}
              className={cn(
                "size-8 transition-colors",
                isEnabled ? "text-primary" : "text-muted-foreground",
              )}
            />
            <span className={cn(
              "text-sm font-medium transition-colors",
              isEnabled ? "text-primary" : "text-foreground",
            )}>
              {localPreset.label}
            </span>
            <span className="text-xs text-muted-foreground text-center min-h-[2.5em]">
              {localPreset.description}
            </span>
          </div>

          {/* Slider */}
          <div className="space-y-2">
            <Slider
              value={[localIndex]}
              min={0}
              max={REASONING_LEVELS.length - 1}
              step={1}
              disabled={disabled || loading}
              onValueChange={([index]) => {
                setLocalIndex(index);
              }}
              onValueCommit={([index]) => {
                if (!currentAssistant) return;
                const level = REASONING_LEVELS[index];
                updateReasoningLevelMutation.mutate({
                  assistantId: currentAssistant.id,
                  reasoningLevel: level,
                });
              }}
            />

            {/* Tick labels */}
            <div className="flex justify-between">
              {reasoningPresets.map((preset, i) => (
                <span
                  key={preset.key}
                  className={cn(
                    "flex-1 text-center text-[10px] transition-colors",
                    i === localIndex ? "text-primary font-medium" : "text-muted-foreground",
                  )}
                >
                  {preset.label}
                </span>
              ))}
            </div>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
