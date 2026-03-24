import { describe, expect, it, vi } from "vitest";

import {
  appendWebAuthQuery,
  clearWebAuthToken,
  onWebAuthRequired,
  setWebAuthToken,
} from "~/services/api";

describe("web auth helpers", () => {
  it("appends a valid token to API URLs while preserving query and hash", () => {
    setWebAuthToken("token-123", Date.now() + 60_000);

    expect(appendWebAuthQuery("/api/conversations")).toBe(
      "/api/conversations?access_token=token-123",
    );
    expect(appendWebAuthQuery("/api/files?id=1#preview")).toBe(
      "/api/files?id=1&access_token=token-123#preview",
    );
  });

  it("clears nearly expired tokens instead of appending them", () => {
    setWebAuthToken("expiring-token", Date.now() + 5_000);

    expect(appendWebAuthQuery("/api/conversations")).toBe("/api/conversations");
    expect(window.localStorage.getItem("rikkahub:web-auth")).toBeNull();
  });

  it("subscribes and unsubscribes from auth-required events", () => {
    const listener = vi.fn();
    const dispose = onWebAuthRequired(listener);

    window.dispatchEvent(
      new CustomEvent("rikkahub:web-auth-required", {
        detail: { message: "Unauthorized", code: 401 },
      }),
    );

    expect(listener).toHaveBeenCalledWith({ message: "Unauthorized", code: 401 });

    dispose();
    clearWebAuthToken();

    window.dispatchEvent(
      new CustomEvent("rikkahub:web-auth-required", {
        detail: { message: "Should not fire", code: 401 },
      }),
    );

    expect(listener).toHaveBeenCalledTimes(1);
  });
});
