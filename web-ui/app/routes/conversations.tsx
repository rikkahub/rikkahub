import * as React from "react";

import { useNavigate, useParams } from "react-router";

import { ConversationSidebar } from "~/components/conversation-sidebar";
import {
  Conversation,
  ConversationContent,
  ConversationEmptyState,
  ConversationScrollButton,
} from "~/components/extended/conversation";
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "~/components/ui/sidebar";
import { ChatMessage } from "~/components/message/chat-message";
import api from "~/services/api";
import {
  type ConversationDto,
  type ConversationNodeUpdateEventDto,
  type ConversationSnapshotEventDto,
  getCurrentMessageDto,
} from "~/types";
import { MessageSquare } from "lucide-react";
import { ChatInput } from "~/components/message/chat-input";
import { useSettingsStore } from "~/stores/settings";
import {
  toConversationSummaryUpdate,
  useConversationList,
} from "~/hooks/use-conversation-list";
import { sse } from "~/services/api";

type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto;

function applyNodeUpdate(
  conversation: ConversationDto,
  event: ConversationNodeUpdateEventDto,
): ConversationDto {
  if (conversation.id !== event.conversationId) {
    return conversation;
  }

  const nextNodes = [...conversation.messages];
  const indexById = nextNodes.findIndex((node) => node.id === event.nodeId);
  const targetIndex = indexById >= 0 ? indexById : event.nodeIndex;

  if (targetIndex < 0) {
    return conversation;
  }

  if (targetIndex < nextNodes.length) {
    nextNodes[targetIndex] = event.node;
  } else if (targetIndex === nextNodes.length) {
    nextNodes.push(event.node);
  } else {
    return conversation;
  }

  return {
    ...conversation,
    messages: nextNodes,
    updateAt: event.updateAt,
    isGenerating: event.isGenerating,
  };
}

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
  const {
    conversations,
    activeId,
    setActiveId,
    loading,
    error,
    hasMore,
    loadMore,
    refreshList,
    updateConversationSummary,
  } = useConversationList({ currentAssistantId, routeId });
  const [detail, setDetail] = React.useState<ConversationDto | null>(null);
  const [detailLoading, setDetailLoading] = React.useState(false);
  const [detailError, setDetailError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!activeId) {
      setDetail(null);
      setDetailError(null);
      setDetailLoading(false);
      return;
    }

    let active = true;

    setDetailLoading(true);
    setDetailError(null);

    const abortController = new AbortController();

    api
      .get<ConversationDto>(`conversations/${activeId}`)
      .then((data) => {
        if (!active) return;
        setDetail(data);
        updateConversationSummary(toConversationSummaryUpdate(data));
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

    void sse<ConversationStreamEvent>(
      `conversations/${activeId}/stream`,
      {
        onMessage: ({ event, data }) => {
          if (!active) return;

          if (event === "snapshot" && data.type === "snapshot") {
            setDetail(data.conversation);
            updateConversationSummary(toConversationSummaryUpdate(data.conversation));
            setDetailError(null);
            setDetailLoading(false);
            return;
          }

          if (event !== "node_update" || data.type !== "node_update") return;

          setDetail((prev) => {
            if (!prev) return prev;
            const next = applyNodeUpdate(prev, data);
            if (next === prev) return prev;
            updateConversationSummary(toConversationSummaryUpdate(next));
            return next;
          });
          setDetailError(null);
          setDetailLoading(false);
        },
        onError: (streamError) => {
          if (!active) return;
          console.error("Conversation detail SSE error:", streamError);
        },
      },
      { signal: abortController.signal },
    );

    return () => {
      active = false;
      abortController.abort();
    };
  }, [activeId, updateConversationSummary]);

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
      refreshList();
    },
    [navigate, refreshList, routeId],
  );

  const handleToolApproval = React.useCallback(
    async (toolCallId: string, approved: boolean, reason: string) => {
      if (!activeId) return;
      await api.post<{ status: string }>(`conversations/${activeId}/tool-approval`, {
        toolCallId,
        approved,
        reason,
      });
    },
    [activeId],
  );

  const handleRegenerate = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      await api.post<{ status: string }>(`conversations/${activeId}/regenerate`, {
        messageId,
      });
    },
    [activeId],
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
              selectedMessages.map((message, index) => (
                <ChatMessage
                  key={message.id}
                  message={message}
                  loading={(detail?.isGenerating ?? false) && index === selectedMessages.length - 1}
                  isLastMessage={index === selectedMessages.length - 1}
                  onRegenerate={handleRegenerate}
                  onToolApproval={handleToolApproval}
                />
              ))}
          </ConversationContent>
          <ConversationScrollButton />
        </Conversation>
        <ChatInput />
      </SidebarInset>
    </SidebarProvider>
  );
}
