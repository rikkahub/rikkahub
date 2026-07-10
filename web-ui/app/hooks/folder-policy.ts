export interface FolderRefreshToken {
  assistantId: string | null;
  epoch: number;
}

export function shouldApplyFolderRefresh(
  request: FolderRefreshToken,
  currentAssistantId: string | null,
  currentEpoch: number,
): boolean {
  return request.assistantId === currentAssistantId && request.epoch === currentEpoch;
}

export function shouldApplyFolderEvent(
  eventAssistantId: string,
  currentAssistantId: string | null,
): boolean {
  return eventAssistantId === currentAssistantId;
}

export function reconcileSelectedFolderId<T extends { id: string }>(
  selectedFolderId: string | null,
  folders: T[],
): string | null {
  if (selectedFolderId === null) return null;
  return folders.some((folder) => folder.id === selectedFolderId) ? selectedFolderId : null;
}
