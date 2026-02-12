import * as React from "react";
import { Brain, Sparkles } from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import type { ReasoningPart as UIReasoningPart } from "~/types";
import Think from "~/assets/think.svg?react";

import { ControlledChainOfThoughtStep } from "../chain-of-thought";

interface ReasoningStepPartProps {
  reasoning: UIReasoningPart;
  isFirst?: boolean;
  isLast?: boolean;
}

enum ReasoningCardState {
  Collapsed = "collapsed",
  Preview = "preview",
  Expanded = "expanded",
}

function formatDuration(createdAt?: string, finishedAt?: string | null): string | null {
  if (!createdAt) return null;

  const start = Date.parse(createdAt);
  if (Number.isNaN(start)) return null;

  const end = finishedAt ? Date.parse(finishedAt) : Date.now();
  if (Number.isNaN(end)) return null;

  const seconds = Math.max((end - start) / 1000, 0);
  if (seconds <= 0) return null;

  return `${seconds.toFixed(1)}s`;
}

export function ReasoningStepPart({ reasoning, isFirst, isLast }: ReasoningStepPartProps) {
  const loading = reasoning.finishedAt == null;
  const [expandState, setExpandState] = React.useState<ReasoningCardState>(
    ReasoningCardState.Collapsed,
  );
  const contentRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    if (loading) {
      setExpandState((state) =>
        state === ReasoningCardState.Collapsed ? ReasoningCardState.Preview : state,
      );
      return;
    }

    setExpandState((state) =>
      state === ReasoningCardState.Collapsed ? state : ReasoningCardState.Collapsed,
    );
  }, [loading, reasoning.reasoning]);

  React.useEffect(() => {
    if (loading && expandState === ReasoningCardState.Preview && contentRef.current) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight;
    }
  }, [loading, expandState, reasoning.reasoning]);

  const onExpandedChange = (nextExpanded: boolean) => {
    if (loading) {
      setExpandState(nextExpanded ? ReasoningCardState.Expanded : ReasoningCardState.Preview);
      return;
    }

    setExpandState(nextExpanded ? ReasoningCardState.Expanded : ReasoningCardState.Collapsed);
  };

  const duration = formatDuration(reasoning.createdAt, reasoning.finishedAt);
  const preview = expandState === ReasoningCardState.Preview;

  return (
    <ControlledChainOfThoughtStep
      expanded={expandState === ReasoningCardState.Expanded}
      onExpandedChange={onExpandedChange}
      isFirst={isFirst}
      isLast={isLast}
      icon={
        loading ? (
          <Sparkles className="h-4 w-4 animate-pulse text-primary" />
        ) : (
          <Think className="h-4 w-4 text-primary" />
        )
      }
      label={<span className="text-foreground text-xs font-medium">深度思考</span>}
      extra={
        duration ? <span className="text-muted-foreground text-xs">{duration}</span> : undefined
      }
      contentVisible={expandState !== ReasoningCardState.Collapsed}
    >
      <div
        ref={contentRef}
        className={preview ? "styled-scrollbar relative max-h-24 overflow-y-auto" : undefined}
      >
        <Markdown content={reasoning.reasoning} className="text-xs" />
      </div>
    </ControlledChainOfThoughtStep>
  );
}
