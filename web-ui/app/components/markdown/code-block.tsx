import * as React from "react";
import type { ComponentProps, CSSProperties, HTMLAttributes } from "react";

import { Check, Copy, Download } from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  bundledLanguages,
  createHighlighter,
  type BundledLanguage,
  type BundledTheme,
  type HighlighterGeneric,
  type ThemedToken,
} from "shiki";

import { getCodePreviewLanguage } from "~/components/workbench/code-preview-language";
import { Button } from "~/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select";
import { cn } from "~/lib/utils";

const MAX_SHIKI_CODE_LENGTH = 12000;
const SHIKI_CACHE_LIMIT = 200;
const SHIKI_THEME_LIGHT = "catppuccin-latte";
const SHIKI_THEME_DARK = "catppuccin-mocha";

interface KeyedToken {
  key: string;
  token: ThemedToken;
}

interface KeyedLine {
  key: string;
  tokens: KeyedToken[];
}

interface TokenizedCode {
  bg: string;
  fg: string;
  tokens: ThemedToken[][];
}

type CodeBlockProps = HTMLAttributes<HTMLDivElement> & {
  code: string;
  language: string;
  onPreview?: () => void;
  showLineNumbers?: boolean;
};

interface CodeBlockContextType {
  code: string;
  language: string;
}

const ITALIC_STYLES = new Set([1, 3, 5, 7]);
const BOLD_STYLES = new Set([2, 3, 6, 7]);
const UNDERLINE_STYLES = new Set([4, 5, 6, 7]);

const CodeBlockContext = React.createContext<CodeBlockContextType>({
  code: "",
  language: "text",
});

const DEFAULT_DOWNLOAD_FILE_NAME = "code.txt";
const CODE_LANGUAGE_EXTENSION_MAP: Record<string, string> = {
  bash: "sh",
  csharp: "cs",
  javascript: "js",
  js: "js",
  jsx: "jsx",
  kotlin: "kt",
  markdown: "md",
  plaintext: "txt",
  python: "py",
  shell: "sh",
  typescript: "ts",
  tsx: "tsx",
};

function toDownloadFileName(language: string): string {
  const normalized = language.trim().toLowerCase();
  if (!normalized) {
    return DEFAULT_DOWNLOAD_FILE_NAME;
  }

  const mappedExtension = CODE_LANGUAGE_EXTENSION_MAP[normalized];
  if (mappedExtension) {
    return `code.${mappedExtension}`;
  }

  const safeExtension = normalized.replace(/[^a-z0-9]+/g, "");
  if (!safeExtension) {
    return DEFAULT_DOWNLOAD_FILE_NAME;
  }

  return `code.${safeExtension}`;
}

const highlighterCache = new Map<
  BundledLanguage,
  Promise<HighlighterGeneric<BundledLanguage, BundledTheme>>
>();
const tokensCache = new Map<string, TokenizedCode>();
const subscribers = new Map<string, Set<(result: TokenizedCode) => void>>();

function resolveShikiLanguage(language: string): BundledLanguage | null {
  const normalized = language.trim().toLowerCase();
  if (!normalized) {
    return null;
  }

  if (!Object.prototype.hasOwnProperty.call(bundledLanguages, normalized)) {
    return null;
  }

  return normalized as BundledLanguage;
}

function getTokensCacheKey(code: string, language: BundledLanguage): string {
  return `${language}\u0000${code}`;
}

function readTokensFromCache(cacheKey: string): TokenizedCode | null {
  const cached = tokensCache.get(cacheKey);
  if (!cached) {
    return null;
  }

  tokensCache.delete(cacheKey);
  tokensCache.set(cacheKey, cached);
  return cached;
}

function writeTokensToCache(cacheKey: string, tokenized: TokenizedCode): void {
  if (tokensCache.size >= SHIKI_CACHE_LIMIT) {
    const oldest = tokensCache.keys().next().value;
    if (typeof oldest === "string") {
      tokensCache.delete(oldest);
    }
  }

  tokensCache.set(cacheKey, tokenized);
}

function getHighlighter(
  language: BundledLanguage,
): Promise<HighlighterGeneric<BundledLanguage, BundledTheme>> {
  const cached = highlighterCache.get(language);
  if (cached) {
    return cached;
  }

  const highlighterPromise = createHighlighter({
    langs: [language],
    themes: [SHIKI_THEME_LIGHT, SHIKI_THEME_DARK],
  });
  highlighterCache.set(language, highlighterPromise);
  return highlighterPromise;
}

function createRawTokens(code: string): TokenizedCode {
  return {
    bg: "transparent",
    fg: "inherit",
    tokens: code.split("\n").map((line) =>
      line === ""
        ? []
        : [
            {
              color: "inherit",
              content: line,
            } as ThemedToken,
          ],
    ),
  };
}

function addKeysToTokens(lines: ThemedToken[][]): KeyedLine[] {
  return lines.map((line, lineIndex) => ({
    key: `line-${lineIndex}`,
    tokens: line.map((token, tokenIndex) => ({
      key: `line-${lineIndex}-${tokenIndex}`,
      token,
    })),
  }));
}

function isItalic(fontStyle: number | undefined): boolean {
  return ITALIC_STYLES.has(fontStyle ?? 0);
}

function isBold(fontStyle: number | undefined): boolean {
  return BOLD_STYLES.has(fontStyle ?? 0);
}

function isUnderline(fontStyle: number | undefined): boolean {
  return UNDERLINE_STYLES.has(fontStyle ?? 0);
}

export function highlightCode(
  code: string,
  language: BundledLanguage,
  callback?: (result: TokenizedCode) => void,
): TokenizedCode | null {
  const tokensCacheKey = getTokensCacheKey(code, language);
  const cached = readTokensFromCache(tokensCacheKey);
  if (cached) {
    return cached;
  }

  if (callback) {
    if (!subscribers.has(tokensCacheKey)) {
      subscribers.set(tokensCacheKey, new Set());
    }
    subscribers.get(tokensCacheKey)?.add(callback);
  }

  void getHighlighter(language)
    .then((highlighter) => {
      const tokenResult = highlighter.codeToTokens(code, {
        lang: language,
        themes: {
          light: SHIKI_THEME_LIGHT,
          dark: SHIKI_THEME_DARK,
        },
      });

      const tokenized: TokenizedCode = {
        bg: tokenResult.bg ?? "transparent",
        fg: tokenResult.fg ?? "inherit",
        tokens: tokenResult.tokens,
      };

      writeTokensToCache(tokensCacheKey, tokenized);
      const subs = subscribers.get(tokensCacheKey);
      if (subs) {
        for (const sub of subs) {
          sub(tokenized);
        }
        subscribers.delete(tokensCacheKey);
      }
    })
    .catch((e) => {
      const fallback = createRawTokens(code);
      writeTokensToCache(tokensCacheKey, fallback);
      const subs = subscribers.get(tokensCacheKey);
      if (subs) {
        for (const sub of subs) {
          sub(fallback);
        }
        subscribers.delete(tokensCacheKey);
      }
    });

  return null;
}

const LINE_NUMBER_CLASSES = cn(
  "block",
  "before:mr-4",
  "before:inline-block",
  "before:w-8",
  "before:text-right",
  "before:font-mono",
  "before:text-muted-foreground/50",
  "before:select-none",
  "before:content-[counter(line)]",
  "before:[counter-increment:line]",
);

function TokenSpan({ token }: { token: ThemedToken }) {
  return (
    <span
      className="dark:!bg-[var(--shiki-dark-bg)] dark:!text-[var(--shiki-dark)]"
      style={
        {
          backgroundColor: token.bgColor,
          color: token.color,
          fontStyle: isItalic(token.fontStyle) ? "italic" : undefined,
          fontWeight: isBold(token.fontStyle) ? "bold" : undefined,
          textDecoration: isUnderline(token.fontStyle) ? "underline" : undefined,
          ...token.htmlStyle,
        } as CSSProperties
      }
    >
      {token.content}
    </span>
  );
}

function LineSpan({
  keyedLine,
  showLineNumbers,
}: {
  keyedLine: KeyedLine;
  showLineNumbers: boolean;
}) {
  return (
    <span className={showLineNumbers ? LINE_NUMBER_CLASSES : "block"}>
      {keyedLine.tokens.length === 0
        ? "\n"
        : keyedLine.tokens.map(({ key, token }) => <TokenSpan key={key} token={token} />)}
    </span>
  );
}

const CodeBlockBody = React.memo(
  ({
    className,
    showLineNumbers,
    tokenized,
  }: {
    className?: string;
    showLineNumbers: boolean;
    tokenized: TokenizedCode;
  }) => {
    const preStyle = React.useMemo(
      () => ({
        backgroundColor: tokenized.bg,
        color: tokenized.fg,
      }),
      [tokenized.bg, tokenized.fg],
    );

    const keyedLines = React.useMemo(() => addKeysToTokens(tokenized.tokens), [tokenized.tokens]);

    return (
      <pre className={cn("m-0 p-3 text-sm", className)} style={preStyle}>
        <code
          className={cn(
            "font-mono leading-relaxed",
            showLineNumbers && "[counter-increment:line_0] [counter-reset:line]",
          )}
        >
          {keyedLines.map((keyedLine) => (
            <LineSpan key={keyedLine.key} keyedLine={keyedLine} showLineNumbers={showLineNumbers} />
          ))}
        </code>
      </pre>
    );
  },
  (prevProps, nextProps) =>
    prevProps.className === nextProps.className &&
    prevProps.showLineNumbers === nextProps.showLineNumbers &&
    prevProps.tokenized === nextProps.tokenized,
);

CodeBlockBody.displayName = "CodeBlockBody";

export function CodeBlockContainer({
  className,
  language,
  style,
  ...props
}: HTMLAttributes<HTMLDivElement> & { language: string }) {
  return (
    <div
      className={cn(
        "code-block group relative w-full overflow-hidden rounded-lg border border-border",
        className,
      )}
      data-language={language}
      style={style}
      {...props}
    />
  );
}

export function CodeBlockHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("code-block-header", className)} {...props} />;
}

export function CodeBlockTitle({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex items-center gap-2", className)} {...props} />;
}

export function CodeBlockLanguage({ className, ...props }: HTMLAttributes<HTMLSpanElement>) {
  return <span className={cn("code-block-language", className)} {...props} />;
}

export function CodeBlockActions({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("code-block-actions", className)} {...props} />;
}

export function CodeBlockContent({
  code,
  language,
  showLineNumbers = false,
}: {
  code: string;
  language: BundledLanguage | null;
  showLineNumbers?: boolean;
}) {
  const rawTokens = React.useMemo(() => createRawTokens(code), [code]);
  const shouldHighlight = Boolean(language) && code.length <= MAX_SHIKI_CODE_LENGTH;

  const [tokenized, setTokenized] = React.useState<TokenizedCode>(() => {
    if (!shouldHighlight || !language) {
      return rawTokens;
    }

    return highlightCode(code, language) ?? rawTokens;
  });

  React.useEffect(() => {
    if (!shouldHighlight || !language) {
      setTokenized(rawTokens);
      return;
    }

    let cancelled = false;
    const tokensCacheKey = getTokensCacheKey(code, language);
    const onHighlighted = (result: TokenizedCode) => {
      if (!cancelled) {
        setTokenized(result);
      }
    };

    const nextTokenized = highlightCode(code, language, onHighlighted) ?? rawTokens;
    setTokenized(nextTokenized);

    return () => {
      cancelled = true;
      const subs = subscribers.get(tokensCacheKey);
      subs?.delete(onHighlighted);
      if (subs && subs.size === 0) {
        subscribers.delete(tokensCacheKey);
      }
    };
  }, [code, language, rawTokens, shouldHighlight]);

  return (
    <div className="code-block-content relative overflow-auto">
      <CodeBlockBody
        className="dark:!bg-[var(--shiki-dark-bg)] dark:!text-[var(--shiki-dark)]"
        showLineNumbers={showLineNumbers}
        tokenized={tokenized}
      />
    </div>
  );
}

export type CodeBlockCopyButtonProps = ComponentProps<typeof Button> & {
  onCopy?: () => void;
  onError?: (error: Error) => void;
  timeout?: number;
};

export function CodeBlockCopyButton({
  children,
  className,
  onCopy,
  onError,
  timeout = 2000,
  ...props
}: CodeBlockCopyButtonProps) {
  const { t } = useTranslation("markdown");
  const [isCopied, setIsCopied] = React.useState(false);
  const timeoutRef = React.useRef<number>(0);
  const { code } = React.useContext(CodeBlockContext);

  const copyToClipboard = React.useCallback(async () => {
    if (typeof window === "undefined" || !navigator?.clipboard?.writeText) {
      onError?.(new Error(t("code_block.clipboard_not_available")));
      return;
    }

    if (isCopied) {
      return;
    }

    try {
      await navigator.clipboard.writeText(code);
      setIsCopied(true);
      onCopy?.();
      timeoutRef.current = window.setTimeout(() => {
        setIsCopied(false);
      }, timeout);
    } catch (error) {
      onError?.(error as Error);
    }
  }, [code, isCopied, onCopy, onError, t, timeout]);

  React.useEffect(
    () => () => {
      window.clearTimeout(timeoutRef.current);
    },
    [],
  );

  return (
    <Button
      aria-label={t("code_block.copy_code")}
      className={cn("code-block-copy h-6 px-1.5", className)}
      onClick={copyToClipboard}
      size="xs"
      type="button"
      variant="ghost"
      {...props}
    >
      {children ??
        (isCopied ? (
          <>
            <Check className="size-3" />
            <span>{t("code_block.copied")}</span>
          </>
        ) : (
          <>
            <Copy className="size-3" />
            <span>{t("code_block.copy")}</span>
          </>
        ))}
    </Button>
  );
}

export type CodeBlockPreviewButtonProps = Omit<ComponentProps<typeof Button>, "onClick"> & {
  onPreview: () => void;
};

export function CodeBlockPreviewButton({
  children,
  className,
  onPreview,
  ...props
}: CodeBlockPreviewButtonProps) {
  const { t } = useTranslation("markdown");
  return (
    <Button
      aria-label={t("code_block.preview_code")}
      className={cn("code-block-copy h-6 px-1.5", className)}
      onClick={onPreview}
      size="xs"
      type="button"
      variant="ghost"
      {...props}
    >
      {children ?? <span>{t("code_block.preview")}</span>}
    </Button>
  );
}

export type CodeBlockDownloadButtonProps = ComponentProps<typeof Button> & {
  onDownload?: () => void;
  onError?: (error: Error) => void;
};

export function CodeBlockDownloadButton({
  children,
  className,
  onDownload,
  onError,
  ...props
}: CodeBlockDownloadButtonProps) {
  const { t } = useTranslation("markdown");
  const { code, language } = React.useContext(CodeBlockContext);

  const handleDownload = React.useCallback(() => {
    if (typeof window === "undefined") {
      onError?.(new Error(t("code_block.window_not_available")));
      return;
    }

    try {
      const blob = new Blob([code], { type: "text/plain;charset=utf-8" });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = toDownloadFileName(language);
      document.body.append(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
      onDownload?.();
    } catch (error) {
      onError?.(error as Error);
    }
  }, [code, language, onDownload, onError, t]);

  return (
    <Button
      aria-label={t("code_block.download_code")}
      className={cn("code-block-copy h-6 px-1.5", className)}
      onClick={handleDownload}
      size="xs"
      type="button"
      variant="ghost"
      {...props}
    >
      {children ?? (
        <>
          <Download className="size-3" />
          <span>{t("code_block.download")}</span>
        </>
      )}
    </Button>
  );
}

export type CodeBlockLanguageSelectorProps = ComponentProps<typeof Select>;

export function CodeBlockLanguageSelector(props: CodeBlockLanguageSelectorProps) {
  return <Select {...props} />;
}

export type CodeBlockLanguageSelectorTriggerProps = ComponentProps<typeof SelectTrigger>;

export function CodeBlockLanguageSelectorTrigger({
  className,
  ...props
}: CodeBlockLanguageSelectorTriggerProps) {
  return (
    <SelectTrigger
      className={cn("h-7 border-none bg-transparent px-2 text-xs shadow-none", className)}
      size="sm"
      {...props}
    />
  );
}

export type CodeBlockLanguageSelectorValueProps = ComponentProps<typeof SelectValue>;

export function CodeBlockLanguageSelectorValue(props: CodeBlockLanguageSelectorValueProps) {
  return <SelectValue {...props} />;
}

export type CodeBlockLanguageSelectorContentProps = ComponentProps<typeof SelectContent>;

export function CodeBlockLanguageSelectorContent({
  align = "end",
  ...props
}: CodeBlockLanguageSelectorContentProps) {
  return <SelectContent align={align} {...props} />;
}

export type CodeBlockLanguageSelectorItemProps = ComponentProps<typeof SelectItem>;

export function CodeBlockLanguageSelectorItem(props: CodeBlockLanguageSelectorItemProps) {
  return <SelectItem {...props} />;
}

export function CodeBlock({
  className,
  code,
  language,
  onPreview,
  showLineNumbers = false,
  ...props
}: CodeBlockProps) {
  const displayLanguage = language || "text";
  const previewLanguage = React.useMemo(() => getCodePreviewLanguage(language), [language]);
  const canPreview = Boolean(onPreview && previewLanguage);
  const shikiLanguage = React.useMemo(() => resolveShikiLanguage(language), [language]);
  const contextValue = React.useMemo(
    () => ({ code, language: displayLanguage }),
    [code, displayLanguage],
  );

  return (
    <CodeBlockContext.Provider value={contextValue}>
      <CodeBlockContainer className={className} language={displayLanguage} {...props}>
        <CodeBlockHeader>
          <CodeBlockTitle>
            <CodeBlockLanguage>{displayLanguage}</CodeBlockLanguage>
          </CodeBlockTitle>
          <CodeBlockActions>
            {canPreview && onPreview && <CodeBlockPreviewButton onPreview={onPreview} />}
            <CodeBlockDownloadButton />
            <CodeBlockCopyButton />
          </CodeBlockActions>
        </CodeBlockHeader>
        <CodeBlockContent code={code} language={shikiLanguage} showLineNumbers={showLineNumbers} />
      </CodeBlockContainer>
    </CodeBlockContext.Provider>
  );
}
