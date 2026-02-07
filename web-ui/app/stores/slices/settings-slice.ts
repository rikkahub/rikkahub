import type { StateCreator } from "zustand";

import type { AppStoreState, SettingsSlice } from "~/stores/slices/types";

export const createSettingsSlice: StateCreator<AppStoreState, [], [], SettingsSlice> = (set) => ({
  settings: null,
  setSettings: (settings) => set({ settings }),
});
