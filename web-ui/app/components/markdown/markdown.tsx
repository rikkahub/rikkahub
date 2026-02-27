import * as React from "react";
import { useTranslation } from "react-i18next";
import { Streamdown } from "streamdown";
import { cjk } from "@streamdown/cjk";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import rehypeRaw from "rehype-raw";
import { cn } from "~/lib/utils";
import { getCodePreviewLanguage } from "~/components/workbench/code-preview-language";
import { useOptionalWorkbench } from "~/components/workbench/workbench-context";
import { useSettingsStore } from "~/stores";
import { CodeBlock } from "./code-block";
import "katex/dist/katex.min.css";
import "./markdown.css";
import "streamdown/styles.css";

// Regex patterns for preprocessing
const INLINE_LATEX_REGEX = /\\\((.+?)\\\)/g;
const BLOCK_LATEX_REGEX = /\\\[(.+?)\\\]/gs;
const CODE_BLOCK_REGEX = /```[\s\S]*?```|`[^`\n]*`/g;

// Preprocess markdown content
function preProcess(content: string): string {
  // Find all code block positions
  const codeBlocks: { start: number; end: number }[] = [];
  let match;
  const codeBlockRegex = new RegExp(CODE_BLOCK_REGEX.source, "g");
  while ((match = codeBlockRegex.exec(content)) !== null) {
    codeBlocks.push({ start: match.index, end: match.index + match[0].length });
  }

  // Check if position is inside a code block
  const isInCodeBlock = (position: number): boolean => {
    return codeBlocks.some((range) => position >= range.start && position < range.end);
  };

  // Replace inline formulas \( ... \) to $ ... $, skip code blocks
  let result = content.replace(
    new RegExp(INLINE_LATEX_REGEX.source, "g"),
    (match, group1, offset) => {
      if (isInCodeBlock(offset)) {
        return match;
      }
      return `$${group1}$`;
    },
  );

  // Replace block formulas \[ ... \] to $$ ... $$, skip code blocks
  result = result.replace(new RegExp(BLOCK_LATEX_REGEX.source, "gs"), (match, group1, offset) => {
    if (isInCodeBlock(offset)) {
      return match;
    }
    return `$$${group1}$$`;
  });

  return result;
}

type MarkdownProps = {
  content: string;
  className?: string;
  onClickCitation?: (id: string) => void;
  allowCodePreview?: boolean;
  isAnimating?: boolean;
};

function getNodeText(node: React.ReactNode): string {
  if (node == null || typeof node === "boolean") return "";
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(getNodeText).join("");
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) {
    return getNodeText(node.props.children);
  }
  return "";
}

export default function Markdown({
  content,
  className,
  onClickCitation,
  allowCodePreview = true,
  isAnimating = false,
}: MarkdownProps) {
  const { t } = useTranslation("markdown");
  const workbench = useOptionalWorkbench();
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);
  const processedContent = React.useMemo(() => preProcess(content), [content]);
  const handlePreviewCode = React.useCallback(
    (language: string, code: string) => {
      if (!allowCodePreview || !workbench) return;

      const previewLanguage = getCodePreviewLanguage(language);
      if (!previewLanguage) return;

      workbench.openPanel({
        type: "code-preview",
        title: t("markdown.code_preview_title", {
          language: previewLanguage.toUpperCase(),
        }),
        payload: {
          language: previewLanguage,
          code,
        },
      });
    },
    [allowCodePreview, t, workbench],
  );

  return (
    <div className={cn("markdown", className)}>
      <Streamdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex, rehypeRaw]}
        plugins={{ cjk: cjk }}
        animated={{ animation: "fadeIn", sep: 'word', duration: 150 }}
        isAnimating={isAnimating}
        controls={{code: false, mermaid: false}}
        components={{
          pre: ({ children }) => <>{children}</>,
          code: ({ className, children, ...props }) => {
            const match = /language-([A-Za-z0-9_-]+)/.exec(className || "");
            const code = String(children).replace(/\n$/, "");
            const isBlock = code.includes("\n");

            if (match || isBlock) {
              const language = match?.[1] || "";
              return (
                <CodeBlock
                  language={language}
                  code={code}
                  showLineNumbers={displaySetting?.showLineNumbers ?? false}
                  wrapLines={displaySetting?.codeBlockAutoWrap ?? false}
                  onPreview={
                    allowCodePreview && workbench
                      ? () => {
                          handlePreviewCode(language, code);
                        }
                      : undefined
                  }
                />
              );
            }

            return (
              <code className="inline-code" {...props}>
                {children}
              </code>
            );
          },
          a: ({ href, children, ...props }) => {
            const childText = getNodeText(children).trim();

            // Citation format: [citation,domain](id)
            if (childText.startsWith("citation,")) {
              const domain = childText.substring("citation,".length);
              const id = (href || "").trim();

              if (id.length === 6) {
                return (
                  <span
                    className="citation-badge"
                    onClick={() => onClickCitation?.(id)}
                    title={domain}
                  >
                    {domain}
                  </span>
                );
              }

              if (href) {
                return (
                  <a
                    className="citation-badge"
                    href={href}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={domain}
                    {...props}
                  >
                    {domain}
                  </a>
                );
              }
            }

            return (
              <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
                {children}
              </a>
            );
          },
        }}
      >
        {processedContent}
      </Streamdown>
    </div>
  );
}
