import * as React from "react";

import { useNavigate, useParams } from "react-router";

import { ConversationSidebar } from "~/components/conversation-sidebar";
import {
  Conversation,
  ConversationContent,
  ConversationEmptyState,
  ConversationScrollButton,
} from "~/components/extended/conversation";
import { ChatInput } from "~/components/message/chat-input";
import { ChatMessage } from "~/components/message/chat-message";
import { Spinner } from "~/components/ui/spinner";
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "~/components/ui/sidebar";
import {
  toConversationSummaryUpdate,
  useConversationList,
} from "~/hooks/use-conversation-list";
import api, { sse } from "~/services/api";
import { useChatInputStore, useSettingsStore } from "~/stores";
import {
  type ConversationDto,
  type ConversationNodeUpdateEventDto,
  type ConversationSnapshotEventDto,
  type UIMessagePart,
  getCurrentMessageDto,
} from "~/types";
import { MessageSquare } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto;

type SelectedMessage = ReturnType<typeof getCurrentMessageDto>;
type ConversationSummaryUpdater = (update: ReturnType<typeof toConversationSummaryUpdate>) => void;

function createHomeDraftId() {
  return `home-${uuidv4()}`;
}

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

function useConversationDetail(activeId: string | null, updateSummary: ConversationSummaryUpdater) {
  const [detail, setDetail] = React.useState<ConversationDto | null>(null);
  const [detailLoading, setDetailLoading] = React.useState(false);
  const [detailError, setDetailError] = React.useState<string | null>(null);

  const resetDetail = React.useCallback(() => {
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
  }, []);

  React.useEffect(() => {
    if (!activeId) {
      resetDetail();
      return;
    }

    let mounted = true;
    setDetailLoading(true);
    setDetailError(null);

    const abortController = new AbortController();

    api
      .get<ConversationDto>(`conversations/${activeId}`)
      .then((data) => {
        if (!mounted) return;
        setDetail(data);
        updateSummary(toConversationSummaryUpdate(data));
      })
      .catch((err: Error) => {
        if (!mounted) return;
        setDetailError(err.message || "加载会话详情失败");
        setDetail(null);
      })
      .finally(() => {
        if (!mounted) return;
        setDetailLoading(false);
      });

    void sse<ConversationStreamEvent>(
      `conversations/${activeId}/stream`,
      {
        onMessage: ({ event, data }) => {
          if (!mounted) return;

          if (event === "snapshot" && data.type === "snapshot") {
            setDetail(data.conversation);
            updateSummary(toConversationSummaryUpdate(data.conversation));
            setDetailError(null);
            setDetailLoading(false);
            return;
          }

          if (event !== "node_update" || data.type !== "node_update") return;

          setDetail((prev) => {
            if (!prev) return prev;
            const next = applyNodeUpdate(prev, data);
            if (next === prev) return prev;
            updateSummary(toConversationSummaryUpdate(next));
            return next;
          });
          setDetailError(null);
          setDetailLoading(false);
        },
        onError: (streamError) => {
          if (!mounted) return;
          console.error("Conversation detail SSE error:", streamError);
        },
      },
      { signal: abortController.signal },
    );

    return () => {
      mounted = false;
      abortController.abort();
    };
  }, [activeId, resetDetail, updateSummary]);

  const selectedMessages = React.useMemo<SelectedMessage[]>(() => {
    if (!detail) return [];
    return detail.messages.map(getCurrentMessageDto);
  }, [detail]);

  return {
    detail,
    detailLoading,
    detailError,
    selectedMessages,
    resetDetail,
  };
}

function useDraftInputController({
  activeId,
  isHomeRoute,
  homeDraftId,
  setHomeDraftId,
  navigate,
  refreshList,
}: {
  activeId: string | null;
  isHomeRoute: boolean;
  homeDraftId: string;
  setHomeDraftId: React.Dispatch<React.SetStateAction<string>>;
  navigate: ReturnType<typeof useNavigate>;
  refreshList: () => void;
}) {
  const draftKey = activeId ?? (isHomeRoute ? homeDraftId : null);
  const draft = useChatInputStore(
    React.useCallback(
      (state) => (draftKey ? state.drafts[draftKey] : undefined),
      [draftKey],
    ),
  );

  const setDraftText = useChatInputStore((state) => state.setText);
  const addDraftParts = useChatInputStore((state) => state.addParts);
  const removeDraftPart = useChatInputStore((state) => state.removePartAt);
  const getSubmitParts = useChatInputStore((state) => state.getSubmitParts);
  const clearDraft = useChatInputStore((state) => state.clearDraft);

  const inputText = draft?.text ?? "";
  const inputAttachments = draft?.parts ?? [];

  const handleInputTextChange = React.useCallback(
    (text: string) => {
      if (!draftKey) return;
      setDraftText(draftKey, text);
    },
    [draftKey, setDraftText],
  );

  const handleAddInputParts = React.useCallback(
    (parts: UIMessagePart[]) => {
      if (!draftKey || parts.length === 0) return;
      addDraftParts(draftKey, parts);
    },
    [addDraftParts, draftKey],
  );

  const handleRemoveInputPart = React.useCallback(
    (index: number) => {
      if (!draftKey) return;
      removeDraftPart(draftKey, index);
    },
    [draftKey, removeDraftPart],
  );

  const handleSubmit = React.useCallback(async () => {
    if (!draftKey) return;

    const parts = getSubmitParts(draftKey);
    if (parts.length === 0) return;

    if (activeId) {
      await api.post<{ status: string }>(`conversations/${activeId}/messages`, { parts });
      clearDraft(draftKey);
      return;
    }

    const conversationId = uuidv4();
    navigate(`/c/${conversationId}`);
    setHomeDraftId(createHomeDraftId());

    await api.post<{ status: string }>(`conversations/${conversationId}/messages`, { parts });
    clearDraft(draftKey);
    refreshList();
  }, [activeId, clearDraft, draftKey, getSubmitParts, navigate, refreshList, setHomeDraftId]);

  return {
    draftKey,
    inputText,
    inputAttachments,
    handleInputTextChange,
    handleAddInputParts,
    handleRemoveInputPart,
    handleSubmit,
  };
}

function ConversationTimeline({
  activeId,
  isHomeRoute,
  detailLoading,
  detailError,
  selectedMessages,
  isGenerating,
  onRegenerate,
  onToolApproval,
}: {
  activeId: string | null;
  isHomeRoute: boolean;
  detailLoading: boolean;
  detailError: string | null;
  selectedMessages: SelectedMessage[];
  isGenerating: boolean;
  onRegenerate: (messageId: string) => Promise<void>;
  onToolApproval: (toolCallId: string, approved: boolean, reason: string) => Promise<void>;
}) {
  return (
    <Conversation className="flex-1 min-h-0">
      <ConversationContent className="mx-auto w-full max-w-3xl gap-4 px-4 py-6">
        {!activeId && isHomeRoute && (
          <ConversationEmptyState
            icon={<MessageSquare className="size-10" />}
            title="开始新对话"
            description="输入消息后将自动创建会话"
          />
        )}
        {!activeId && !isHomeRoute && (
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
        {!detailLoading && !detailError && activeId && selectedMessages.length === 0 && (
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
              loading={isGenerating && index === selectedMessages.length - 1}
              isLastMessage={index === selectedMessages.length - 1}
              onRegenerate={onRegenerate}
              onToolApproval={onToolApproval}
            />
          ))}
        {!detailLoading && !detailError && activeId && isGenerating && (
          <div className="flex items-center justify-center gap-2 py-2 text-xs text-muted-foreground">
            <Spinner className="size-3.5" />
            <span>正在生成回复...</span>
          </div>
        )}
      </ConversationContent>
      <ConversationScrollButton />
    </Conversation>
  );
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
  const isHomeRoute = !routeId;

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
  } = useConversationList({ currentAssistantId, routeId, autoSelectFirst: !isHomeRoute });

  const [homeDraftId, setHomeDraftId] = React.useState(() => createHomeDraftId());

  const {
    detail,
    detailLoading,
    detailError,
    selectedMessages,
    resetDetail,
  } = useConversationDetail(activeId, updateConversationSummary);

  const {
    draftKey,
    inputText,
    inputAttachments,
    handleInputTextChange,
    handleAddInputParts,
    handleRemoveInputPart,
    handleSubmit,
  } = useDraftInputController({
    activeId,
    isHomeRoute,
    homeDraftId,
    setHomeDraftId,
    navigate,
    refreshList,
  });

  const activeConversation = conversations.find((item) => item.id === activeId);

  const handleSelect = React.useCallback(
    (id: string) => {
      setActiveId(id);
      if (routeId !== id) {
        navigate(`/c/${id}`);
      }
    },
    [navigate, routeId, setActiveId],
  );

  const handleAssistantChange = React.useCallback(
    async (assistantId: string) => {
      await api.post<{ status: string }>("settings/assistant", { assistantId });
      setActiveId(null);
      resetDetail();
      if (routeId) {
        navigate("/", { replace: true });
      }
      refreshList();
    },
    [navigate, refreshList, resetDetail, routeId, setActiveId],
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

  const handleCreateConversation = React.useCallback(() => {
    setActiveId(null);
    resetDetail();
    setHomeDraftId(createHomeDraftId());

    if (routeId) {
      navigate("/");
    }
  }, [navigate, resetDetail, routeId, setActiveId]);

  const handleStop = React.useCallback(async () => {
    if (!activeId) return;
    await api.post<{ status: string }>(`conversations/${activeId}/stop`);
  }, [activeId]);

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
        onCreateConversation={handleCreateConversation}
      />
      <SidebarInset className="flex min-h-svh flex-col overflow-hidden">
        <div className="flex items-center gap-2 border-b px-4 py-3">
          <SidebarTrigger />
          <div className="text-sm text-muted-foreground">
            {activeConversation ? activeConversation.title : "请选择会话"}
          </div>
        </div>

        <ConversationTimeline
          activeId={activeId}
          isHomeRoute={isHomeRoute}
          detailLoading={detailLoading}
          detailError={detailError}
          selectedMessages={selectedMessages}
          isGenerating={detail?.isGenerating ?? false}
          onRegenerate={handleRegenerate}
          onToolApproval={handleToolApproval}
        />

        <ChatInput
          value={inputText}
          attachments={inputAttachments}
          ready={draftKey !== null}
          isGenerating={detail?.isGenerating ?? false}
          disabled={detailLoading || Boolean(detailError)}
          onValueChange={handleInputTextChange}
          onAddParts={handleAddInputParts}
          onRemovePart={(index) => {
            handleRemoveInputPart(index);
          }}
          onSend={handleSubmit}
          onStop={activeId ? handleStop : undefined}
        />
      </SidebarInset>
    </SidebarProvider>
  );
}
