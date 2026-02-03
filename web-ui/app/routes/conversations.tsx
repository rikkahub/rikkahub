import * as React from "react";

import { useNavigate, useParams } from "react-router";

import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarSeparator,
  SidebarTrigger,
} from "~/components/ui/sidebar";
import Markdown from "~/components/markdown";
import api from "~/services/api";

type ConversationListDto = {
  id: string;
  assistantId: string;
  title: string;
  isPinned: boolean;
  createAt: number;
  updateAt: number;
};

type MessagePartDto = {
  type?: string;
  text?: string;
  reasoning?: string;
  toolName?: string;
  input?: string;
  output?: string | null;
  url?: string;
  fileName?: string;
  mime?: string;
  isPending?: boolean;
  isExecuted?: boolean;
  isFinished?: boolean;
};

type MessageDto = {
  id: string;
  role: string;
  parts: MessagePartDto[];
  createdAt: string;
  finishedAt?: string | null;
  translation?: string | null;
};

type MessageNodeDto = {
  id: string;
  messages: MessageDto[];
  selectIndex: number;
};

type ConversationDto = {
  id: string;
  assistantId: string;
  title: string;
  messages: MessageNodeDto[];
  truncateIndex: number;
  chatSuggestions: string[];
  isPinned: boolean;
  createAt: number;
  updateAt: number;
  isGenerating: boolean;
};

export function meta() {
  return [
    { title: "RikkaHub Web" },
    { name: "description", content: "RikkaHub web client" },
  ];
}

export default function ConversationsPage() {
  const navigate = useNavigate();
  const { id: routeId } = useParams();
  const [conversations, setConversations] = React.useState<
    ConversationListDto[]
  >([]);
  const [activeId, setActiveId] = React.useState<string | null>(
    routeId ?? null,
  );
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [detail, setDetail] = React.useState<ConversationDto | null>(null);
  const [detailLoading, setDetailLoading] = React.useState(false);
  const [detailError, setDetailError] = React.useState<string | null>(null);

  React.useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    api
      .get<ConversationListDto[]>("/conversations")
      .then((data) => {
        if (!active) return;
        const sorted = [...data].sort((a, b) => {
          if (a.isPinned !== b.isPinned) {
            return a.isPinned ? -1 : 1;
          }
          return b.updateAt - a.updateAt;
        });
        setConversations(sorted);
        if (routeId && sorted.some((item) => item.id === routeId)) {
          setActiveId(routeId);
          return;
        }
        setActiveId((current) => current ?? sorted[0]?.id ?? null);
      })
      .catch((err: Error) => {
        if (!active) return;
        setError(err.message || "加载会话失败");
      })
      .finally(() => {
        if (!active) return;
        setLoading(false);
      });

    return () => {
      active = false;
    };
  }, []);

  React.useEffect(() => {
    if (!routeId) return;
    if (conversations.some((item) => item.id === routeId)) {
      setActiveId(routeId);
    }
  }, [routeId, conversations]);

  React.useEffect(() => {
    if (!activeId) {
      setDetail(null);
      return;
    }
    let active = true;
    setDetailLoading(true);
    setDetailError(null);
    api
      .get<ConversationDto>(`/conversations/${activeId}`)
      .then((data) => {
        if (!active) return;
        setDetail(data);
      })
      .catch((err: Error) => {
        if (!active) return;
        setDetailError(err.message || "加载会话详情失败");
        setDetail(null);
      })
      .finally(() => {
        if (!active) return;
        setDetailLoading(false);
      });

    return () => {
      active = false;
    };
  }, [activeId]);

  const activeConversation = conversations.find((item) => item.id === activeId);
  const selectedMessages = React.useMemo(() => {
    if (!detail) return [];
    return detail.messages
      .map((node) => node.messages[node.selectIndex] ?? node.messages[0])
      .filter(Boolean) as MessageDto[];
  }, [detail]);

  const handleSelect = React.useCallback(
    (id: string) => {
      setActiveId(id);
      if (routeId !== id) {
        navigate(`/c/${id}`);
      }
    },
    [navigate, routeId],
  );

  return (
    <SidebarProvider defaultOpen>
      <Sidebar collapsible="icon" variant="sidebar">
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
                    <div className="px-2 py-2 text-xs text-muted-foreground">
                      加载中...
                    </div>
                  </SidebarMenuItem>
                )}
                {error && (
                  <SidebarMenuItem>
                    <div className="px-2 py-2 text-xs text-destructive">
                      {error}
                    </div>
                  </SidebarMenuItem>
                )}
                {!loading && !error && conversations.length === 0 && (
                  <SidebarMenuItem>
                    <div className="px-2 py-2 text-xs text-muted-foreground">
                      暂无会话
                    </div>
                  </SidebarMenuItem>
                )}
                {conversations.map((item) => (
                  <SidebarMenuItem key={item.id}>
                    <SidebarMenuButton
                      isActive={item.id === activeId}
                      onClick={() => handleSelect(item.id)}
                    >
                      <span className="flex w-full items-center gap-2">
                        {item.isPinned && (
                          <span className="text-xs text-muted-foreground">
                            📌
                          </span>
                        )}
                        <span className="flex-1 truncate">
                          {item.title || "未命名会话"}
                        </span>
                      </span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </ScrollArea>
          </SidebarGroup>
        </SidebarContent>
        <SidebarFooter>
          <Button variant="outline" size="sm" className="w-full">
            新建会话
          </Button>
        </SidebarFooter>
      </Sidebar>
      <SidebarInset className="flex min-h-svh flex-col">
        <div className="flex items-center gap-2 border-b px-4 py-3">
          <SidebarTrigger />
          <div className="text-sm text-muted-foreground">
            {activeConversation ? activeConversation.title : "请选择会话"}
          </div>
        </div>
        <ScrollArea className="flex-1">
          <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-6">
            {!activeId && (
              <div className="text-sm text-muted-foreground">
                请选择一个会话以查看消息
              </div>
            )}
            {detailLoading && (
              <div className="text-sm text-muted-foreground">加载中...</div>
            )}
            {detailError && (
              <div className="text-sm text-destructive">{detailError}</div>
            )}
            {!detailLoading &&
              !detailError &&
              activeId &&
              selectedMessages.length === 0 && (
                <div className="text-sm text-muted-foreground">
                  当前会话暂无消息
                </div>
              )}
            {selectedMessages.map((message) => (
              <div
                key={message.id}
                className="flex flex-col gap-2 rounded-lg border bg-card p-3 text-sm shadow-sm"
              >
                <div className="text-xs uppercase text-muted-foreground">
                  {message.role}
                </div>
                <div className="flex flex-col gap-2">
                  {message.parts.map((part, index) => {
                    if (typeof part.text === "string" && part.text.length > 0) {
                      return <Markdown key={index} content={part.text} />;
                    }
                    if (
                      typeof part.reasoning === "string" &&
                      part.reasoning.length > 0
                    ) {
                      return (
                        <div
                          key={index}
                          className="whitespace-pre-wrap text-muted-foreground"
                        >
                          {part.reasoning}
                        </div>
                      );
                    }
                    if (part.toolName) {
                      return (
                        <div
                          key={index}
                          className="rounded-md border bg-muted/40 p-2 text-xs"
                        >
                          <div className="font-medium">{part.toolName}</div>
                          {part.input && (
                            <div className="mt-1 whitespace-pre-wrap">
                              {part.input}
                            </div>
                          )}
                          {part.output && (
                            <div className="mt-1 whitespace-pre-wrap text-muted-foreground">
                              {part.output}
                            </div>
                          )}
                        </div>
                      );
                    }
                    if (part.url) {
                      return (
                        <div
                          key={index}
                          className="text-xs text-muted-foreground"
                        >
                          {part.fileName ?? part.url}
                        </div>
                      );
                    }
                    return (
                      <div
                        key={index}
                        className="text-xs text-muted-foreground"
                      >
                        [Unsupported part]
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </ScrollArea>
      </SidebarInset>
    </SidebarProvider>
  );
}
