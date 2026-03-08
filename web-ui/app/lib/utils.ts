import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

import { useAppStore } from "~/stores/app-store";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function serverNow(): number {
  return Date.now() + useAppStore.getState().clockOffset;
}

export function extractThinkingTitle(text: string): string | null {
  const lines = text.split(/\r?\n/);

  for (let index = lines.length - 1; index >= 0; index -= 1) {
    const line = lines[index]?.trim();
    if (!line) continue;

    const match = line.match(/^\*\*(.+?)\*\*$/);
    const title = match?.[1]?.trim();
    if (title) {
      return title;
    }
  }

  return null;
}
