import * as React from "react";
import { AudioLines, VolumeX } from "lucide-react";

import { resolveFileUrl } from "~/lib/files";

interface AudioPartProps {
  url: string;
}

export function AudioPart({ url }: AudioPartProps) {
  const [error, setError] = React.useState(false);

  if (!url) return null;

  const audioUrl = resolveFileUrl(url);

  if (error) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        <VolumeX className="h-4 w-4" />
        <span>Failed to load audio: {audioUrl}</span>
      </div>
    );
  }

  return (
    <div className="my-2 max-w-md space-y-2 rounded-xl border border-muted bg-card p-3">
      <audio
        className="w-full"
        controls
        onError={() => setError(true)}
        preload="metadata"
        src={audioUrl}
      />
      <a
        className="text-muted-foreground inline-flex items-center gap-1 text-xs hover:underline"
        href={audioUrl}
        rel="noreferrer"
        target="_blank"
      >
        <AudioLines className="h-3.5 w-3.5" />
        在新窗口打开音频
      </a>
    </div>
  );
}
