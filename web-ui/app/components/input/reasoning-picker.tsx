import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import {
  Brain,
  BrainCircuit,
  ChevronDown,
  Lightbulb,
  LightbulbOff,
  LoaderCircle,
  Sparkles,
} from "lucide-react";
import { Slider as SliderPrimitive } from "radix-ui";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { useCurrentModel } from "~/hooks/use-current-model";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { ProviderModel } from "~/types";
import { Button } from "~/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "~/components/ui/popover";

import { PickerErrorAlert } from "./picker-error-alert";

type ReasoningLevel = "off" | "auto" | "low" | "medium" | "high" | "xhigh";

const REASONING_LEVELS: ReasoningLevel[] = ["off", "auto", "low", "medium", "high", "xhigh"];

// Thumb size in px, keep in sync with `size-7` on the thumb below
const THUMB_SIZE = 28;

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
    case "off":
      return <LightbulbOff {...props} />;
    case "auto":
      return <Sparkles {...props} />;
    case "low":
      return <Lightbulb {...props} />;
    case "medium":
      return <Lightbulb {...props} />;
    case "high":
      return <BrainCircuit {...props} />;
    case "xhigh":
      return <Brain {...props} />;
  }
}

function ReasoningSlider({
  value,
  disabled,
  onValueChange,
  onValueCommit,
}: {
  value: number;
  disabled?: boolean;
  onValueChange: (index: number) => void;
  onValueCommit: (index: number) => void;
}) {
  const max = REASONING_LEVELS.length - 1;
  return (
    <SliderPrimitive.Root
      value={[value]}
      min={0}
      max={max}
      step={1}
      disabled={disabled}
      onValueChange={([index]) => onValueChange(index)}
      onValueCommit={([index]) => onValueCommit(index)}
      className="relative flex h-7 w-full touch-none items-center select-none data-[disabled]:opacity-50"
    >
      <SliderPrimitive.Track className="relative h-7 w-full grow overflow-hidden rounded-full bg-muted">
        <SliderPrimitive.Range className="absolute h-full bg-primary/75" />
        {/* Tick dots inside the track, aligned with thumb travel positions */}
        {REASONING_LEVELS.map((level, i) => (
          <span
            key={level}
            className={cn(
              "pointer-events-none absolute top-1/2 size-1.5 -translate-x-1/2 -translate-y-1/2 rounded-full",
              i <= value ? "bg-primary-foreground/60" : "bg-foreground/25",
            )}
            style={{ left: `calc(${THUMB_SIZE / 2}px + (100% - ${THUMB_SIZE}px) * ${i / max})` }}
          />
        ))}
      </SliderPrimitive.Track>
      <SliderPrimitive.Thumb className="block size-7 shrink-0 rounded-lg border-2 border-primary bg-background shadow-md ring-ring/50 transition-[box-shadow] hover:ring-4 focus-visible:ring-4 focus-visible:outline-hidden disabled:pointer-events-none" />
    </SliderPrimitive.Root>
  );
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
      {
        key: "off",
        label: t("reasoning.presets.off.label"),
        description: t("reasoning.presets.off.description"),
      },
      {
        key: "auto",
        label: t("reasoning.presets.auto.label"),
        description: t("reasoning.presets.auto.description"),
      },
      {
        key: "low",
        label: t("reasoning.presets.low.label"),
        description: t("reasoning.presets.low.description"),
      },
      {
        key: "medium",
        label: t("reasoning.presets.medium.label"),
        description: t("reasoning.presets.medium.description"),
      },
      {
        key: "high",
        label: t("reasoning.presets.high.label"),
        description: t("reasoning.presets.high.description"),
      },
      {
        key: "xhigh",
        label: t("reasoning.presets.xhigh.label"),
        description: t("reasoning.presets.xhigh.description"),
      },
    ],
    [t],
  );

  const currentLevel =
    (currentAssistant?.reasoningLevel as ReasoningLevel | null | undefined) ?? "auto";
  const currentIndex = Math.max(0, REASONING_LEVELS.indexOf(currentLevel));

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
    mutationFn: ({
      assistantId,
      reasoningLevel,
    }: {
      assistantId: string;
      reasoningLevel: ReasoningLevel;
    }) =>
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
  const localPreset = reasoningPresets.find((p) => p.key === localLevel) ?? reasoningPresets[1];

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
          <ReasoningIcon level={localLevel} className="size-3.5" />
          <span className="hidden sm:block">{localPreset.label}</span>
          <span className="hidden sm:block">
            {loading ? (
              <LoaderCircle className="size-3.5 animate-spin" />
            ) : (
              <ChevronDown className="size-3.5" />
            )}
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,20rem)] space-y-3 px-4 py-4">
        <PickerErrorAlert error={error} />

        {/* Faster / Smarter labels */}
        <div className="flex items-center justify-between text-sm font-medium text-foreground">
          <span>{t("reasoning.faster")}</span>
          <span>{t("reasoning.smarter")}</span>
        </div>

        {/* Thick slider with tick dots */}
        <ReasoningSlider
          value={localIndex}
          disabled={disabled || loading}
          onValueChange={setLocalIndex}
          onValueCommit={(index) => {
            if (!currentAssistant) return;
            updateReasoningLevelMutation.mutate({
              assistantId: currentAssistant.id,
              reasoningLevel: REASONING_LEVELS[index],
            });
          }}
        />

        {/* Current preset hint */}
        <div className="text-xs text-muted-foreground">
          <span className="font-medium text-foreground">{localPreset.label}</span>
          {" · "}
          {localPreset.description}
        </div>
      </PopoverContent>
    </Popover>
  );
}
