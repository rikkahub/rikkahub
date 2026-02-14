export function safeStringArray(source: unknown): string[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is string => typeof item === "string" && item.length > 0);
}
