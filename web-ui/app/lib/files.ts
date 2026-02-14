import { appendWebAuthQuery } from "~/services/api";

/**
 * Convert file URL to the correct API endpoint
 * - data: URLs are returned as-is (base64 encoded files)
 * - http/https URLs are returned as-is (external files)
 * - file:// URLs are extracted to relative paths and converted to /api/files/path/{path}
 * - Relative paths are converted to /api/files/path/{path}
 */
export function resolveFileUrl(url: string): string {
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
      return appendWebAuthQuery(`/api/files/path/${match[1]}`);
    }
    // If we can't extract the path, return as-is (will fail to load with error)
    return url;
  }

  // Relative path - convert to API endpoint
  // Remove leading slash if present
  const path = url.startsWith("/") ? url.slice(1) : url;
  return appendWebAuthQuery(`/api/files/path/${path}`);
}
