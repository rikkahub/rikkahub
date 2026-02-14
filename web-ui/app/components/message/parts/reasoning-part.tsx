import * as React from "react";
import { ChevronDown, ChevronRight, Brain } from "lucide-react";
import Markdown from "~/components/markdown/markdown";
import Think from "~/assets/think.svg?react";

interface ReasoningPartProps {
  reasoning: string;
  isFinished?: boolean;
}

export function ReasoningPart({ reasoning, isFinished = true }: ReasoningPartProps) {
  const [expanded, setExpanded] = React.useState(false);

  if (!reasoning) return null;

  return (
    <div className="rounded-md border border-muted bg-muted/30">
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-muted-foreground hover:bg-muted/50"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        <Think className="h-4 w-4" />?<span>{isFinished ? "Thinking" : "Thinking..."}</span>
      </button>
      {expanded && (
        <div className="border-t border-muted px-3 py-2 text-sm text-muted-foreground">
          <Markdown content={reasoning} />
        </div>
      )}
    </div>
  );
}
