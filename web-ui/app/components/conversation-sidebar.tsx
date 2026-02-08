import * as React from "react";

import { Check, Laptop, Moon, Plus, Sun } from "lucide-react";

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
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarSeparator,
} from "~/components/ui/sidebar";
import { UIAvatar } from "~/components/ui/ui-avatar";
import { useTheme, type Theme } from "~/components/theme-provider";
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
  onCreateConversation?: () => void;
}

function getAssistantDisplayName(assistant: AssistantProfile) {
  const name = assistant.name.trim();
  if (name.length > 0) {
    return name;
  }

  return "默认助手";
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
  onCreateConversation,
}: ConversationSidebarProps) {
  const { theme, setTheme } = useTheme();

  const [pickerOpen, setPickerOpen] = React.useState(false);
  const [selectedTagIds, setSelectedTagIds] = React.useState<string[]>([]);
  const [switchingAssistantId, setSwitchingAssistantId] = React.useState<string | null>(null);
  const [switchError, setSwitchError] = React.useState<string | null>(null);

  const currentTheme = theme;
  const currentThemeOption =
    THEME_OPTIONS.find((option) => option.value === currentTheme) ?? THEME_OPTIONS[2];
  const CurrentThemeIcon = currentThemeOption.icon;

  const currentAssistant = React.useMemo(
    () => assistants.find((assistant) => assistant.id === currentAssistantId) ?? assistants[0] ?? null,
    [assistants, currentAssistantId],
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
          <UIAvatar size="default" name={userName} avatar={userAvatar} className="ring-1 ring-sidebar-border/70" />
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium leading-none">{userName}</div>
            <div className="mt-1 truncate text-xs text-muted-foreground">欢迎回来</div>
          </div>
        </div>
      </SidebarHeader>
      <SidebarContent className="min-h-0">
        <SidebarGroup>
          <Button variant="ghost" size="sm" className="w-full justify-start" onClick={onCreateConversation}>
            <Plus className="size-4" />
            新建对话
          </Button>
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
              {conversations.map((item) => (
                <SidebarMenuItem key={item.id}>
                  <SidebarMenuButton
                    isActive={item.id === activeId}
                    onClick={() => onSelect(item.id)}
                  >
                    <span className="flex w-full items-center gap-2">
                      {item.isPinned && <span className="text-xs text-muted-foreground">📌</span>}
                      <span className="flex-1 truncate">{item.title || "未命名会话"}</span>
                    </span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
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
            </DropdownMenuContent>
          </DropdownMenu>

          <div className="ml-auto text-xs font-light text-muted-foreground">RikkaHub</div>
        </div>
      </SidebarFooter>
    </Sidebar>
  );
}
