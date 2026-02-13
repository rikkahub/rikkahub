import * as React from "react";

import { BookOpen, LoaderCircle } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { getDisplayName } from "~/lib/display";
import { extractErrorMessage } from "~/lib/error";
import { safeStringArray } from "~/lib/type-guards";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { LorebookProfile, ModeInjectionProfile } from "~/types";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";
import { ScrollArea } from "~/components/ui/scroll-area";

import { PickerErrorAlert } from "./picker-error-alert";

export interface InjectionPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function getModeInjections(source: unknown): ModeInjectionProfile[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is ModeInjectionProfile =>
    Boolean(item && typeof item === "object" && typeof item.id === "string"),
  );
}

function getLorebooks(source: unknown): LorebookProfile[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is LorebookProfile =>
    Boolean(item && typeof item === "object" && typeof item.id === "string"),
  );
}

export function InjectionPickerButton({ disabled = false, className }: InjectionPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();

  const [activeTab, setActiveTab] = React.useState<"mode" | "lorebook">("mode");
  const [updating, setUpdating] = React.useState(false);
  const [updatingKey, setUpdatingKey] = React.useState<string | null>(null);

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps } = usePickerPopover(canUse);

  const modeInjections = React.useMemo(
    () => getModeInjections(settings?.modeInjections),
    [settings?.modeInjections],
  );
  const lorebooks = React.useMemo(() => getLorebooks(settings?.lorebooks), [settings?.lorebooks]);

  const modeInjectionIdSet = React.useMemo(
    () => new Set(modeInjections.map((item) => item.id)),
    [modeInjections],
  );
  const lorebookIdSet = React.useMemo(() => new Set(lorebooks.map((item) => item.id)), [lorebooks]);

  const selectedModeInjectionIds = React.useMemo(
    () => safeStringArray(currentAssistant?.modeInjectionIds),
    [currentAssistant?.modeInjectionIds],
  );
  const selectedLorebookIds = React.useMemo(
    () => safeStringArray(currentAssistant?.lorebookIds),
    [currentAssistant?.lorebookIds],
  );

  const selectedCount = selectedModeInjectionIds.length + selectedLorebookIds.length;
  const hasData = modeInjections.length > 0 || lorebooks.length > 0;

  React.useEffect(() => {
    if (!canUse || !hasData) {
      popoverProps.onOpenChange(false);
    }
  }, [canUse, hasData]);

  React.useEffect(() => {
    if (modeInjections.length === 0 && lorebooks.length > 0) {
      setActiveTab("lorebook");
      return;
    }

    if (lorebooks.length === 0 && modeInjections.length > 0) {
      setActiveTab("mode");
    }
  }, [lorebooks.length, modeInjections.length]);

  const updateSelections = React.useCallback(
    async (nextModeInjectionIds: string[], nextLorebookIds: string[]) => {
      if (!canUse || !currentAssistant) {
        return;
      }

      setUpdating(true);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/assistant/injections", {
          assistantId: currentAssistant.id,
          modeInjectionIds: nextModeInjectionIds,
          lorebookIds: nextLorebookIds,
        });
      } catch (updateError) {
        setError(extractErrorMessage(updateError, t("injection.update_failed")));
      } finally {
        setUpdating(false);
        setUpdatingKey(null);
      }
    },
    [canUse, currentAssistant, t],
  );

  const handleToggleModeInjection = React.useCallback(
    async (id: string, checked: boolean) => {
      if (!canUse) {
        return;
      }

      setUpdatingKey(`mode:${id}`);
      const nextModeIds = new Set(
        selectedModeInjectionIds.filter((item) => modeInjectionIdSet.has(item)),
      );
      const nextLorebookIds = selectedLorebookIds.filter((item) => lorebookIdSet.has(item));

      if (checked) {
        nextModeIds.add(id);
      } else {
        nextModeIds.delete(id);
      }

      await updateSelections(Array.from(nextModeIds), nextLorebookIds);
    },
    [
      canUse,
      lorebookIdSet,
      modeInjectionIdSet,
      selectedLorebookIds,
      selectedModeInjectionIds,
      updateSelections,
    ],
  );

  const handleToggleLorebook = React.useCallback(
    async (id: string, checked: boolean) => {
      if (!canUse) {
        return;
      }

      setUpdatingKey(`lorebook:${id}`);
      const nextModeIds = selectedModeInjectionIds.filter((item) => modeInjectionIdSet.has(item));
      const nextLorebookIds = new Set(
        selectedLorebookIds.filter((item) => lorebookIdSet.has(item)),
      );

      if (checked) {
        nextLorebookIds.add(id);
      } else {
        nextLorebookIds.delete(id);
      }

      await updateSelections(nextModeIds, Array.from(nextLorebookIds));
    },
    [
      canUse,
      lorebookIdSet,
      modeInjectionIdSet,
      selectedLorebookIds,
      selectedModeInjectionIds,
      updateSelections,
    ],
  );

  if (!hasData) {
    return null;
  }

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canUse || updating}
          className={cn(
            "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
            selectedCount > 0 && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          {updating ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : (
            <BookOpen className="size-4" />
          )}
          {selectedCount > 0 ? (
            <span className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
              {selectedCount}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,26rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-6 py-4">
          <PopoverTitle>{t("injection.title")}</PopoverTitle>
          <PopoverDescription>{t("injection.description")}</PopoverDescription>
        </PopoverHeader>

        <div className="space-y-4 px-4 py-4">
          <PickerErrorAlert error={error} />

          <div className="bg-muted inline-flex rounded-full p-1">
            <button
              type="button"
              className={cn(
                "rounded-full px-3 py-1 text-xs transition",
                activeTab === "mode"
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground",
              )}
              onClick={() => {
                setActiveTab("mode");
              }}
              disabled={modeInjections.length === 0}
            >
              {t("injection.tab_mode")}
            </button>
            <button
              type="button"
              className={cn(
                "rounded-full px-3 py-1 text-xs transition",
                activeTab === "lorebook"
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground",
              )}
              onClick={() => {
                setActiveTab("lorebook");
              }}
              disabled={lorebooks.length === 0}
            >
              {t("injection.tab_lorebook")}
            </button>
          </div>

          <ScrollArea className="h-[16rem] pr-3">
            {activeTab === "mode" ? (
              modeInjections.length > 0 ? (
                <div className="space-y-2">
                  {modeInjections.map((item) => {
                    const checked = selectedModeInjectionIds.includes(item.id);
                    const switching = updatingKey === `mode:${item.id}`;

                    return (
                      <label
                        key={item.id}
                        className={cn(
                          "flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-3 transition",
                          checked && "border-primary bg-primary/5",
                        )}
                      >
                        {switching ? (
                          <LoaderCircle className="size-4 animate-spin" />
                        ) : (
                          <Checkbox
                            checked={checked}
                            disabled={disabled || updating}
                            onCheckedChange={(nextChecked) => {
                              void handleToggleModeInjection(item.id, Boolean(nextChecked));
                            }}
                          />
                        )}
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium">
                            {getDisplayName(item.name, t("injection.unnamed_mode"))}
                          </div>
                          {item.enabled === false ? (
                            <div className="text-muted-foreground mt-0.5 text-xs">
                              {t("injection.disabled")}
                            </div>
                          ) : null}
                        </div>
                      </label>
                    );
                  })}
                </div>
              ) : (
                <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                  {t("injection.empty_mode")}
                </div>
              )
            ) : lorebooks.length > 0 ? (
              <div className="space-y-2">
                {lorebooks.map((item) => {
                  const checked = selectedLorebookIds.includes(item.id);
                  const switching = updatingKey === `lorebook:${item.id}`;

                  return (
                    <label
                      key={item.id}
                      className={cn(
                        "flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-3 transition",
                        checked && "border-primary bg-primary/5",
                      )}
                    >
                      {switching ? (
                        <LoaderCircle className="size-4 animate-spin" />
                      ) : (
                        <Checkbox
                          checked={checked}
                          disabled={disabled || updating}
                          onCheckedChange={(nextChecked) => {
                            void handleToggleLorebook(item.id, Boolean(nextChecked));
                          }}
                        />
                      )}

                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium">
                          {getDisplayName(item.name, t("injection.unnamed_lorebook"))}
                        </div>
                        {typeof item.description === "string" &&
                        item.description.trim().length > 0 ? (
                          <div className="text-muted-foreground mt-0.5 line-clamp-2 text-xs">
                            {item.description}
                          </div>
                        ) : null}
                        {item.enabled === false ? (
                          <div className="text-muted-foreground mt-0.5 text-xs">
                            {t("injection.disabled")}
                          </div>
                        ) : null}
                      </div>
                    </label>
                  );
                })}
              </div>
            ) : (
              <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                {t("injection.empty_lorebook")}
              </div>
            )}
          </ScrollArea>
        </div>
      </PopoverContent>
    </Popover>
  );
}
