import * as React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import rehypeRaw from "rehype-raw";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneLight, oneDark } from "react-syntax-highlighter/dist/esm/styles/prism";
import { cn } from "~/lib/utils";
import { Check, Copy } from "lucide-react";
import { getCodePreviewLanguage } from "~/components/workbench/code-preview-language";
import { useOptionalWorkbench } from "~/components/workbench/workbench-context";
import "katex/dist/katex.min.css";
import "./markdown.css";

// Regex patterns for preprocessing
const INLINE_LATEX_REGEX = /\\\((.+?)\\\)/g;
const BLOCK_LATEX_REGEX = /\\\[(.+?)\\\]/gs;
const THINKING_REGEX = /<think>([\s\S]*?)(?:<\/think>|$)/g;
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

  // Replace thinking tags with blockquote format
  result = result.replace(THINKING_REGEX, (_, thinkContent) => {
    return thinkContent
      .split("\n")
      .filter((line: string) => line.trim() !== "")
      .map((line: string) => `>${line}`)
      .join("\n");
  });

  return result;
}

type MarkdownProps = {
  content: string;
  className?: string;
  onClickCitation?: (id: string) => void;
  allowCodePreview?: boolean;
};

function CodeBlock({
  language,
  children,
  onPreview,
}: {
  language: string;
  children: string;
  onPreview?: () => void;
}) {
  const [copied, setCopied] = React.useState(false);
  const isDark =
    typeof window !== "undefined" && document.documentElement.classList.contains("dark");
  const previewLanguage = getCodePreviewLanguage(language);
  const canPreview = Boolean(onPreview && previewLanguage);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(children);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="code-block">
      <div className="code-block-header">
        <span className="code-block-language">{language || "text"}</span>
        <div className="code-block-actions">
          {canPreview && (
            <button
              onClick={onPreview}
              className="code-block-copy"
              type="button"
              aria-label="Preview code"
            >
              <span>预览</span>
            </button>
          )}
          <button
            onClick={handleCopy}
            className="code-block-copy"
            aria-label="Copy code"
            type="button"
          >
            {copied ? (
              <>
                <Check className="h-3 w-3" />
                <span>Copied</span>
              </>
            ) : (
              <>
                <Copy className="h-3 w-3" />
                <span>Copy</span>
              </>
            )}
          </button>
        </div>
      </div>
      <SyntaxHighlighter
        style={isDark ? oneDark : oneLight}
        language={language || "text"}
        PreTag="div"
        customStyle={{
          margin: 0,
          padding: "0.75rem",
          borderRadius: "0 0 0.5rem 0.5rem",
          fontSize: "0.875rem",
        }}
      >
        {children}
      </SyntaxHighlighter>
    </div>
  );
}

export default function Markdown({
  content,
  className,
  onClickCitation,
  allowCodePreview = true,
}: MarkdownProps) {
  const workbench = useOptionalWorkbench();
  const processedContent = React.useMemo(() => preProcess(content), [content]);
  const handlePreviewCode = React.useCallback(
    (language: string, code: string) => {
      if (!allowCodePreview || !workbench) return;

      const previewLanguage = getCodePreviewLanguage(language);
      if (!previewLanguage) return;

      workbench.openPanel({
        type: "code-preview",
        title: `${previewLanguage.toUpperCase()} 预览`,
        payload: {
          language: previewLanguage,
          code,
        },
      });
    },
    [allowCodePreview, workbench],
  );

  return (
    <div className={cn("markdown", className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex, rehypeRaw]}
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
                  onPreview={
                    allowCodePreview && workbench
                      ? () => {
                          handlePreviewCode(language, code);
                        }
                      : undefined
                  }
                >
                  {code}
                </CodeBlock>
              );
            }

            return (
              <code className="inline-code" {...props}>
                {children}
              </code>
            );
          },
          a: ({ href, children, ...props }) => {
            const childText = typeof children === "string" ? children : "";

            // Citation format: [citation,domain](id)
            if (childText.startsWith("citation,")) {
              const domain = childText.substring("citation,".length);
              const id = href || "";

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
      </ReactMarkdown>
    </div>
  );
}
