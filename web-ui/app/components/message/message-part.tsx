import * as React from "react";
import { useTranslation } from "react-i18next";

import type { ReasoningPart, ToolPart, UIMessagePart } from "~/types";

import { ChainOfThought } from "./chain-of-thought";
import { AudioPart } from "./parts/audio-part";
import { DocumentPart } from "./parts/document-part";
import { ImagePart } from "./parts/image-part";
import { ReasoningPart as ReasoningFallbackPart } from "./parts/reasoning-part";
import { ReasoningStepPart } from "./parts/reasoning-step-part";
import { TextPart } from "./parts/text-part";
import { ToolPart as ToolStepPart } from "./parts/tool-part";
import { VideoPart } from "./parts/video-part";

type ThinkingStep =
  | {
      type: "reasoning";
      reasoning: ReasoningPart;
    }
  | {
      type: "tool";
      tool: ToolPart;
    };

type MessagePartBlock =
  | {
      type: "thinking";
      steps: ThinkingStep[];
    }
  | {
      type: "content";
      part: UIMessagePart;
      index: number;
    };

export function groupMessageParts(parts: UIMessagePart[]): MessagePartBlock[] {
  const result: MessagePartBlock[] = [];
  let currentThinkingSteps: ThinkingStep[] = [];

  const flushThinkingSteps = () => {
    if (currentThinkingSteps.length === 0) return;
    result.push({ type: "thinking", steps: currentThinkingSteps });
    currentThinkingSteps = [];
  };

  parts.forEach((part, index) => {
    if (part.type === "reasoning") {
      currentThinkingSteps.push({ type: "reasoning", reasoning: part });
      return;
    }

    if (part.type === "tool") {
      currentThinkingSteps.push({ type: "tool", tool: part });
      return;
    }

    flushThinkingSteps();
    result.push({ type: "content", part, index });
  });

  flushThinkingSteps();
  return result;
}

interface MessagePartsProps {
  parts: UIMessagePart[];
  loading?: boolean;
  onToolApproval?: (toolCallId: string, approved: boolean, reason: string) => void | Promise<void>;
}

function renderContentPart(
  part: UIMessagePart,
  t: (key: string, options?: Record<string, unknown>) => string,
) {
  switch (part.type) {
    case "text":
      return <TextPart text={part.text} />;
    case "image":
      return <ImagePart url={part.url} />;
    case "video":
      return <VideoPart url={part.url} />;
    case "audio":
      return <AudioPart url={part.url} />;
    case "document":
      return <DocumentPart url={part.url} fileName={part.fileName} mime={part.mime} />;
    case "reasoning":
      return (
        <ReasoningFallbackPart reasoning={part.reasoning} isFinished={part.finishedAt != null} />
      );
    case "tool":
      return (
        <div className="text-xs text-muted-foreground">{t("message_parts.tool_step_hint")}</div>
      );
  }
}

export function MessageParts({ parts, loading = false, onToolApproval }: MessagePartsProps) {
  const { t } = useTranslation("message");
  const groupedParts = React.useMemo(() => groupMessageParts(parts), [parts]);

  return (
    <>
      {groupedParts.map((block, blockIndex) => {
        if (block.type === "thinking") {
          if (block.steps.length === 0) return null;

          return (
            <ChainOfThought
              key={`thinking-${blockIndex}`}
              className="my-1"
              collapseLabel={t("message_parts.collapse_thinking")}
              showMoreLabel={(hiddenCount) =>
                t("message_parts.expand_thinking_steps", { count: hiddenCount })
              }
              steps={block.steps}
              renderStep={(step, stepIndex, { isFirst, isLast }) => {
                if (step.type === "reasoning") {
                  const stepKey = step.reasoning.createdAt ?? `${blockIndex}-${stepIndex}`;
                  return (
                    <ReasoningStepPart
                      key={stepKey}
                      reasoning={step.reasoning}
                      isFirst={isFirst}
                      isLast={isLast}
                    />
                  );
                }

                const stepKey = step.tool.toolCallId || `${blockIndex}-${stepIndex}`;
                return (
                  <ToolStepPart
                    key={stepKey}
                    tool={step.tool}
                    loading={loading && step.tool.output.length === 0}
                    onToolApproval={onToolApproval}
                    isFirst={isFirst}
                    isLast={isLast}
                  />
                );
              }}
            />
          );
        }

        return (
          <React.Fragment key={`content-${block.index}`}>
            {renderContentPart(block.part, t)}
          </React.Fragment>
        );
      })}
    </>
  );
}

interface MessagePartProps {
  part: UIMessagePart;
  loading?: boolean;
  onToolApproval?: (toolCallId: string, approved: boolean, reason: string) => void | Promise<void>;
}

export function MessagePart({ part, loading, onToolApproval }: MessagePartProps) {
  return <MessageParts parts={[part]} loading={loading} onToolApproval={onToolApproval} />;
}
