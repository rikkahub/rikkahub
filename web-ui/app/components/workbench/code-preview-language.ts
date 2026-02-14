const CODE_PREVIEW_LANGUAGE_ALIASES: Record<string, string> = {
  html: "html",
  htm: "html",
  xml: "html",
  svg: "svg",
  md: "markdown",
  markdown: "markdown",
  mermaid: "mermaid",
  mmd: "mermaid",
};

const SUPPORTED_CODE_PREVIEW_LANGUAGES = new Set(Object.keys(CODE_PREVIEW_LANGUAGE_ALIASES));

export function getCodePreviewLanguage(language: string): string | null {
  const normalized = language.trim().toLowerCase();
  if (!normalized || !SUPPORTED_CODE_PREVIEW_LANGUAGES.has(normalized)) {
    return null;
  }

  return CODE_PREVIEW_LANGUAGE_ALIASES[normalized] ?? null;
}
