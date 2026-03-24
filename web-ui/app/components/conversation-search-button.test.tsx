import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ConversationSearchButton } from "~/components/conversation-search-button";

const apiMock = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock("~/services/api", () => ({
  default: apiMock,
}));

describe("ConversationSearchButton", () => {
  it("searches after debounce and selects a result", async () => {
    const onSelect = vi.fn();

    apiMock.get.mockReset();
    apiMock.get.mockResolvedValue([
      {
        nodeId: "node-1",
        messageId: "message-1",
        conversationId: "conversation-1",
        title: "Release notes",
        updateAt: new Date("2026-03-24T10:00:00Z").getTime(),
        snippet: "Ship the [release] notes",
      },
    ]);

    render(<ConversationSearchButton onSelect={onSelect} />);

    fireEvent.click(screen.getByRole("button", { name: "Search conversations" }));
    fireEvent.change(screen.getByPlaceholderText("Search message content..."), {
      target: { value: "release" },
    });

    await waitFor(() => {
      expect(apiMock.get).toHaveBeenCalledWith("conversations/search", {
        searchParams: { query: "release" },
      });
    }, { timeout: 2_000 });

    fireEvent.click(await screen.findByText("Release notes"));

    expect(onSelect).toHaveBeenCalledWith("conversation-1");
  });

  it("shows the backend error message when search fails", async () => {
    apiMock.get.mockReset();
    apiMock.get.mockRejectedValue(new Error("Backend unavailable"));

    render(<ConversationSearchButton onSelect={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Search conversations" }));
    fireEvent.change(screen.getByPlaceholderText("Search message content..."), {
      target: { value: "error" },
    });

    expect(await screen.findByText("Backend unavailable")).toBeInTheDocument();
  });
});
