import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { WebAuthGate } from "~/components/web-auth-gate";

type WebAuthDetail = { message: string; code: number };

const apiMocks = vi.hoisted(() => {
  let listener: ((detail: WebAuthDetail) => void) | null = null;

  return {
    requestWebAuthToken: vi.fn(),
    onWebAuthRequired: vi.fn((nextListener: (detail: WebAuthDetail) => void) => {
      listener = nextListener;
      return vi.fn(() => {
        listener = null;
      });
    }),
    emit(detail: WebAuthDetail) {
      listener?.(detail);
    },
  };
});

vi.mock("~/services/api", () => ({
  onWebAuthRequired: apiMocks.onWebAuthRequired,
  requestWebAuthToken: apiMocks.requestWebAuthToken,
}));

describe("WebAuthGate", () => {
  beforeEach(() => {
    apiMocks.requestWebAuthToken.mockReset();
  });

  it("requires a password before unlocking", async () => {
    const user = userEvent.setup();

    render(<WebAuthGate />);
    apiMocks.emit({ message: "Unauthorized", code: 401 });

    await user.click(await screen.findByRole("button", { name: "Unlock" }));

    expect(await screen.findByText("Please enter your access password.")).toBeInTheDocument();
    expect(apiMocks.requestWebAuthToken).not.toHaveBeenCalled();
  });

  it("submits the password and reloads the page after success", async () => {
    const user = userEvent.setup();
    apiMocks.requestWebAuthToken.mockResolvedValue({
      token: "token-123",
      expiresAt: Date.now() + 60_000,
    });

    render(<WebAuthGate />);
    apiMocks.emit({ message: "Unauthorized", code: 401 });

    await user.type(await screen.findByPlaceholderText("Access password"), "secret");
    await user.click(screen.getByRole("button", { name: "Unlock" }));

    await waitFor(() => {
      expect(apiMocks.requestWebAuthToken).toHaveBeenCalledWith("secret");
    });

    await waitFor(() => {
      expect(screen.queryByText("Web API is locked")).not.toBeInTheDocument();
    });
  });
});
