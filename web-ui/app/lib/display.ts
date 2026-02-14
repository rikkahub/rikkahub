export function getDisplayName(value: string | null | undefined, fallback: string): string {
  const normalized = value?.trim() ?? "";
  return normalized.length > 0 ? normalized : fallback;
}

export function getAssistantDisplayName(name: string | null | undefined): string {
  return getDisplayName(name, "默认助手");
}

export function getModelDisplayName(
  displayName: string | null | undefined,
  modelId: string | null | undefined,
): string {
  const normalizedDisplayName = displayName?.trim() ?? "";
  if (normalizedDisplayName.length > 0) {
    return normalizedDisplayName;
  }

  return getDisplayName(modelId, "未命名模型");
}
