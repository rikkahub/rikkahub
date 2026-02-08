import * as React from "react";

import { ExternalLink } from "lucide-react";

import { cn } from "~/lib/utils";
import type { UIMessageAnnotation } from "~/types";

function getCitationLabel(annotation: UIMessageAnnotation): string {
  if (annotation.title.trim().length > 0) {
    return annotation.title;
  }

  try {
    const hostname = new URL(annotation.url).hostname.replace(/^www\./, "");
    if (hostname.length > 0) {
      return hostname;
    }
  } catch {
    return annotation.url;
  }

  return annotation.url;
}

export function ChatMessageAnnotationsRow({
  annotations,
  alignRight,
}: {
  annotations?: UIMessageAnnotation[];
  alignRight: boolean;
}) {
  const citations = React.useMemo(
    () => annotations?.filter((annotation) => annotation.type === "url_citation") ?? [],
    [annotations],
  );

  if (citations.length === 0) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex w-full flex-wrap items-center gap-2 px-1",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      {citations.map((annotation, index) => {
        const label = getCitationLabel(annotation);

        return (
          <a
            key={`${annotation.url}-${index}`}
            href={annotation.url}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex max-w-full items-center gap-1 rounded-full border border-border bg-background px-2 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
            title={annotation.url || label}
          >
            <span className="max-w-[220px] truncate">{label}</span>
            <ExternalLink className="size-3" />
          </a>
        );
      })}
    </div>
  );
}
