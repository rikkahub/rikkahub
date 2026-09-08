import * as React from "react";

import { Check, ChevronDown, Heart, LoaderCircle, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { getModelDisplayName } from "~/lib/display";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { ProviderModel } from "~/types";
import { AIIcon } from "~/components/ui/ai-icon";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
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

const FAVORITE_SECTION_ID = "__favorites__";

function normalizeKeyword(value: string) {
  return value.trim().toLowerCase();
}

interface ModelOptionRowProps {
  model: ProviderModel;
  selected: boolean;
  updating: boolean;
  favorite: boolean;
  disabled: boolean;
  onSelect: (model: ProviderModel) => void | Promise<void>;
  onToggleFavorite: (model: ProviderModel) => void | Promise<void>;
}

function ModelOptionRow({
  model,
  selected,
  updating,
  favorite,
  disabled,
  onSelect,
  onToggleFavorite,
}: ModelOptionRowProps) {

  return (
    <div
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
      className={cn(
        "hover:bg-muted flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left transition",
        disabled && "pointer-events-none opacity-60",
        selected && "bg-primary/5",
      )}
      onClick={() => {
        if (disabled) {
          return;
        }

        void onSelect(model);
      }}
      onKeyDown={(event) => {
        if (disabled) {
          return;
        }

        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          void onSelect(model);
        }
      }}
    >
      <AIIcon name={model.modelId} size={20} />

      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium leading-tight">
          {getModelDisplayName(model.displayName, model.modelId)}
        </div>
        <div className="text-muted-foreground truncate text-[11px] leading-tight">
          {model.modelId}
        </div>

      </div>

      {updating ? (
        <LoaderCircle className="text-muted-foreground size-3.5 animate-spin" />
      ) : selected ? (
        <Check className="text-primary size-3.5" />
      ) : (
        <button
          type="button"
          className={favorite ? "text-primary" : "text-muted-foreground hover:text-primary"}
          onClick={(event) => {
            event.stopPropagation();
            void onToggleFavorite(model);
          }}
        >
          <Heart className={cn("size-3.5", favorite && "fill-current")} />
        </button>
      )}
    </div>
  );
}

export function ModelList({ disabled = false, className, onChanged }: ModelListProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();

  const [open, setOpen] = React.useState(false);
  const [searchKeywords, setSearchKeywords] = React.useState("");
  const [selectedProviderId, setSelectedProviderId] = React.useState<string | null>(null);
  const [updatingModelId, setUpdatingModelId] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const currentModelId = currentAssistant?.chatModelId ?? settings?.chatModelId ?? null;
  const favoriteModelIds = settings?.favoriteModels ?? [];
  const favoriteModelIdSet = React.useMemo(() => new Set(favoriteModelIds), [favoriteModelIds]);

  const allModels = React.useMemo(() => {
    if (!settings) {
      return [];
    }

    return settings.providers
      .filter((provider) => provider.enabled)
      .flatMap((provider) => provider.models)
      .filter((model) => model.type === "CHAT");
  }, [settings]);

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

          const displayName = getModelDisplayName(model.displayName, model.modelId).toLowerCase();
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

  const selectedSection = React.useMemo(() => {
    if (sections.length === 0) {
      return null;
    }

    return sections.find((section) => section.providerId === selectedProviderId) ?? sections[0];
  }, [sections, selectedProviderId]);
  const filteredModels = React.useMemo(() => sections.flatMap((section) => section.models), [sections]);

  const favoriteModels = React.useMemo(() => {
    return favoriteModelIds
      .map((id) => filteredModels.find((model) => model.id === id))
      .filter((model): model is ProviderModel => model !== undefined);
  }, [favoriteModelIds, filteredModels]);
  const isFavoriteSectionSelected = selectedProviderId === FAVORITE_SECTION_ID;
  const displayedModels = isFavoriteSectionSelected ? favoriteModels : (selectedSection?.models ?? []);

  const currentModel = React.useMemo(
    () => allModels.find((model) => model.id === currentModelId) ?? null,
    [allModels, currentModelId],
  );

  const currentModelLabel = currentModel
    ? getModelDisplayName(currentModel.displayName, currentModel.modelId)
    : t("model_list.select_model");

  React.useEffect(() => {
    if (!open) {
      setSearchKeywords("");
      setError(null);
    }
  }, [open]);

  React.useEffect(() => {
    if (!open) {
      return;
    }

    if (sections.length === 0 && favoriteModels.length === 0) {
      setSelectedProviderId(null);
      return;
    }

    if (selectedProviderId === FAVORITE_SECTION_ID && favoriteModels.length > 0) {
      return;
    }

    if (selectedProviderId && sections.some((section) => section.providerId === selectedProviderId)) {
      return;
    }

    const currentModelSection =
      currentModelId == null ? null : sections.find((section) => section.models.some((model) => model.id === currentModelId));
    setSelectedProviderId(currentModelSection?.providerId ?? (favoriteModels.length > 0 ? FAVORITE_SECTION_ID : sections[0]?.providerId ?? null));
  }, [currentModelId, favoriteModels.length, open, sections, selectedProviderId]);

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
        const message =
          changeError instanceof Error
            ? changeError.message
            : t("model_list.switch_model_failed");
        setError(message);
      } finally {
        setUpdatingModelId(null);
      }
    },
    [currentAssistant, currentModelId, disabled, onChanged, t],
  );

  const handleToggleFavorite = React.useCallback(
    async (model: ProviderModel) => {
      if (disabled || !settings) {
        return;
      }

      const isFavorite = favoriteModelIds.includes(model.id);
      const newFavoriteModels = isFavorite
        ? favoriteModelIds.filter((id) => id !== model.id)
        : [...favoriteModelIds, model.id];

      setUpdatingModelId(model.id);
      setError(null);

      try {
        await api.post<{ status: string }>("settings/favorite-models", {
          modelIds: newFavoriteModels,
        });
      } catch (changeError) {
        const message =
          changeError instanceof Error
            ? changeError.message
            : t("model_list.update_favorites_failed");
        setError(message);
      } finally {
        setUpdatingModelId(null);
      }
    },
    [disabled, favoriteModelIds, settings, t],
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
            "h-8 min-w-0 justify-start gap-1.5 rounded-full px-2 font-normal text-foreground",
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
          <span className="min-w-0 flex-1 truncate text-left">
            {currentModelLabel}
          </span>
          <ChevronDown className="size-3.5 shrink-0" />
        </Button>
      </PopoverTrigger>

      <PopoverContent side="top" align="end" sideOffset={8} className="w-[min(92vw,20rem)] max-h-[var(--radix-popover-content-available-height)] overflow-y-auto space-y-3 p-4">
        <PopoverTitle className="text-sm">{t("model_list.title")}</PopoverTitle>

        <div className="space-y-3">
          <div className="relative">
            <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2" />
            <Input
              value={searchKeywords}
              onChange={(event) => {
                setSearchKeywords(event.target.value);
              }}
              placeholder={t("model_list.search_placeholder")}
              className="h-8 pl-7 text-xs"
            />
          </div>

          {error ? (
            <div className="rounded-md border border-destructive/30 bg-destructive/10 px-2.5 py-1.5 text-[11px] text-destructive">
              {error}
            </div>
          ) : null}

          <div>
            {sections.length === 0 && favoriteModels.length === 0 ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t("model_list.empty")}
              </div>
            ) : (
              <div className="flex min-h-0 flex-col gap-2">
                <ScrollArea className="max-h-20 w-full **:data-[slot=scroll-area-viewport]:max-h-20">
                  <div className="flex flex-wrap items-center gap-1.5 pb-1">
                    {favoriteModels.length > 0 && (
                      <button
                        type="button"
                        className={cn(
                          "bg-muted/60 hover:bg-muted inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs transition",
                          isFavoriteSectionSelected && "border-primary bg-primary/10 text-primary",
                        )}
                        onClick={() => {
                          setSelectedProviderId(FAVORITE_SECTION_ID);
                        }}
                      >
                        <Heart className={cn("size-3", isFavoriteSectionSelected && "fill-current")} />
                        <span>{t("model_list.favorites")}</span>
                      </button>
                    )}

                    {sections.map((section) => {
                      const selected = section.providerId === selectedProviderId;
                      return (
                        <button
                          key={section.providerId}
                          type="button"
                          className={cn(
                            "bg-muted/60 hover:bg-muted inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs transition",
                            selected && "border-primary bg-primary/10 text-primary",
                          )}
                          onClick={() => {
                            setSelectedProviderId(section.providerId);
                          }}
                        >
                          <AIIcon
                            name={section.providerName}
                            size={12}
                            className="bg-transparent"
                            imageClassName="h-full w-full"
                          />
                          <span className="truncate">{section.providerName}</span>
                        </button>
                      );
                    })}
                  </div>
                </ScrollArea>

                <ScrollArea className="h-auto max-h-64 **:data-[slot=scroll-area-viewport]:max-h-64">
                  <div className="space-y-0.5">
                    {displayedModels.map((model) => (
                      <ModelOptionRow
                        key={model.id}
                        model={model}
                        selected={model.id === currentModelId}
                        updating={model.id === updatingModelId}
                        favorite={favoriteModelIdSet.has(model.id)}
                        disabled={disabled || updatingModelId !== null}
                        onSelect={handleSelectModel}
                        onToggleFavorite={handleToggleFavorite}
                      />
                    ))}
                  </div>
                </ScrollArea>
              </div>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
