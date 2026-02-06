import * as React from "react";

import { useNavigate, useParams } from "react-router";

import { ConversationSidebar } from "~/components/conversation/conversation-sidebar";
import {
  Conversation,
  ConversationContent,
  ConversationEmptyState,
  ConversationScrollButton,
} from "~/components/message/conversation";
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "~/components/ui/sidebar";
import { MessagePart } from "~/components/message";
import api from "~/services/api";
import {
  type ConversationListDto,
  type ConversationDto,
  type PagedResult,
  getCurrentMessageDto,
} from "~/types";
import { MessageSquare } from "lucide-react";
import { ChatInput } from "~/components/message/chat-input";
import { useSettingsStore } from "~/stores/settings";

export function meta() {
  return [
    { title: "RikkaHub Web" },
    { name: "description", content: "RikkaHub web client" },
  ];
}

export default function ConversationsPage() {
  const navigate = useNavigate();
  const { id: routeId } = useParams();
  const settings = useSettingsStore((state) => state.settings);
  const currentAssistantId = settings?.assistantId ?? null;
  const [conversations, setConversations] = React.useState<
    ConversationListDto[]
  >([]);
  const [activeId, setActiveId] = React.useState<string | null>(
    routeId ?? null,
  );
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [hasMore, setHasMore] = React.useState(false);
  const nextOffsetRef = React.useRef<number | null>(0);
  const [detail, setDetail] = React.useState<ConversationDto | null>(null);
  const [detailLoading, setDetailLoading] = React.useState(false);
  const [detailError, setDetailError] = React.useState<string | null>(null);
  const [refreshToken, setRefreshToken] = React.useState(0);

  const PAGE_SIZE = 20;

  React.useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setConversations([]);
    nextOffsetRef.current = 0;
    setHasMore(false);
    api
      .get<PagedResult<ConversationListDto>>("conversations/paged", {
        searchParams: { offset: 0, limit: PAGE_SIZE },
      })
      .then((data) => {
        if (!active) return;
        setConversations(data.items);
        nextOffsetRef.current = data.nextOffset ?? null;
        setHasMore(data.hasMore);
        if (routeId && data.items.some((item) => item.id === routeId)) {
          setActiveId(routeId);
          return;
        }
        setActiveId((current) => current ?? data.items[0]?.id ?? null);
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
  }, [currentAssistantId, refreshToken]);

  const loadMore = React.useCallback(() => {
    const offset = nextOffsetRef.current;
    if (offset === null) return;
    api
      .get<PagedResult<ConversationListDto>>("conversations/paged", {
        searchParams: { offset, limit: PAGE_SIZE },
      })
      .then((data) => {
        setConversations((prev) => [...prev, ...data.items]);
        nextOffsetRef.current = data.nextOffset ?? null;
        setHasMore(data.hasMore);
      })
      .catch(() => {
        setHasMore(false);
      });
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
      .get<ConversationDto>(`conversations/${activeId}`)
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
    return detail.messages.map(getCurrentMessageDto);
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

  const handleAssistantChange = React.useCallback(
    async (assistantId: string) => {
      await api.post<{ status: string }>("settings/assistant", { assistantId });
      setActiveId(null);
      setDetail(null);
      setDetailError(null);
      if (routeId) {
        navigate("/", { replace: true });
      }
      setRefreshToken((token) => token + 1);
    },
    [navigate, routeId],
  );

  return (
    <SidebarProvider defaultOpen className="h-svh overflow-hidden">
      <ConversationSidebar
        conversations={conversations}
        activeId={activeId}
        loading={loading}
        error={error}
        hasMore={hasMore}
        loadMore={loadMore}
        assistants={settings?.assistants ?? []}
        assistantTags={settings?.assistantTags ?? []}
        currentAssistantId={currentAssistantId}
        onSelect={handleSelect}
        onAssistantChange={handleAssistantChange}
      />
      <SidebarInset className="flex min-h-svh flex-col overflow-hidden">
        <div className="flex items-center gap-2 border-b px-4 py-3">
          <SidebarTrigger />
          <div className="text-sm text-muted-foreground">
            {activeConversation ? activeConversation.title : "请选择会话"}
          </div>
        </div>
        <Conversation className="flex-1 min-h-0">
          <ConversationContent className="mx-auto w-full max-w-3xl gap-4 px-4 py-6">
            {!activeId && (
              <ConversationEmptyState
                icon={<MessageSquare className="size-10" />}
                title="请选择会话"
                description="选择左侧会话以查看消息"
              />
            )}
            {activeId && detailLoading && (
              <ConversationEmptyState
                title="加载中..."
                description="正在加载会话详情"
              />
            )}
            {activeId && detailError && (
              <ConversationEmptyState
                title="加载失败"
                description={detailError}
              />
            )}
            {!detailLoading &&
              !detailError &&
              activeId &&
              selectedMessages.length === 0 && (
                <ConversationEmptyState
                  icon={<MessageSquare className="size-10" />}
                  title="暂无消息"
                  description="当前会话还没有消息"
                />
              )}
            {!detailLoading &&
              !detailError &&
              activeId &&
              selectedMessages.map((message) => {
                const isUser = message.role === "USER";
                return (
                  <div
                    key={message.id}
                    className={`flex ${isUser ? "justify-end" : "justify-start"}`}
                  >
                    <div
                      className={`flex flex-col gap-2 text-sm ${
                        isUser
                          ? "max-w-[85%] rounded-2xl bg-muted px-4 py-3"
                          : "w-full"
                      }`}
                    >
                      {message.parts.map((part, index) => (
                        <MessagePart key={index} part={part} />
                      ))}
                    </div>
                  </div>
                );
              })}
          </ConversationContent>
          <ConversationScrollButton />
        </Conversation>
        <ChatInput />
      </SidebarInset>
    </SidebarProvider>
  );
}
