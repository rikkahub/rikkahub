import { create } from "zustand";

import { createChatInputSlice } from "~/stores/slices/chat-input-slice";
import { createSettingsSlice } from "~/stores/slices/settings-slice";
import type { AppStoreState } from "~/stores/slices/types";

export const useAppStore = create<AppStoreState>()((...args) => ({
  ...createSettingsSlice(...args),
  ...createChatInputSlice(...args),
}));

export const useSettingsStore = useAppStore;
export const useChatInputStore = useAppStore;

export type { AppStoreState, ChatInputSlice, Draft, SettingsSlice } from "~/stores/slices/types";
