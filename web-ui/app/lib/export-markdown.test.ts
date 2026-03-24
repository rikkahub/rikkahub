import { describe, expect, it } from "vitest";

import { convertConversationToMarkdown, convertMessageToMarkdown } from "~/lib/export-markdown";
import type { ConversationDto, MessageDto } from "~/types";

const assistantMessage: MessageDto = {
  id: "assistant-1",
  role: "ASSISTANT",
  createdAt: "2026-03-24T00:00:00Z",
  annotations: [],
  parts: [
    { type: "text", text: "Answer with context." },
    { type: "reasoning", reasoning: "step one\nstep two" },
    { type: "image", url: "https://example.com/result.png" },
  ],
};

describe("export-markdown", () => {
  it("renders a message into markdown with reasoning when requested", () => {
    expect(convertMessageToMarkdown(assistantMessage, true)).toMatchInlineSnapshot(`
      "Answer with context.

      > **Thinking:**
      > step one
      > step two

      ![image](https://example.com/result.png)"
    `);
  });

  it("renders the selected branch of a conversation into markdown", () => {
    const detail: ConversationDto = {
      id: "conversation-1",
      assistantId: "assistant-1",
      title: "Release checklist",
      truncateIndex: -1,
      chatSuggestions: [],
      isPinned: false,
      createAt: 1,
      updateAt: 2,
      isGenerating: false,
      messages: [
        {
          id: "node-1",
          selectIndex: 0,
          messages: [
            {
              id: "user-1",
              role: "USER",
              createdAt: "2026-03-24T00:00:00Z",
              annotations: [],
              parts: [{ type: "text", text: "Ship the release notes." }],
            },
          ],
        },
        {
          id: "node-2",
          selectIndex: 1,
          messages: [
            {
              ...assistantMessage,
              id: "assistant-branch-1",
              parts: [{ type: "text", text: "Old answer" }],
            },
            {
              ...assistantMessage,
              id: "assistant-branch-2",
              parts: [
                { type: "text", text: "Final answer" },
                {
                  type: "document",
                  fileName: "notes.md",
                  url: "/api/files/1",
                  mime: "text/markdown",
                },
              ],
            },
          ],
        },
      ],
    };

    expect(convertConversationToMarkdown(detail, false)).toMatchInlineSnapshot(`
      "# Release checklist

      ## User

      Ship the release notes.

      ## Assistant

      Final answer

      [notes.md](/api/files/1)"
    `);
  });
});
