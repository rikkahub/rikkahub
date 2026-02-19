import type { StateCreator } from "zustand";

import type { AppStoreState, ClockSlice } from "~/stores/slices/types";

export const createClockSlice: StateCreator<AppStoreState, [], [], ClockSlice> = (set) => ({
  clockOffset: 0,
  setClockOffset: (serverTime) => set({ clockOffset: serverTime - Date.now() }),
});
