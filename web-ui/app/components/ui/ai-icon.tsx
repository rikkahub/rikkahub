import * as React from "react";

import { cn } from "~/lib/utils";

export interface AIIconProps {
  name: string;
  size?: number;
  loading?: boolean;
  className?: string;
  imageClassName?: string;
}

function toFallbackText(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length === 0) {
    return "A";
  }

  return trimmed.slice(0, 1).toUpperCase();
}

export function AIIcon({
  name,
  size = 24,
  loading = false,
  className,
  imageClassName,
}: AIIconProps) {
  const normalizedName = name.trim() || "auto";
  const fallbackText = toFallbackText(normalizedName);
  const src = React.useMemo(
    () => `/api/ai-icon?name=${encodeURIComponent(normalizedName)}`,
    [normalizedName],
  );
  const [loadFailed, setLoadFailed] = React.useState(false);

  React.useEffect(() => {
    setLoadFailed(false);
  }, [src]);

  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-secondary",
        loading && "animate-pulse",
        className,
      )}
      style={{ width: size, height: size }}
      aria-label={normalizedName}
      title={normalizedName}
    >
      {loadFailed ? (
        <span className="text-[10px] font-medium text-muted-foreground">{fallbackText}</span>
      ) : (
        <img
          src={src}
          alt={normalizedName}
          className={cn("h-[72%] w-[72%] object-contain", imageClassName)}
          loading="lazy"
          decoding="async"
          onError={() => {
            setLoadFailed(true);
          }}
        />
      )}
    </span>
  );
}
