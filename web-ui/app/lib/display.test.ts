import { describe, expect, it } from "vitest";

import { getAssistantDisplayName, getDisplayName, getModelDisplayName } from "~/lib/display";

describe("display helpers", () => {
  it("falls back when the display name is blank", () => {
    expect(getDisplayName("   ", "Fallback")).toBe("Fallback");
    expect(getAssistantDisplayName("")).toBe("默认助手");
    expect(getModelDisplayName("", "gpt-5")).toBe("gpt-5");
  });

  it("prefers the explicit display name", () => {
    expect(getDisplayName(" Alice ", "Fallback")).toBe("Alice");
    expect(getModelDisplayName("GPT 5", "gpt-5")).toBe("GPT 5");
  });
});
