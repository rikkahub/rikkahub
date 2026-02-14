import * as React from "react";

import dayjs from "dayjs";
import type { TFunction } from "i18next";
import { toast } from "sonner";
import {
  Check,
  Laptop,
  Languages,
  Moon,
  MoreHorizontal,
  MoveRight,
  Palette,
  ArrowUp,
  Pencil,
  Pin,
  PinOff,
  Plus,
  RefreshCw,
  LogOut,
  Sun,
  Trash2,
} from "lucide-react";
import { useTranslation } from "react-i18next";

import { InfiniteScrollArea } from "~/components/extended/infinite-scroll-area";
import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { ScrollArea } from "~/components/ui/scroll-area";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuAction,
  SidebarMenuButton,
  SidebarMenuItem,
} from "~/components/ui/sidebar";
import { UIAvatar } from "~/components/ui/ui-avatar";
import {
  useTheme,
  type ColorTheme,
  type CustomThemeCss,
  type Theme,
} from "~/components/theme-provider";
import { ConversationSearchButton } from "~/components/conversation-search-button";
import { CustomThemeDialog } from "~/components/custom-theme-dialog";
import { getAssistantDisplayName } from "~/lib/display";
import { clearWebAuthToken } from "~/services/api";
import type { AssistantAvatar, AssistantProfile, AssistantTag, ConversationListDto } from "~/types";

const THEME_OPTIONS: Array<{
  value: Theme;
  labelKey: string;
  icon: React.ComponentType<{ className?: string }>;
}> = [
  {
    value: "light",
    labelKey: "theme_light",
    icon: Sun,
  },
  {
    value: "dark",
    labelKey: "theme_dark",
    icon: Moon,
  },
  {
    value: "system",
    labelKey: "theme_system",
    icon: Laptop,
  },
];

const COLOR_THEME_OPTIONS: Array<{
  value: ColorTheme;
  labelKey: string;
}> = [
  {
    value: "default",
    labelKey: "color_default",
  },
  {
    value: "claude",
    labelKey: "color_claude",
  },
  {
    value: "t3-chat",
    labelKey: "color_t3_chat",
  },
  {
    value: "mono",
    labelKey: "color_mono",
  },
  {
    value: "bubblegum",
    labelKey: "color_bubblegum",
  },
  {
    value: "custom",
    labelKey: "color_custom",
  },
];

const LANGUAGE_OPTIONS = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en-US", label: "English" },
] as const;

type ConversationListItem =
  | { type: "pinned-header" }
  | { type: "date-header"; date: string; label: string }
  | { type: "item"; conversation: ConversationListDto };

function getDateLabel(date: dayjs.Dayjs, t: TFunction): string {
  const today = dayjs().startOf("day");
  const yesterday = today.subtract(1, "day");

  if (date.isSame(today, "day")) return t("conversation_sidebar.today");
  if (date.isSame(yesterday, "day")) return t("conversation_sidebar.yesterday");

  const native = date.toDate();
  const sameYear = date.year() === today.year();
  const formatter = new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  });
  return formatter.format(native);
}

function groupConversations(
  conversations: ConversationListDto[],
  t: TFunction,
): ConversationListItem[] {
  const items: ConversationListItem[] = [];
  const pinned = conversations.filter((c) => c.isPinned);
  const unpinned = conversations.filter((c) => !c.isPinned);

  if (pinned.length > 0) {
    items.push({ type: "pinned-header" });
    for (const c of pinned) {
      items.push({ type: "item", conversation: c });
    }
  }

  let lastDate: string | null = null;
  for (const c of unpinned) {
    const date = dayjs(c.updateAt).startOf("day");
    const dateKey = date.format("YYYY-MM-DD");
    if (dateKey !== lastDate) {
      items.push({ type: "date-header", date: dateKey, label: getDateLabel(date, t) });
      lastDate = dateKey;
    }
    items.push({ type: "item", conversation: c });
  }

  return items;
}

export interface ConversationSidebarProps {
  conversations: ConversationListDto[];
  activeId: string | null;
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  userName: string;
  userAvatar?: AssistantAvatar | null;
  assistants: AssistantProfile[];
  assistantTags: AssistantTag[];
  currentAssistantId: string | null;
  onSelect: (id: string) => void;
  onAssistantChange: (assistantId: string) => Promise<void>;
  onPin?: (id: string) => Promise<void>;
  onRegenerateTitle?: (id: string) => Promise<void>;
  onMoveToAssistant?: (id: string, assistantId: string) => Promise<void>;
  onUpdateTitle?: (id: string, title: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
  onCreateConversation?: () => void;
  webAuthEnabled?: boolean;
}

interface ConversationListRowProps {
  conversation: ConversationListDto;
  isActive: boolean;
  assistants: AssistantProfile[];
  onSelect: (id: string) => void;
  onPin?: (id: string) => Promise<void>;
  onRegenerateTitle?: (id: string) => Promise<void>;
  onMoveToAssistant?: (id: string, assistantId: string) => Promise<void>;
  onUpdateTitle?: (id: string, title: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
}

function ConversationListRow({
  conversation,
  isActive,
  assistants,
  onSelect,
  onPin,
  onRegenerateTitle,
  onMoveToAssistant,
  onUpdateTitle,
  onDelete,
}: ConversationListRowProps) {
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = React.useState(false);
  const [pendingAction, setPendingAction] = React.useState<string | null>(null);

  const moveTargets = React.useMemo(
    () => assistants.filter((assistant) => assistant.id !== conversation.assistantId),
    [assistants, conversation.assistantId],
  );

  const hasMenuAction = Boolean(
    onPin || onRegenerateTitle || onMoveToAssistant || onUpdateTitle || onDelete,
  );

  const runAction = React.useCallback(
    async (
      actionId: string,
      action: () => Promise<void>,
      messages?: { success?: string; error?: string },
    ) => {
      setPendingAction(actionId);
      try {
        await action();
        setMenuOpen(false);
        if (messages?.success) {
          toast.success(messages.success);
        }
      } catch (error) {
        console.error("Conversation action failed", error);
        toast.error(messages?.error ?? t("conversation_sidebar.action_failed_retry"));
      } finally {
        setPendingAction(null);
      }
    },
    [t],
  );
  return (
    <SidebarMenuItem>
      <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
        <SidebarMenuButton
          isActive={isActive}
          onClick={() => onSelect(conversation.id)}
          onContextMenu={(event) => {
            if (!hasMenuAction) return;
            event.preventDefault();
            setMenuOpen(true);
          }}
        >
          <span className="flex w-full items-center gap-2">
            <span className="flex-1 truncate">
              {conversation.title || t("conversation_sidebar.unnamed_conversation")}
            </span>
            {conversation.isPinned && <Pin className="size-3 text-primary" aria-hidden />}
            {conversation.isGenerating && (
              <span
                className="inline-block size-2 rounded-full bg-emerald-500"
                aria-label={t("conversation_sidebar.generating")}
                title={t("conversation_sidebar.generating")}
              />
            )}
          </span>
        </SidebarMenuButton>

        {hasMenuAction && (
          <>
            <DropdownMenuTrigger asChild>
              <SidebarMenuAction
                showOnHover
                aria-label={t("conversation_sidebar.conversation_actions")}
                title={t("conversation_sidebar.conversation_actions")}
                disabled={pendingAction !== null}
                onClick={(event) => {
                  event.stopPropagation();
                }}
              >
                <MoreHorizontal className="size-4" />
              </SidebarMenuAction>
            </DropdownMenuTrigger>
            <DropdownMenuContent side="right" align="start" className="w-48">
              {onPin && (
                <DropdownMenuItem
                  disabled={pendingAction !== null}
                  onSelect={(event) => {
                    event.preventDefault();
                    void runAction(
                      "pin",
                      async () => {
                        await onPin(conversation.id);
                      },
                      {
                        success: conversation.isPinned
                          ? t("conversation_sidebar.unpin_success")
                          : t("conversation_sidebar.pin_success"),
                        error: conversation.isPinned
                          ? t("conversation_sidebar.unpin_failed")
                          : t("conversation_sidebar.pin_failed"),
                      },
                    );
                  }}
                >
                  {conversation.isPinned ? (
                    <PinOff className="size-4" />
                  ) : (
                    <Pin className="size-4" />
                  )}
                  <span>
                    {conversation.isPinned
                      ? t("conversation_sidebar.unpin")
                      : t("conversation_sidebar.pin")}
                  </span>
                </DropdownMenuItem>
              )}

              {onRegenerateTitle && (
                <DropdownMenuItem
                  disabled={pendingAction !== null}
                  onSelect={(event) => {
                    event.preventDefault();
                    void runAction(
                      "regenerate-title",
                      async () => {
                        await onRegenerateTitle(conversation.id);
                      },
                      {
                        success: t("conversation_sidebar.regenerate_title_success"),
                        error: t("conversation_sidebar.regenerate_title_failed"),
                      },
                    );
                  }}
                >
                  <RefreshCw className="size-4" />
                  <span>{t("conversation_sidebar.regenerate_title")}</span>
                </DropdownMenuItem>
              )}

              {onUpdateTitle && (
                <DropdownMenuItem
                  disabled={pendingAction !== null}
                  onSelect={(event) => {
                    event.preventDefault();
                    const nextTitle = window
                      .prompt(t("conversation_sidebar.edit_title_prompt"), conversation.title)
                      ?.trim();
                    if (nextTitle == null) {
                      return;
                    }
                    if (nextTitle.length === 0) {
                      toast.error(t("conversation_sidebar.title_empty"));
                      return;
                    }
                    if (nextTitle === conversation.title) {
                      return;
                    }
                    void runAction(
                      "update-title",
                      async () => {
                        await onUpdateTitle(conversation.id, nextTitle);
                      },
                      {
                        success: t("conversation_sidebar.title_updated"),
                        error: t("conversation_sidebar.title_update_failed"),
                      },
                    );
                  }}
                >
                  <Pencil className="size-4" />
                  <span>{t("conversation_sidebar.edit_title")}</span>
                </DropdownMenuItem>
              )}

              {onMoveToAssistant && (
                <DropdownMenuSub>
                  <DropdownMenuSubTrigger
                    disabled={pendingAction !== null || moveTargets.length === 0}
                  >
                    <MoveRight className="size-4" />
                    <span>{t("conversation_sidebar.move_to_assistant")}</span>
                  </DropdownMenuSubTrigger>
                  <DropdownMenuSubContent>
                    {moveTargets.length === 0 ? (
                      <DropdownMenuItem disabled>
                        {t("conversation_sidebar.no_available_assistants")}
                      </DropdownMenuItem>
                    ) : (
                      moveTargets.map((assistant) => (
                        <DropdownMenuItem
                          key={assistant.id}
                          disabled={pendingAction !== null}
                          onSelect={(event) => {
                            event.preventDefault();
                            void runAction(
                              `move:${assistant.id}`,
                              async () => {
                                await onMoveToAssistant(conversation.id, assistant.id);
                              },
                              {
                                success: t("conversation_sidebar.moved_to_assistant", {
                                  assistant: getAssistantDisplayName(assistant.name),
                                }),
                                error: t("conversation_sidebar.move_conversation_failed"),
                              },
                            );
                          }}
                        >
                          {getAssistantDisplayName(assistant.name)}
                        </DropdownMenuItem>
                      ))
                    )}
                  </DropdownMenuSubContent>
                </DropdownMenuSub>
              )}

              {onDelete && (
                <>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem
                    variant="destructive"
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      if (!window.confirm(t("conversation_sidebar.delete_confirm"))) {
                        return;
                      }
                      void runAction(
                        "delete",
                        async () => {
                          await onDelete(conversation.id);
                        },
                        {
                          success: t("conversation_sidebar.delete_success"),
                          error: t("conversation_sidebar.delete_failed"),
                        },
                      );
                    }}
                  >
                    <Trash2 className="size-4" />
                    <span>{t("conversation_sidebar.delete_conversation")}</span>
                  </DropdownMenuItem>
                </>
              )}
            </DropdownMenuContent>
          </>
        )}
      </DropdownMenu>
    </SidebarMenuItem>
  );
}

function resolveLanguage(language: string): (typeof LANGUAGE_OPTIONS)[number]["value"] {
  return language.startsWith("zh") ? "zh-CN" : "en-US";
}

function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const currentLanguage = resolveLanguage(i18n.resolvedLanguage ?? i18n.language);
  const currentOption =
    LANGUAGE_OPTIONS.find((option) => option.value === currentLanguage) ?? LANGUAGE_OPTIONS[0];

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="icon-sm"
          className="text-foreground"
          type="button"
          aria-label={`Language: ${currentOption.label}`}
          title={`Language: ${currentOption.label}`}
        >
          <Languages className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-40" side="top" align="end">
        <DropdownMenuLabel>Language</DropdownMenuLabel>
        {LANGUAGE_OPTIONS.map((option) => {
          const selected = option.value === currentLanguage;
          return (
            <DropdownMenuItem
              key={option.value}
              onClick={() => {
                void i18n.changeLanguage(option.value);
              }}
            >
              <span className="flex-1">{option.label}</span>
              <Check className={selected ? "size-4" : "size-4 opacity-0"} />
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export function ConversationSidebar({
  conversations,
  activeId,
  loading,
  error,
  hasMore,
  loadMore,
  userName,
  userAvatar,
  assistants,
  assistantTags,
  currentAssistantId,
  onSelect,
  onAssistantChange,
  onPin,
  onRegenerateTitle,
  onMoveToAssistant,
  onUpdateTitle,
  onDelete,
  onCreateConversation,
  webAuthEnabled = false,
}: ConversationSidebarProps) {
  const { t, i18n } = useTranslation();
  const { theme, setTheme, colorTheme, setColorTheme, customThemeCss, setCustomThemeCss } =
    useTheme();

  const [pickerOpen, setPickerOpen] = React.useState(false);
  const [customThemeOpen, setCustomThemeOpen] = React.useState(false);
  const [selectedTagIds, setSelectedTagIds] = React.useState<string[]>([]);
  const [switchingAssistantId, setSwitchingAssistantId] = React.useState<string | null>(null);
  const [switchError, setSwitchError] = React.useState<string | null>(null);
  const [showBackToTop, setShowBackToTop] = React.useState(false);

  const currentTheme = theme;
  const currentThemeOption =
    THEME_OPTIONS.find((option) => option.value === currentTheme) ?? THEME_OPTIONS[2];
  const CurrentThemeIcon = currentThemeOption.icon;
  const currentColorThemeOption =
    COLOR_THEME_OPTIONS.find((option) => option.value === colorTheme) ?? COLOR_THEME_OPTIONS[0];

  const handleCustomThemeSave = React.useCallback(
    (themeCss: CustomThemeCss) => {
      setCustomThemeCss(themeCss);
      setColorTheme("custom");
      toast.success(t("conversation_sidebar.custom_theme_saved"));
    },
    [setColorTheme, setCustomThemeCss, t],
  );

  const currentAssistant = React.useMemo(
    () =>
      assistants.find((assistant) => assistant.id === currentAssistantId) ?? assistants[0] ?? null,
    [assistants, currentAssistantId],
  );

  const groupedItems = React.useMemo(
    () => groupConversations(conversations, t),
    [conversations, i18n.resolvedLanguage, t],
  );

  const filteredAssistants = React.useMemo(() => {
    if (selectedTagIds.length === 0) {
      return assistants;
    }
    return assistants.filter((assistant) =>
      assistant.tags.some((tagId) => selectedTagIds.includes(tagId)),
    );
  }, [assistants, selectedTagIds]);

  const toggleTag = React.useCallback((tagId: string) => {
    setSelectedTagIds((current) =>
      current.includes(tagId) ? current.filter((id) => id !== tagId) : [...current, tagId],
    );
  }, []);

  const handleAssistantSelect = React.useCallback(
    async (assistantId: string) => {
      if (assistantId === currentAssistantId) {
        setPickerOpen(false);
        return;
      }
      setSwitchError(null);
      setSwitchingAssistantId(assistantId);
      try {
        await onAssistantChange(assistantId);
        setPickerOpen(false);
      } catch (switchAssistantError) {
        if (switchAssistantError instanceof Error) {
          setSwitchError(switchAssistantError.message);
        } else {
          setSwitchError(t("conversation_sidebar.switch_assistant_failed"));
        }
      } finally {
        setSwitchingAssistantId(null);
      }
    },
    [currentAssistantId, onAssistantChange],
  );

  const handleBackToTop = React.useCallback(() => {
    const scrollContainer = document.getElementById("conversationScrollTarget");
    if (!scrollContainer) return;
    scrollContainer.scrollTo({ top: 0, behavior: "smooth" });
  }, []);

  const handleWebLogout = React.useCallback(() => {
    clearWebAuthToken();
    toast.success("Web session cleared");
    window.location.reload();
  }, []);

  React.useEffect(() => {
    const scrollContainer = document.getElementById("conversationScrollTarget");
    if (!scrollContainer) return;

    const handleScroll = () => {
      setShowBackToTop(scrollContainer.scrollTop > 240);
    };

    handleScroll();
    scrollContainer.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      scrollContainer.removeEventListener("scroll", handleScroll);
    };
  }, [conversations.length]);

  return (
    <Sidebar collapsible="offcanvas" variant="sidebar">
      <SidebarHeader>
        <div className="flex items-center gap-3 rounded-lg px-2.5 py-2.5">
          <UIAvatar
            size="default"
            name={userName}
            avatar={userAvatar}
            className="ring-1 ring-sidebar-border/70"
          />
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium leading-none">{userName}</div>
            <div className="mt-1 truncate text-xs text-muted-foreground">
              {t("conversation_sidebar.welcome_back")}
            </div>
          </div>
        </div>
      </SidebarHeader>
      <SidebarContent className="min-h-0">
        <SidebarGroup>
          <div className="space-y-1">
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start"
              onClick={onCreateConversation}
            >
              <Plus className="size-4" />
              {t("conversation_sidebar.new_conversation")}
            </Button>

            <ConversationSearchButton onSelect={onSelect} />
          </div>
        </SidebarGroup>

        <SidebarGroup className="flex min-h-0 flex-1 flex-col">
          <SidebarGroupLabel>{t("conversation_sidebar.conversations")}</SidebarGroupLabel>
          <InfiniteScrollArea
            dataLength={conversations.length}
            next={loadMore}
            hasMore={hasMore}
            scrollTargetId="conversationScrollTarget"
          >
            <SidebarMenu>
              {loading && (
                <SidebarMenuItem>
                  <div className="px-2 py-2 text-xs text-muted-foreground">
                    {t("conversation_sidebar.loading")}
                  </div>
                </SidebarMenuItem>
              )}
              {error && (
                <SidebarMenuItem>
                  <div className="px-2 py-2 text-xs text-destructive">{error}</div>
                </SidebarMenuItem>
              )}
              {!loading && !error && conversations.length === 0 && (
                <SidebarMenuItem>
                  <div className="px-2 py-2 text-xs text-muted-foreground">
                    {t("conversation_sidebar.no_conversations")}
                  </div>
                </SidebarMenuItem>
              )}
              {groupedItems.map((listItem) => {
                if (listItem.type === "pinned-header") {
                  return (
                    <SidebarMenuItem key="pinned_header">
                      <div className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-semibold text-primary">
                        <Pin className="size-3" />
                        {t("conversation_sidebar.pinned")}
                      </div>
                    </SidebarMenuItem>
                  );
                }
                if (listItem.type === "date-header") {
                  return (
                    <SidebarMenuItem key={`date_${listItem.date}`}>
                      <div className="px-2 py-1.5 text-xs font-semibold text-primary">
                        {listItem.label}
                      </div>
                    </SidebarMenuItem>
                  );
                }
                const item = listItem.conversation;
                return (
                  <ConversationListRow
                    key={item.id}
                    conversation={item}
                    isActive={item.id === activeId}
                    assistants={assistants}
                    onSelect={onSelect}
                    onPin={onPin}
                    onRegenerateTitle={onRegenerateTitle}
                    onMoveToAssistant={onMoveToAssistant}
                    onUpdateTitle={onUpdateTitle}
                    onDelete={onDelete}
                  />
                );
              })}
            </SidebarMenu>
          </InfiniteScrollArea>
          {showBackToTop && (
            <div className="pointer-events-none absolute right-0 bottom-4 left-0 flex justify-center">
              <Button
                type="button"
                variant="secondary"
                size="icon-sm"
                className="pointer-events-auto shadow-sm"
                aria-label={t("conversation_sidebar.back_to_top")}
                title={t("conversation_sidebar.back_to_top")}
                onClick={handleBackToTop}
              >
                <ArrowUp className="size-4" />
              </Button>
            </div>
          )}
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter>
        <Dialog
          open={pickerOpen}
          onOpenChange={(open) => {
            setPickerOpen(open);
            if (!open) {
              setSwitchError(null);
            }
          }}
        >
          <DialogTrigger asChild>
            <Button
              variant="outline"
              className="w-full justify-start gap-2 text-foreground"
              type="button"
            >
              {currentAssistant ? (
                <>
                  <UIAvatar
                    key={currentAssistant.id}
                    size="sm"
                    name={getAssistantDisplayName(currentAssistant.name)}
                    avatar={currentAssistant.avatar}
                  />
                  <span className="truncate">{getAssistantDisplayName(currentAssistant.name)}</span>
                </>
              ) : (
                <span className="truncate">{t("conversation_sidebar.select_assistant")}</span>
              )}
            </Button>
          </DialogTrigger>
          <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
            <DialogHeader className="border-b px-6 py-4">
              <DialogTitle>{t("conversation_sidebar.select_assistant")}</DialogTitle>
            </DialogHeader>
            <div className="space-y-4 px-6 py-4">
              {assistantTags.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {assistantTags.map((tag) => {
                    const selected = selectedTagIds.includes(tag.id);
                    return (
                      <Button
                        key={tag.id}
                        type="button"
                        size="sm"
                        variant={selected ? "default" : "outline"}
                        onClick={() => toggleTag(tag.id)}
                      >
                        {tag.name}
                      </Button>
                    );
                  })}
                </div>
              )}

              {switchError && (
                <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                  {switchError}
                </div>
              )}

              <ScrollArea className="h-[380px]">
                <div className="space-y-2">
                  {filteredAssistants.map((assistant) => {
                    const selected = assistant.id === currentAssistantId;
                    const switching = switchingAssistantId === assistant.id;
                    const displayName = getAssistantDisplayName(assistant.name);
                    return (
                      <button
                        key={assistant.id}
                        type="button"
                        className="flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition hover:bg-muted"
                        onClick={() => void handleAssistantSelect(assistant.id)}
                        disabled={switchingAssistantId !== null}
                      >
                        <UIAvatar size="sm" name={displayName} avatar={assistant.avatar} />
                        <span className="min-w-0 flex-1 truncate text-sm">{displayName}</span>
                        {selected && !switching && (
                          <Badge variant="secondary" className="gap-1">
                            <Check className="size-3" />
                            {t("conversation_sidebar.current")}
                          </Badge>
                        )}
                        {switching && (
                          <Badge variant="secondary" className="text-xs">
                            {t("conversation_sidebar.switching")}
                          </Badge>
                        )}
                      </button>
                    );
                  })}
                  {filteredAssistants.length === 0 && (
                    <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                      {t("conversation_sidebar.no_assistants_by_tag")}
                    </div>
                  )}
                </div>
              </ScrollArea>
            </div>
          </DialogContent>
        </Dialog>

        <CustomThemeDialog
          open={customThemeOpen}
          onOpenChange={setCustomThemeOpen}
          initialCss={customThemeCss}
          onSave={handleCustomThemeSave}
        />

        <div className="flex items-center gap-2">
          {webAuthEnabled && (
            <Button
              variant="outline"
              size="icon-sm"
              className="text-foreground"
              type="button"
              onClick={handleWebLogout}
              aria-label="Clear web session"
              title="Clear web session"
            >
              <LogOut className="size-4" />
            </Button>
          )}

          <LanguageSwitcher />

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="icon-sm"
                className="text-foreground"
                type="button"
                aria-label={t("conversation_sidebar.theme_mode_label", {
                  label: t(`conversation_sidebar.${currentThemeOption.labelKey}`),
                })}
                title={t("conversation_sidebar.theme_mode_label", {
                  label: t(`conversation_sidebar.${currentThemeOption.labelKey}`),
                })}
              >
                <CurrentThemeIcon className="size-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-44" side="top" align="end">
              <DropdownMenuLabel>{t("conversation_sidebar.theme_mode")}</DropdownMenuLabel>
              {THEME_OPTIONS.map((option) => {
                const selected = option.value === currentTheme;
                const ThemeOptionIcon = option.icon;
                return (
                  <DropdownMenuItem
                    key={option.value}
                    onClick={() => {
                      setTheme(option.value);
                    }}
                  >
                    <ThemeOptionIcon className="size-4" />
                    <span className="flex-1">{t(`conversation_sidebar.${option.labelKey}`)}</span>
                    <Check className={selected ? "size-4" : "size-4 opacity-0"} />
                  </DropdownMenuItem>
                );
              })}
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="icon-sm"
                className="text-foreground"
                type="button"
                aria-label={t("conversation_sidebar.theme_color_label", {
                  label: t(`conversation_sidebar.${currentColorThemeOption.labelKey}`),
                })}
                title={t("conversation_sidebar.theme_color_label", {
                  label: t(`conversation_sidebar.${currentColorThemeOption.labelKey}`),
                })}
              >
                <Palette className="size-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-44" side="top" align="end">
              <DropdownMenuLabel>{t("conversation_sidebar.theme_color")}</DropdownMenuLabel>
              {COLOR_THEME_OPTIONS.map((option) => {
                const selected = option.value === colorTheme;
                return (
                  <DropdownMenuItem
                    key={option.value}
                    onClick={() => {
                      setColorTheme(option.value);
                      if (option.value === "custom") {
                        setCustomThemeOpen(true);
                      }
                    }}
                  >
                    <span className="flex-1">{t(`conversation_sidebar.${option.labelKey}`)}</span>
                    <Check className={selected ? "size-4" : "size-4 opacity-0"} />
                  </DropdownMenuItem>
                );
              })}
              <DropdownMenuSeparator />
              <DropdownMenuItem
                onClick={() => {
                  setCustomThemeOpen(true);
                }}
              >
                <span className="flex-1">{t("conversation_sidebar.edit_custom_css")}</span>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <a
            href="https://rikka-ai.com"
            target="_blank"
            rel="noopener noreferrer"
            className="ml-auto text-xs font-normal text-foreground/80 hover:text-foreground transition-colors"
          >
            RikkaHub
          </a>
        </div>
      </SidebarFooter>
    </Sidebar>
  );
}
