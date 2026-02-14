import type { Settings, UIMessagePart } from "~/types";

export interface Draft {
  text: string;
  parts: UIMessagePart[];
}

export interface SettingsSlice {
  settings: Settings | null;
  setSettings: (settings: Settings) => void;
}

export interface ChatInputSlice {
  drafts: Record<string, Draft>;
  setText: (conversationId: string, text: string) => void;
  addParts: (conversationId: string, parts: UIMessagePart[]) => void;
  removePartAt: (conversationId: string, index: number) => void;
  clearDraft: (conversationId: string) => void;
  isEmpty: (conversationId: string) => boolean;
  getSubmitParts: (conversationId: string) => UIMessagePart[];
}

export type AppStoreState = SettingsSlice & ChatInputSlice;
