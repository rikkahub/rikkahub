import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

import { useAppStore } from "~/stores/app-store";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function serverNow(): number {
  return Date.now() + useAppStore.getState().clockOffset;
}
