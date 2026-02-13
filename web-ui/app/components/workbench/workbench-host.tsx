import * as React from "react";
import { useTranslation } from "react-i18next";

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
  const { t } = useTranslation();
  const [mode, setMode] = React.useState<"preview" | "source">("preview");

  const language = readStringField(panel.payload, "language");
  const normalizedLanguage = getCodePreviewLanguage(language);
  const code = readStringField(panel.payload, "code");

  const canRenderPreview =
    normalizedLanguage === "html" ||
    normalizedLanguage === "svg" ||
    normalizedLanguage === "markdown" ||
    normalizedLanguage === "mermaid";

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

    if (normalizedLanguage === "mermaid") {
      const encodedCode = encodeURIComponent(code);
      return `<!doctype html>
<html>
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <style>
      html, body {
        margin: 0;
        padding: 0;
        background: #ffffff;
        color: #1f2937;
        font-family: ui-sans-serif, system-ui, sans-serif;
      }
      #container {
        min-height: 100vh;
        box-sizing: border-box;
        padding: 16px;
        display: flex;
        justify-content: center;
      }
      #diagram {
        width: 100%;
      }
      #error {
        display: none;
        width: 100%;
        white-space: pre-wrap;
        color: #b91c1c;
        background: #fef2f2;
        border: 1px solid #fecaca;
        border-radius: 8px;
        padding: 12px;
        font-size: 12px;
      }
    </style>
  </head>
  <body>
    <div id="container">
      <div id="diagram"></div>
      <pre id="error"></pre>
    </div>
    <script type="module">
      import mermaid from "https://esm.sh/mermaid@11";

      const source = decodeURIComponent("${encodedCode}");
      const diagram = document.getElementById("diagram");
      const errorEl = document.getElementById("error");

      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "loose",
      });

      try {
        const id = "mermaid-" + Math.random().toString(36).slice(2);
        const result = await mermaid.render(id, source.trim());
        if (diagram) {
          diagram.innerHTML = result.svg;
        }
      } catch (error) {
        if (errorEl) {
          errorEl.style.display = "block";
          errorEl.textContent = error instanceof Error ? error.message : String(error);
        }
      }
    </script>
  </body>
</html>`;
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
          {t("workbench.preview")}
        </Button>
        <Button
          type="button"
          size="sm"
          variant={mode === "source" ? "secondary" : "ghost"}
          onClick={() => {
            setMode("source");
          }}
        >
          {t("workbench.source_code")}
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
              sandbox="allow-scripts allow-same-origin"
              srcDoc={iframeDoc}
              className="h-full w-full border-0"
            />
          )
        ) : (
          <pre className="h-full overflow-auto bg-muted/30 p-4 text-xs">
            {code || t("workbench.empty_content")}
          </pre>
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
  const { t } = useTranslation();
  const renderer = PANEL_RENDERERS[panel.type];

  return (
    <section className={cn("flex h-full min-h-0 flex-col border-l", className)}>
      <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
        <div className="min-w-0">
          <div className="truncate font-medium text-sm">{panel.title}</div>
          <div className="truncate text-muted-foreground text-xs">
            {t("workbench.type_label", { type: panel.type })}
          </div>
        </div>
        <Button
          aria-label={t("workbench.close_panel")}
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
