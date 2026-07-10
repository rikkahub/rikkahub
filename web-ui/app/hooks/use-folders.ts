import * as React from "react";

import api from "~/services/api";
import { EVENT_FOLDERS, subscribeToEvent } from "~/services/events";
import type { FolderDto, FolderListEventDto } from "~/types";

export interface UseFoldersResult {
  folders: FolderDto[];
  selectedFolderId: string | null;
  setSelectedFolderId: React.Dispatch<React.SetStateAction<string | null>>;
  loading: boolean;
  refreshFolders: () => Promise<void>;
  createFolder: (name: string) => Promise<FolderDto>;
  renameFolder: (id: string, name: string) => Promise<void>;
  deleteFolder: (id: string) => Promise<void>;
  moveConversationToFolder: (conversationId: string, folderId: string | null) => Promise<void>;
}

function reconcileSelectedFolderId(
  selectedFolderId: string | null,
  folders: FolderDto[],
): string | null {
  if (selectedFolderId === null) return null;
  return folders.some((folder) => folder.id === selectedFolderId) ? selectedFolderId : null;
}

/**
 * Folder management for the current assistant. Folders are an assistant-scoped
 * grouping; switching assistant resets both the list and the selected folder.
 *
 * @see app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawerVM.kt
 */
export function useFolders(currentAssistantId: string | null): UseFoldersResult {
  const [folders, setFolders] = React.useState<FolderDto[]>([]);
  const [selectedFolderId, setSelectedFolderId] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);
  const requestEpochRef = React.useRef(0);
  const currentAssistantIdRef = React.useRef<string | null>(currentAssistantId);

  React.useEffect(() => {
    currentAssistantIdRef.current = currentAssistantId;
  }, [currentAssistantId]);

  const refreshFolders = React.useCallback(async () => {
    const epoch = ++requestEpochRef.current;
    setLoading(true);
    try {
      const data = await api.get<FolderDto[]>("folders");
      if (epoch !== requestEpochRef.current) return;
      setFolders(data);
    } finally {
      if (epoch === requestEpochRef.current) {
        setLoading(false);
      }
    }
  }, []);

  // Reset selection and reload folders whenever the assistant changes.
  React.useEffect(() => {
    setSelectedFolderId(null);
    setFolders([]);
    void refreshFolders().catch((folderError) => {
      console.error("Refresh folders failed", folderError);
    });
  }, [currentAssistantId, refreshFolders]);

  // Live folder updates for the current assistant (create/rename/delete from any client).
  React.useEffect(() => {
    return subscribeToEvent<FolderListEventDto>(EVENT_FOLDERS, (data) => {
      if (data.assistantId !== currentAssistantIdRef.current) return;
      setFolders(data.folders);
      setSelectedFolderId((current) => reconcileSelectedFolderId(current, data.folders));
    });
  }, []);

  const createFolder = React.useCallback(
    async (name: string) => {
      const folder = await api.post<FolderDto>("folders", { name });
      await refreshFolders();
      return folder;
    },
    [refreshFolders],
  );

  const renameFolder = React.useCallback(
    async (id: string, name: string) => {
      await api.post<{ status: string }>(`folders/${id}/rename`, { name });
      await refreshFolders();
    },
    [refreshFolders],
  );

  const deleteFolder = React.useCallback(
    async (id: string) => {
      await api.delete<Record<string, never>>(`folders/${id}`, {
        parseJson: (raw) => (raw ? JSON.parse(raw) : {}),
      });
      setSelectedFolderId((current) => (current === id ? null : current));
      await refreshFolders();
    },
    [refreshFolders],
  );

  const moveConversationToFolder = React.useCallback(
    async (conversationId: string, folderId: string | null) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/folder`, {
        folderId,
      });
    },
    [],
  );

  return {
    folders,
    selectedFolderId,
    setSelectedFolderId,
    loading,
    refreshFolders,
    createFolder,
    renameFolder,
    deleteFolder,
    moveConversationToFolder,
  };
}
