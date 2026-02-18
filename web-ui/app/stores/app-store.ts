import { create } from "zustand";

import { createChatInputSlice } from "~/stores/slices/chat-input-slice";
import { createClockSlice } from "~/stores/slices/clock-slice";
import { createSettingsSlice } from "~/stores/slices/settings-slice";
import type { AppStoreState } from "~/stores/slices/types";

export const useAppStore = create<AppStoreState>()((...args) => ({
  ...createSettingsSlice(...args),
  ...createChatInputSlice(...args),
  ...createClockSlice(...args),
}));

export const useSettingsStore = useAppStore;
export const useChatInputStore = useAppStore;
export const useClockStore = useAppStore;

export type { AppStoreState, ChatInputSlice, ClockSlice, Draft, SettingsSlice } from "~/stores/slices/types";
