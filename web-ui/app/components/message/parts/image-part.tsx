import * as React from "react";
import { ImageOff } from "lucide-react";

interface ImagePartProps {
  url: string;
}

/**
 * Convert image URL to the correct API endpoint
 * - data: URLs are returned as-is (base64 images)
 * - http/https URLs are returned as-is (external images)
 * - file:// URLs are extracted to relative paths and converted to /api/files/path/{path}
 * - Relative paths are converted to /api/files/path/{path}
 */
function resolveImageUrl(url: string): string {
  if (url.startsWith("data:")) {
    return url;
  }
  if (url.startsWith("http://") || url.startsWith("https://")) {
    return url;
  }

  // Handle file:// protocol URLs from Android
  if (url.startsWith("file://")) {
    // Extract path after /files/
    // Format: file:///data/user/0/package.name/files/upload/xxx
    const match = url.match(/file:\/\/.*?\/files\/(.+)/);
    if (match && match[1]) {
      return `/api/files/path/${match[1]}`;
    }
    // If we can't extract the path, return as-is (will fail to load with error)
    return url;
  }

  // Relative path - convert to API endpoint
  // Remove leading slash if present
  const path = url.startsWith("/") ? url.slice(1) : url;
  return `/api/files/path/${path}`;
}

export function ImagePart({ url }: ImagePartProps) {
  const [error, setError] = React.useState(false);
  const [loaded, setLoaded] = React.useState(false);
  const imageUrl = resolveImageUrl(url);

  if (!url) return null;

  if (error) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        <ImageOff className="h-4 w-4" />
        <span>Failed to load image: {resolveImageUrl(url)}</span>
      </div>
    );
  }

  return (
    <div className="relative my-2 max-w-md">
      {!loaded && (
        <div className="flex h-48 items-center justify-center rounded-md border border-muted bg-muted/30">
          <div className="text-sm text-muted-foreground">Loading image...</div>
        </div>
      )}
      <img
        src={imageUrl}
        alt="Message attachment"
        className={`rounded-md border border-muted object-contain ${loaded ? "block" : "hidden"}`}
        onLoad={() => setLoaded(true)}
        onError={() => setError(true)}
        style={{ maxHeight: "500px", width: "auto" }}
      />
    </div>
  );
}
