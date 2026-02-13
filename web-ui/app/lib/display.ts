export function getAssistantDisplayName(name: string | null | undefined): string {
  const normalized = name?.trim() ?? "";
  if (normalized.length > 0) {
    return normalized;
  }

  return "默认助手";
}

export function getModelDisplayName(
  displayName: string | null | undefined,
  modelId: string | null | undefined,
): string {
  const normalizedDisplayName = displayName?.trim() ?? "";
  if (normalizedDisplayName.length > 0) {
    return normalizedDisplayName;
  }

  const normalizedModelId = modelId?.trim() ?? "";
  if (normalizedModelId.length > 0) {
    return normalizedModelId;
  }

  return "未命名模型";
}
