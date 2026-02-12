import * as React from "react";

import { X } from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import { Button } from "~/components/ui/button";
import { cn } from "~/lib/utils";

import { getCodePreviewLanguage } from "./code-preview-language";
import type { WorkbenchPanel } from "./workbench-context";

interface WorkbenchHostProps {
  panel: WorkbenchPanel;
  onClose: () => void;
  className?: string;
}

interface WorkbenchPanelRenderer {
  render: (panel: WorkbenchPanel) => React.ReactNode;
}

function readStringField(payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  return typeof value === "string" ? value : "";
}

function CodePreviewPanel({ panel }: { panel: WorkbenchPanel }) {
  const [mode, setMode] = React.useState<"preview" | "source">("preview");

  const language = readStringField(panel.payload, "language");
  const normalizedLanguage = getCodePreviewLanguage(language);
  const code = readStringField(panel.payload, "code");

  const canRenderPreview =
    normalizedLanguage === "html" ||
    normalizedLanguage === "svg" ||
    normalizedLanguage === "markdown";

  React.useEffect(() => {
    setMode(canRenderPreview ? "preview" : "source");
  }, [canRenderPreview, panel.payload]);

  const iframeDoc = React.useMemo(() => {
    if (normalizedLanguage === "html") {
      return code;
    }

    if (normalizedLanguage === "svg") {
      return `<!doctype html><html><body style="margin:0;display:flex;align-items:center;justify-content:center;padding:16px;">${code}</body></html>`;
    }

    return "";
  }, [code, normalizedLanguage]);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b px-3 py-2">
        <Button
          type="button"
          size="sm"
          variant={mode === "preview" ? "secondary" : "ghost"}
          disabled={!canRenderPreview}
          onClick={() => {
            setMode("preview");
          }}
        >
          预览
        </Button>
        <Button
          type="button"
          size="sm"
          variant={mode === "source" ? "secondary" : "ghost"}
          onClick={() => {
            setMode("source");
          }}
        >
          源码
        </Button>
      </div>

      <div className="flex-1 min-h-0">
        {mode === "preview" && canRenderPreview ? (
          normalizedLanguage === "markdown" ? (
            <div className="h-full overflow-auto p-4">
              <Markdown content={code} allowCodePreview={false} />
            </div>
          ) : (
            <iframe
              title={panel.title}
              sandbox="allow-scripts"
              srcDoc={iframeDoc}
              className="h-full w-full border-0"
            />
          )
        ) : (
          <pre className="h-full overflow-auto bg-muted/30 p-4 text-xs">{code || "(空内容)"}</pre>
        )}
      </div>
    </div>
  );
}

function UnknownPanel({ panel }: { panel: WorkbenchPanel }) {
  return (
    <div className="h-full overflow-auto p-4">
      <div className="rounded-lg border bg-muted/30 p-3 text-xs">
        <pre>{JSON.stringify(panel.payload, null, 2)}</pre>
      </div>
    </div>
  );
}

const PANEL_RENDERERS: Record<string, WorkbenchPanelRenderer> = {
  "code-preview": {
    render: (panel) => <CodePreviewPanel panel={panel} />,
  },
};

export function WorkbenchHost({ panel, onClose, className }: WorkbenchHostProps) {
  const renderer = PANEL_RENDERERS[panel.type];

  return (
    <section className={cn("flex h-full min-h-0 flex-col border-l", className)}>
      <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
        <div className="min-w-0">
          <div className="truncate font-medium text-sm">{panel.title}</div>
          <div className="truncate text-muted-foreground text-xs">类型：{panel.type}</div>
        </div>
        <Button
          aria-label="关闭面板"
          type="button"
          size="icon-sm"
          variant="ghost"
          onClick={onClose}
        >
          <X className="size-4" />
        </Button>
      </div>

      <div className="flex-1 min-h-0">
        {renderer ? renderer.render(panel) : <UnknownPanel panel={panel} />}
      </div>
    </section>
  );
}
