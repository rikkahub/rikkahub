import * as React from "react";
import type { ComponentProps, HTMLAttributes } from "react";

import { Check, Copy, Download } from "lucide-react";
import { useTranslation } from "react-i18next";

import { getCodePreviewLanguage } from "~/components/workbench/code-preview-language";
import { Button } from "~/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select";
import { copyTextToClipboard } from "~/lib/clipboard";
import { cn } from "~/lib/utils";

import { highlightToLines, resolveHighlightLanguage } from "./highlight";

const MAX_HIGHLIGHT_CODE_LENGTH = 12000;

type CodeBlockProps = HTMLAttributes<HTMLDivElement> & {
  code: string;
  language: string;
  onPreview?: () => void;
  showLineNumbers?: boolean;
  wrapLines?: boolean;
};

interface CodeBlockContextType {
  code: string;
  language: string;
}

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

const CodeBlockBody = React.memo(
  ({
    lines,
    showLineNumbers,
    wrapLines,
  }: {
    lines: string[];
    showLineNumbers: boolean;
    wrapLines: boolean;
  }) => (
    <pre className={cn("m-0 p-3 text-sm", wrapLines ? "whitespace-pre-wrap" : "whitespace-pre")}>
      <code
        className={cn(
          "font-mono leading-relaxed",
          showLineNumbers && "[counter-increment:line_0] [counter-reset:line]",
        )}
      >
        {lines.map((line, index) => (
          <span
            // 行内容只来自 highlight.js 的输出或转义后的原文
            dangerouslySetInnerHTML={{ __html: line || "\n" }}
            className={showLineNumbers ? LINE_NUMBER_CLASSES : "block"}
            key={`line-${index}`}
          />
        ))}
      </code>
    </pre>
  ),
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
  wrapLines = false,
}: {
  code: string;
  language: string | null;
  showLineNumbers?: boolean;
  wrapLines?: boolean;
}) {
  const lines = React.useMemo(
    () => highlightToLines(code, code.length <= MAX_HIGHLIGHT_CODE_LENGTH ? language : null),
    [code, language],
  );

  return (
    <div
      className={cn(
        "code-block-content relative",
        wrapLines ? "overflow-y-auto overflow-x-hidden" : "overflow-auto",
      )}
    >
      <CodeBlockBody lines={lines} showLineNumbers={showLineNumbers} wrapLines={wrapLines} />
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
    if (isCopied) {
      return;
    }

    try {
      await copyTextToClipboard(code);
      setIsCopied(true);
      onCopy?.();
      timeoutRef.current = window.setTimeout(() => {
        setIsCopied(false);
      }, timeout);
    } catch (error) {
      onError?.(error as Error);
    }
  }, [code, isCopied, onCopy, onError, timeout]);

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
  wrapLines = false,
  ...props
}: CodeBlockProps) {
  const displayLanguage = language || "text";
  const previewLanguage = React.useMemo(() => getCodePreviewLanguage(language), [language]);
  const canPreview = Boolean(onPreview && previewLanguage);
  const highlightLanguage = React.useMemo(() => resolveHighlightLanguage(language), [language]);
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
        <CodeBlockContent
          code={code}
          language={highlightLanguage}
          showLineNumbers={showLineNumbers}
          wrapLines={wrapLines}
        />
      </CodeBlockContainer>
    </CodeBlockContext.Provider>
  );
}
