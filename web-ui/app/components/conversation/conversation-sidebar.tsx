import * as React from "react";

import { Check, Drama } from "lucide-react";

import { Avatar, AvatarFallback } from "~/components/ui/avatar";
import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog";
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
import type { AssistantProfile, AssistantTag, ConversationListDto } from "~/types";

export interface ConversationSidebarProps {
  conversations: ConversationListDto[];
  activeId: string | null;
  loading: boolean;
  error: string | null;
  assistants: AssistantProfile[];
  assistantTags: AssistantTag[];
  currentAssistantId: string | null;
  onSelect: (id: string) => void;
  onAssistantChange: (assistantId: string) => Promise<void>;
  onCreateConversation?: () => void;
}

function getAvatarContent(assistant: AssistantProfile) {
  const avatar = assistant.avatar;
  if (avatar?.content && avatar.content.length > 0) {
    return avatar.content;
  }
  return assistant.name.slice(0, 1).toUpperCase() || "A";
}

export function ConversationSidebar({
  conversations,
  activeId,
  loading,
  error,
  assistants,
  assistantTags,
  currentAssistantId,
  onSelect,
  onAssistantChange,
  onCreateConversation,
}: ConversationSidebarProps) {
  const [pickerOpen, setPickerOpen] = React.useState(false);
  const [selectedTagIds, setSelectedTagIds] = React.useState<string[]>([]);
  const [switchingAssistantId, setSwitchingAssistantId] = React.useState<string | null>(null);
  const [switchError, setSwitchError] = React.useState<string | null>(null);

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
        <div className="flex items-center justify-between px-1">
          <div className="text-sm font-semibold">RikkaHub</div>
          <Button size="icon" variant="ghost" className="md:hidden">
            +
          </Button>
        </div>
      </SidebarHeader>
      <SidebarSeparator />
      <SidebarContent className="min-h-0">
        <SidebarGroup className="flex min-h-0 flex-1 flex-col">
          <SidebarGroupLabel>Conversations</SidebarGroupLabel>
          <ScrollArea className="min-h-0 flex-1">
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
          </ScrollArea>
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
              <Drama className="size-4" />
              <span className="truncate">{currentAssistant?.name || "选择助手"}</span>
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
                    return (
                      <button
                        key={assistant.id}
                        type="button"
                        className="flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition hover:bg-muted"
                        onClick={() => void handleAssistantSelect(assistant.id)}
                        disabled={switchingAssistantId !== null}
                      >
                        <Avatar size="sm">
                          <AvatarFallback>{getAvatarContent(assistant)}</AvatarFallback>
                        </Avatar>
                        <span className="min-w-0 flex-1 truncate text-sm">
                          {assistant.name || "未命名助手"}
                        </span>
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

        <Button variant="outline" size="sm" className="w-full" onClick={onCreateConversation}>
          新建会话
        </Button>
      </SidebarFooter>
    </Sidebar>
  );
}
