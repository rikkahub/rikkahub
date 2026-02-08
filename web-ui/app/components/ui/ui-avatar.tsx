import { Avatar, AvatarFallback, AvatarImage } from "~/components/ui/avatar";
import { resolveFileUrl } from "~/lib/files";
import type { AssistantAvatar } from "~/types";

export interface UIAvatarProps {
  name: string;
  avatar?: AssistantAvatar | null;
  size?: "default" | "sm" | "lg";
  className?: string;
}

function getDisplayName(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length > 0) {
    return trimmed;
  }

  return "A";
}

function getAvatarFallback(name: string, avatar?: AssistantAvatar | null): string {
  const content = avatar?.content?.trim();
  if (content) {
    return content;
  }

  return getDisplayName(name).slice(0, 1).toUpperCase();
}

function getAvatarImage(avatar?: AssistantAvatar | null): string | null {
  const url = avatar?.url?.trim();
  if (!url) {
    return null;
  }

  return resolveFileUrl(url);
}

export function UIAvatar({ name, avatar, size = "default", className }: UIAvatarProps) {
  const imageUrl = getAvatarImage(avatar);
  const fallback = getAvatarFallback(name, avatar);

  // Radix Avatar keeps image loading state on the root; force remount when source changes.
  const avatarIdentity = `${avatar?.type ?? "dummy"}:${avatar?.url ?? ""}:${avatar?.content ?? ""}:${name}`;

  return (
    <Avatar key={avatarIdentity} size={size} className={className}>
      {imageUrl && <AvatarImage src={imageUrl} alt={name} />}
      <AvatarFallback>{fallback}</AvatarFallback>
    </Avatar>
  );
}
