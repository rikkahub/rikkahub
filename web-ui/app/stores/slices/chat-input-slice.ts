import type { StateCreator } from "zustand";

import type { AppStoreState, ChatInputSlice, Draft } from "~/stores/slices/types";
import type { UIMessagePart } from "~/types";

const EMPTY_DRAFT: Draft = {
  text: "",
  parts: [],
};

function getDraft(drafts: Record<string, Draft>, conversationId: string): Draft {
  return drafts[conversationId] ?? EMPTY_DRAFT;
}

export const createChatInputSlice: StateCreator<AppStoreState, [], [], ChatInputSlice> = (
  set,
  get,
) => ({
  drafts: {},
  setText: (conversationId, text) => {
    set((state) => {
      const draft = getDraft(state.drafts, conversationId);
      return {
        drafts: {
          ...state.drafts,
          [conversationId]: {
            ...draft,
            text,
          },
        },
      };
    });
  },
  addParts: (conversationId, parts) => {
    if (parts.length === 0) return;

    set((state) => {
      const draft = getDraft(state.drafts, conversationId);
      return {
        drafts: {
          ...state.drafts,
          [conversationId]: {
            ...draft,
            parts: [...draft.parts, ...parts],
          },
        },
      };
    });
  },
  removePartAt: (conversationId, index) => {
    set((state) => {
      const draft = state.drafts[conversationId];
      if (!draft) return state;
      if (index < 0 || index >= draft.parts.length) return state;

      return {
        drafts: {
          ...state.drafts,
          [conversationId]: {
            ...draft,
            parts: draft.parts.filter((_, partIndex) => partIndex !== index),
          },
        },
      };
    });
  },
  clearDraft: (conversationId) => {
    set((state) => {
      if (!(conversationId in state.drafts)) return state;
      const nextDrafts = { ...state.drafts };
      delete nextDrafts[conversationId];
      return { drafts: nextDrafts };
    });
  },
  isEmpty: (conversationId) => {
    const draft = get().drafts[conversationId];
    if (!draft) return true;
    return draft.text.trim().length === 0 && draft.parts.length === 0;
  },
  getSubmitParts: (conversationId) => {
    const draft = get().drafts[conversationId];
    if (!draft) return [];

    const hasText = draft.text.trim().length > 0;
    if (!hasText) {
      return draft.parts;
    }

    const textPart: UIMessagePart = {
      type: "text",
      text: draft.text,
    };

    return [textPart, ...draft.parts];
  },
});
