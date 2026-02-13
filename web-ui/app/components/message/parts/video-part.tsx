import * as React from "react";
import { Video, VideoOff } from "lucide-react";
import { useTranslation } from "react-i18next";

import { resolveFileUrl } from "~/lib/files";

interface VideoPartProps {
  url: string;
}

export function VideoPart({ url }: VideoPartProps) {
  const { t } = useTranslation("message");
  const [error, setError] = React.useState(false);

  if (!url) return null;

  const videoUrl = resolveFileUrl(url);

  if (error) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        <VideoOff className="h-4 w-4" />
        <span>{t("media_part.video_load_failed", { url: videoUrl })}</span>
      </div>
    );
  }

  return (
    <div className="my-2 max-w-md space-y-2">
      <video
        controls
        className="w-full rounded-md border border-muted bg-black/80"
        onError={() => setError(true)}
        preload="metadata"
        src={videoUrl}
      />
      <a
        className="text-muted-foreground inline-flex items-center gap-1 text-xs hover:underline"
        href={videoUrl}
        rel="noreferrer"
        target="_blank"
      >
        <Video className="h-3.5 w-3.5" />
        {t("media_part.open_video_in_new_window")}
      </a>
    </div>
  );
}
