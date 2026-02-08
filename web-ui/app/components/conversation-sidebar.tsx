import * as React from "react";

import dayjs from "dayjs";
import { toast } from "sonner";
import {
  Check,
  Laptop,
  Moon,
  MoreHorizontal,
  MoveRight,
  Pencil,
  Pin,
  PinOff,
  Plus,
  RefreshCw,
  Sun,
  Trash2,
} from "lucide-react";

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
import type { AssistantAvatar, AssistantProfile, AssistantTag, ConversationListDto } from "~/types";

const THEME_OPTIONS: Array<{
  value: Theme;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}> = [
  {
    value: "light",
    label: "浅色",
    icon: Sun,
  },
  {
    value: "dark",
    label: "深色",
    icon: Moon,
  },
  {
    value: "system",
    label: "跟随系统",
    icon: Laptop,
  },
];

const COLOR_THEME_OPTIONS: Array<{
  value: ColorTheme;
  label: string;
}> = [
  {
    value: "default",
    label: "默认",
  },
  {
    value: "claude",
    label: "Claude",
  },
  {
    value: "t3-chat",
    label: "T3 Chat",
  },
  {
    value: "mono",
    label: "Mono",
  },
  {
    value: "bubblegum",
    label: "Bubblegum",
  },
  {
    value: "custom",
    label: "自定义",
  },
];

type ConversationListItem =
  | { type: "pinned-header" }
  | { type: "date-header"; date: string; label: string }
  | { type: "item"; conversation: ConversationListDto };

function getDateLabel(date: dayjs.Dayjs): string {
  const today = dayjs().startOf("day");
  const yesterday = today.subtract(1, "day");

  if (date.isSame(today, "day")) return "今天";
  if (date.isSame(yesterday, "day")) return "昨天";

  const native = date.toDate();
  const sameYear = date.year() === today.year();
  const formatter = new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  });
  return formatter.format(native);
}

function groupConversations(conversations: ConversationListDto[]): ConversationListItem[] {
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
      items.push({ type: "date-header", date: dateKey, label: getDateLabel(date) });
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
}

function getAssistantDisplayName(assistant: AssistantProfile) {
  const name = assistant.name.trim();
  if (name.length > 0) {
    return name;
  }

  return "默认助手";
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
        toast.error(messages?.error ?? "操作失败，请稍后重试");
      } finally {
        setPendingAction(null);
      }
    },
    [],
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
            <span className="flex-1 truncate">{conversation.title || "未命名会话"}</span>
            {conversation.isPinned && <Pin className="size-3 text-primary" aria-hidden />}
            {conversation.isGenerating && (
              <span
                className="inline-block size-2 rounded-full bg-emerald-500"
                aria-label="生成中"
                title="生成中"
              />
            )}
          </span>
        </SidebarMenuButton>

        {hasMenuAction && (
          <>
            <DropdownMenuTrigger asChild>
              <SidebarMenuAction
                showOnHover
                aria-label="会话操作"
                title="会话操作"
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
                        success: conversation.isPinned ? "已取消置顶" : "已置顶",
                        error: conversation.isPinned ? "取消置顶失败" : "置顶失败",
                      },
                    );
                  }}
                >
                  {conversation.isPinned ? (
                    <PinOff className="size-4" />
                  ) : (
                    <Pin className="size-4" />
                  )}
                  <span>{conversation.isPinned ? "取消置顶" : "置顶"}</span>
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
                        success: "已请求重新生成标题",
                        error: "重新生成标题失败",
                      },
                    );
                  }}
                >
                  <RefreshCw className="size-4" />
                  <span>重新生成标题</span>
                </DropdownMenuItem>
              )}

              {onUpdateTitle && (
                <DropdownMenuItem
                  disabled={pendingAction !== null}
                  onSelect={(event) => {
                    event.preventDefault();
                    const nextTitle = window.prompt("请输入新标题", conversation.title)?.trim();
                    if (nextTitle == null) {
                      return;
                    }
                    if (nextTitle.length === 0) {
                      toast.error("标题不能为空");
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
                        success: "标题已更新",
                        error: "更新标题失败",
                      },
                    );
                  }}
                >
                  <Pencil className="size-4" />
                  <span>手动编辑标题</span>
                </DropdownMenuItem>
              )}

              {onMoveToAssistant && (
                <DropdownMenuSub>
                  <DropdownMenuSubTrigger
                    disabled={pendingAction !== null || moveTargets.length === 0}
                  >
                    <MoveRight className="size-4" />
                    <span>移动到助手</span>
                  </DropdownMenuSubTrigger>
                  <DropdownMenuSubContent>
                    {moveTargets.length === 0 ? (
                      <DropdownMenuItem disabled>没有可用助手</DropdownMenuItem>
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
                                success: `已移动到 ${getAssistantDisplayName(assistant)}`,
                                error: "移动会话失败",
                              },
                            );
                          }}
                        >
                          {getAssistantDisplayName(assistant)}
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
                      if (!window.confirm("确定删除这个会话吗？")) {
                        return;
                      }
                      void runAction(
                        "delete",
                        async () => {
                          await onDelete(conversation.id);
                        },
                        {
                          success: "会话已删除",
                          error: "删除会话失败",
                        },
                      );
                    }}
                  >
                    <Trash2 className="size-4" />
                    <span>删除会话</span>
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
}: ConversationSidebarProps) {
  const { theme, setTheme, colorTheme, setColorTheme, customThemeCss, setCustomThemeCss } =
    useTheme();

  const [pickerOpen, setPickerOpen] = React.useState(false);
  const [customThemeOpen, setCustomThemeOpen] = React.useState(false);
  const [selectedTagIds, setSelectedTagIds] = React.useState<string[]>([]);
  const [switchingAssistantId, setSwitchingAssistantId] = React.useState<string | null>(null);
  const [switchError, setSwitchError] = React.useState<string | null>(null);

  const currentTheme = theme;
  const currentThemeOption =
    THEME_OPTIONS.find((option) => option.value === currentTheme) ?? THEME_OPTIONS[2];
  const CurrentThemeIcon = currentThemeOption.icon;

  const handleCustomThemeSave = React.useCallback(
    (themeCss: CustomThemeCss) => {
      setCustomThemeCss(themeCss);
      setColorTheme("custom");
      toast.success("自定义主题已保存");
    },
    [setColorTheme, setCustomThemeCss],
  );

  const currentAssistant = React.useMemo(
    () =>
      assistants.find((assistant) => assistant.id === currentAssistantId) ?? assistants[0] ?? null,
    [assistants, currentAssistantId],
  );

  const groupedItems = React.useMemo(() => groupConversations(conversations), [conversations]);

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
          setSwitchError("切换助手失败");
        }
      } finally {
        setSwitchingAssistantId(null);
      }
    },
    [currentAssistantId, onAssistantChange],
  );

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
            <div className="mt-1 truncate text-xs text-muted-foreground">欢迎回来</div>
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
              新建对话
            </Button>

            <ConversationSearchButton onSelect={onSelect} />
          </div>
        </SidebarGroup>

        <SidebarGroup className="flex min-h-0 flex-1 flex-col">
          <SidebarGroupLabel>Conversations</SidebarGroupLabel>
          <InfiniteScrollArea
            dataLength={conversations.length}
            next={loadMore}
            hasMore={hasMore}
            scrollTargetId="conversationScrollTarget"
          >
            <SidebarMenu>
              {loading && (
                <SidebarMenuItem>
                  <div className="px-2 py-2 text-xs text-muted-foreground">加载中...</div>
                </SidebarMenuItem>
              )}
              {error && (
                <SidebarMenuItem>
                  <div className="px-2 py-2 text-xs text-destructive">{error}</div>
                </SidebarMenuItem>
              )}
              {!loading && !error && conversations.length === 0 && (
                <SidebarMenuItem>
                  <div className="px-2 py-2 text-xs text-muted-foreground">暂无会话</div>
                </SidebarMenuItem>
              )}
              {groupedItems.map((listItem) => {
                if (listItem.type === "pinned-header") {
                  return (
                    <SidebarMenuItem key="pinned_header">
                      <div className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-semibold text-primary">
                        <Pin className="size-3" />
                        置顶
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
            <Button variant="outline" className="w-full justify-start gap-2" type="button">
              {currentAssistant ? (
                <>
                  <UIAvatar
                    key={currentAssistant.id}
                    size="sm"
                    name={getAssistantDisplayName(currentAssistant)}
                    avatar={currentAssistant.avatar}
                  />
                  <span className="truncate">{getAssistantDisplayName(currentAssistant)}</span>
                </>
              ) : (
                <span className="truncate">选择助手</span>
              )}
            </Button>
          </DialogTrigger>
          <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
            <DialogHeader className="border-b px-6 py-4">
              <DialogTitle>选择助手</DialogTitle>
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
                    const displayName = getAssistantDisplayName(assistant);
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
                            当前
                          </Badge>
                        )}
                        {switching && (
                          <Badge variant="secondary" className="text-xs">
                            切换中...
                          </Badge>
                        )}
                      </button>
                    );
                  })}
                  {filteredAssistants.length === 0 && (
                    <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                      没有符合标签的助手
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
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="icon-sm"
                type="button"
                aria-label={`颜色模式：${currentThemeOption.label}`}
                title={`颜色模式：${currentThemeOption.label}`}
              >
                <CurrentThemeIcon className="size-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-44" side="top" align="end">
              <DropdownMenuLabel>颜色模式</DropdownMenuLabel>
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
                    <span className="flex-1">{option.label}</span>
                    <Check className={selected ? "size-4" : "size-4 opacity-0"} />
                  </DropdownMenuItem>
                );
              })}
              <DropdownMenuSeparator />
              <DropdownMenuLabel>主题色</DropdownMenuLabel>
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
                    <span className="flex-1">{option.label}</span>
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
                <span className="flex-1">编辑自定义 CSS</span>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <div className="ml-auto text-xs font-light text-muted-foreground">RikkaHub</div>
        </div>
      </SidebarFooter>
    </Sidebar>
  );
}
