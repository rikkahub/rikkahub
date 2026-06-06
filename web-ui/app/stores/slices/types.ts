import type { Settings, UIMessagePart } from "~/types";

export interface Draft {
  text: string;
  parts: UIMessagePart[];
  modeInjectionIds?: string[];
  lorebookIds?: string[];
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
  setPromptInjectionIds: (
    conversationId: string,
    ids: { modeInjectionIds: string[]; lorebookIds: string[] },
  ) => void;
  getPromptInjectionIds: (conversationId: string) => {
    modeInjectionIds: string[];
    lorebookIds: string[];
  };
  clearDraft: (conversationId: string) => void;
  isEmpty: (conversationId: string) => boolean;
  getSubmitParts: (conversationId: string) => UIMessagePart[];
}

export interface ClockSlice {
  clockOffset: number;
  setClockOffset: (serverTime: number) => void;
}

export type AppStoreState = SettingsSlice & ChatInputSlice & ClockSlice;
