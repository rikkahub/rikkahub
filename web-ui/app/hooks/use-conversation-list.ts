import * as React from "react";

import api from "~/services/api";
import { EVENT_CONVERSATION_LIST_INVALIDATE, subscribeToEvent } from "~/services/events";
import type {
  ConversationDto,
  ConversationListDto,
  ConversationListInvalidateEventDto,
  PagedResult,
} from "~/types";

export interface UseConversationListOptions {
  currentAssistantId: string | null;
  routeId?: string | null;
  autoSelectFirst?: boolean;
  pageSize?: number;
  maxRefreshLimit?: number;
  /**
   * Folder filter: `null` = unfiled (default "chats" view), a folder id = that folder.
   * Passed to the backend as `folderId` ("none" for unfiled).
   */
  folderId?: string | null;
}

// Backend expects "none" for the unfiled view; a folder uuid otherwise.
function toFolderQueryValue(folderId: string | null): string {
  return folderId === null ? "none" : folderId;
}

interface ConversationSummaryUpdate {
  id: string;
  title: string;
  isPinned: boolean;
  createAt: number;
  updateAt: number;
  isGenerating: boolean;
}

export interface UseConversationListResult {
  conversations: ConversationListDto[];
  activeId: string | null;
  setActiveId: React.Dispatch<React.SetStateAction<string | null>>;
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  refreshList: () => void;
  updateConversationSummary: (update: ConversationSummaryUpdate) => void;
}

export function useConversationList({
  currentAssistantId,
  routeId = null,
  autoSelectFirst = true,
  pageSize = 30,
  maxRefreshLimit = 100,
  folderId = null,
}: UseConversationListOptions): UseConversationListResult {
  const [conversations, setConversations] = React.useState<ConversationListDto[]>([]);
  const [activeId, setActiveId] = React.useState<string | null>(routeId ?? null);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [hasMore, setHasMore] = React.useState(false);
  const [refreshToken, setRefreshToken] = React.useState(0);
  const nextOffsetRef = React.useRef<number | null>(0);
  const currentAssistantIdRef = React.useRef<string | null>(currentAssistantId);
  const folderIdRef = React.useRef<string | null>(folderId);
  const conversationsRef = React.useRef<ConversationListDto[]>([]);
  const previousAssistantIdRef = React.useRef<string | null>(null);
  const previousFolderIdRef = React.useRef<string | null>(folderId);
  const refreshTimerRef = React.useRef<number | null>(null);
  const listRequestEpochRef = React.useRef(0);

  const sortConversations = React.useCallback((items: ConversationListDto[]) => {
    return [...items].sort((left, right) => {
      if (left.isPinned !== right.isPinned) {
        return left.isPinned ? -1 : 1;
      }
      return right.updateAt - left.updateAt;
    });
  }, []);

  const mergeConversations = React.useCallback(
    (base: ConversationListDto[], incoming: ConversationListDto[]) => {
      const conversationById = new Map(base.map((item) => [item.id, item]));
      for (const item of incoming) {
        conversationById.set(item.id, item);
      }
      return sortConversations(Array.from(conversationById.values()));
    },
    [sortConversations],
  );

  const refreshConversations = React.useCallback(
    (previous: ConversationListDto[], incoming: ConversationListDto[], replaceCount: number) => {
      const incomingIds = new Set(incoming.map((item) => item.id));
      const tail = previous.slice(replaceCount).filter((item) => !incomingIds.has(item.id));
      return sortConversations([...incoming, ...tail]);
    },
    [sortConversations],
  );

  const refreshList = React.useCallback(() => {
    setRefreshToken((token) => token + 1);
  }, []);

  const scheduleListRefresh = React.useCallback(() => {
    if (refreshTimerRef.current !== null) return;
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null;
      refreshList();
    }, 250);
  }, [refreshList]);

  const updateConversationSummary = React.useCallback(
    (update: ConversationSummaryUpdate) => {
      setConversations((prev) =>
        sortConversations(
          prev.map((item) =>
            item.id === update.id
              ? {
                  ...item,
                  title: update.title,
                  isPinned: update.isPinned,
                  createAt: update.createAt,
                  updateAt: update.updateAt,
                  isGenerating: update.isGenerating,
                }
              : item,
          ),
        ),
      );
    },
    [sortConversations],
  );

  React.useEffect(() => {
    currentAssistantIdRef.current = currentAssistantId;
  }, [currentAssistantId]);

  React.useEffect(() => {
    folderIdRef.current = folderId;
  }, [folderId]);

  React.useEffect(() => {
    conversationsRef.current = conversations;
  }, [conversations]);

  React.useEffect(() => {
    return () => {
      if (refreshTimerRef.current !== null) {
        window.clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    };
  }, []);

  React.useEffect(() => {
    return subscribeToEvent<ConversationListInvalidateEventDto>(
      EVENT_CONVERSATION_LIST_INVALIDATE,
      (data) => {
        if (data.assistantId !== currentAssistantIdRef.current) return;
        scheduleListRefresh();
      },
    );
  }, [scheduleListRefresh]);

  React.useEffect(() => {
    let active = true;
    const assistantChanged = previousAssistantIdRef.current !== currentAssistantId;
    previousAssistantIdRef.current = currentAssistantId;
    const folderChanged = previousFolderIdRef.current !== folderId;
    previousFolderIdRef.current = folderId;
    const scopeChanged = assistantChanged || folderChanged;

    const loadedCount = scopeChanged ? 0 : conversationsRef.current.length;
    const limit = Math.min(Math.max(pageSize, loadedCount), maxRefreshLimit);
    const requestEpoch = ++listRequestEpochRef.current;

    if (scopeChanged || loadedCount === 0) {
      setLoading(true);
      setConversations([]);
      nextOffsetRef.current = 0;
      setHasMore(false);
    }

    setError(null);

    api
      .get<PagedResult<ConversationListDto>>("conversations/paged", {
        searchParams: { offset: 0, limit, folderId: toFolderQueryValue(folderId) },
      })
      .then((data) => {
        if (!active || requestEpoch !== listRequestEpochRef.current) return;

        if (scopeChanged || loadedCount === 0) {
          setConversations(sortConversations(data.items));
        } else {
          setConversations((prev) => refreshConversations(prev, data.items, limit));
        }
        nextOffsetRef.current = data.nextOffset ?? null;
        setHasMore(data.hasMore);

        if (routeId) {
          setActiveId(routeId);
          return;
        }

        setActiveId((current) => {
          if (current && data.items.some((item) => item.id === current)) {
            return current;
          }
          return autoSelectFirst ? (data.items[0]?.id ?? null) : null;
        });
      })
      .catch((err: Error) => {
        if (!active || requestEpoch !== listRequestEpochRef.current) return;
        setError(err.message || "加载会话失败");
      })
      .finally(() => {
        if (!active || requestEpoch !== listRequestEpochRef.current) return;
        setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [
    autoSelectFirst,
    currentAssistantId,
    folderId,
    maxRefreshLimit,
    pageSize,
    refreshConversations,
    refreshToken,
    routeId,
    sortConversations,
  ]);

  const loadMore = React.useCallback(() => {
    const offset = nextOffsetRef.current;
    if (offset === null) return;

    const requestEpoch = listRequestEpochRef.current;

    api
      .get<PagedResult<ConversationListDto>>("conversations/paged", {
        searchParams: {
          offset,
          limit: pageSize,
          folderId: toFolderQueryValue(folderIdRef.current),
        },
      })
      .then((data) => {
        if (requestEpoch !== listRequestEpochRef.current) return;

        setConversations((prev) => mergeConversations(prev, data.items));
        nextOffsetRef.current = data.nextOffset ?? null;
        setHasMore(data.hasMore);
      })
      .catch(() => {
        if (requestEpoch !== listRequestEpochRef.current) return;
        setHasMore(false);
      });
  }, [mergeConversations, pageSize]);

  React.useEffect(() => {
    if (!routeId) return;
    setActiveId(routeId);
  }, [routeId]);

  React.useEffect(() => {
    if (routeId || autoSelectFirst) return;
    setActiveId(null);
  }, [autoSelectFirst, routeId]);

  return {
    conversations,
    activeId,
    setActiveId,
    loading,
    error,
    hasMore,
    loadMore,
    refreshList,
    updateConversationSummary,
  };
}

export function toConversationSummaryUpdate(
  conversation: ConversationDto,
): ConversationSummaryUpdate {
  return {
    id: conversation.id,
    title: conversation.title,
    isPinned: conversation.isPinned,
    createAt: conversation.createAt,
    updateAt: conversation.updateAt,
    isGenerating: conversation.isGenerating,
  };
}
