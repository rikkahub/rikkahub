import type { ProviderModel } from "~/types";

export const PRESET_BUDGETS = {
  OFF: 0,
  AUTO: -1,
  MINIMAL: 1,
  LOW: 1024,
  MEDIUM: 16_000,
  HIGH: 32_000,
  XHIGH: 64_000,
} as const;

export type ReasoningLevel = keyof typeof PRESET_BUDGETS;

const DEFAULT_REASONING_PRESETS: ReasoningLevel[] = ["OFF", "AUTO", "LOW", "MEDIUM", "HIGH"];
const COMPATIBILITY_PRESETS: ReasoningLevel[] = ["AUTO", "OFF", "LOW", "MEDIUM", "HIGH"];
const REASONING_PRIORITY: ReasoningLevel[] = ["OFF", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH"];
const GPT_5_REGEX = /^gpt-5(?:\.(\d+))?(?:-([a-z0-9.-]+))?$/;

type GptReasoningBucket =
  | "GPT_5"
  | "GPT_5_PRO"
  | "GPT_5_CODEX"
  | "GPT_5_1"
  | "GPT_5_1_CODEX"
  | "GPT_5_1_CODEX_MAX"
  | "GPT_5_2_PLUS_BASE"
  | "GPT_5_2_PLUS_PRO"
  | "GPT_5_2_PLUS_CODEX";

const GPT_BUCKET_LEVELS: Record<GptReasoningBucket, ReasoningLevel[]> = {
  GPT_5: ["MINIMAL", "LOW", "MEDIUM", "HIGH"],
  GPT_5_PRO: ["HIGH"],
  GPT_5_CODEX: ["LOW", "MEDIUM", "HIGH"],
  GPT_5_1: ["OFF", "LOW", "MEDIUM", "HIGH"],
  GPT_5_1_CODEX: ["MEDIUM", "HIGH"],
  GPT_5_1_CODEX_MAX: ["MEDIUM", "HIGH", "XHIGH"],
  GPT_5_2_PLUS_BASE: ["OFF", "LOW", "MEDIUM", "HIGH", "XHIGH"],
  GPT_5_2_PLUS_PRO: ["MEDIUM", "HIGH", "XHIGH"],
  GPT_5_2_PLUS_CODEX: ["LOW", "MEDIUM", "HIGH", "XHIGH"],
};

export function supportsReasoningSelection(model: ProviderModel | null): boolean {
  if (!model) {
    return false;
  }

  return (model.abilities ?? []).includes("REASONING") || getSupportedGptReasoningLevels(model) !== null;
}

export function getReasoningPresets(model: ProviderModel | null): ReasoningLevel[] {
  const gptLevels = getSupportedGptReasoningLevels(model);
  return gptLevels ? ["AUTO", ...gptLevels] : DEFAULT_REASONING_PRESETS;
}

export function getReasoningLevel(budget: number | null | undefined): ReasoningLevel {
  if (budget == null) {
    return "AUTO";
  }

  const exactLevel = Object.entries(PRESET_BUDGETS).find(([, value]) => value === budget)?.[0] as
    | ReasoningLevel
    | undefined;
  if (exactLevel) {
    return exactLevel;
  }

  let closest = COMPATIBILITY_PRESETS[0];
  let minDistance = Number.POSITIVE_INFINITY;

  for (const preset of COMPATIBILITY_PRESETS) {
    const distance = Math.abs(budget - PRESET_BUDGETS[preset]);
    if (distance < minDistance) {
      minDistance = distance;
      closest = preset;
    }
  }

  return closest;
}

export function resolveReasoningLevel(
  model: ProviderModel | null,
  budget: number | null | undefined,
): ReasoningLevel {
  const requestedLevel = getReasoningLevel(budget);
  const gptLevels = getSupportedGptReasoningLevels(model);

  if (!gptLevels || requestedLevel === "AUTO") {
    return requestedLevel;
  }

  return clampReasoningLevel(requestedLevel, gptLevels);
}

export function getSupportedGptReasoningLevels(model: ProviderModel | null): ReasoningLevel[] | null {
  if (!model) {
    return null;
  }

  const bucket = getGptReasoningBucket(model.modelId);
  return bucket ? GPT_BUCKET_LEVELS[bucket] : null;
}

function getGptReasoningBucket(modelId: string): GptReasoningBucket | null {
  const normalizedModelId = normalizeModelId(modelId);
  const match = normalizedModelId.match(GPT_5_REGEX);
  if (!match) {
    return null;
  }

  const minorVersion = match[1] ? Number.parseInt(match[1], 10) : null;
  const suffix = match[2] ?? "";

  if (minorVersion == null && suffix.startsWith("codex")) {
    return "GPT_5_CODEX";
  }
  if (minorVersion == null && suffix.startsWith("pro")) {
    return "GPT_5_PRO";
  }
  if (minorVersion == null && suffix.length === 0) {
    return "GPT_5";
  }
  if (minorVersion === 1 && suffix.startsWith("codex-max")) {
    return "GPT_5_1_CODEX_MAX";
  }
  if (minorVersion === 1 && suffix.startsWith("codex")) {
    return "GPT_5_1_CODEX";
  }
  if (minorVersion === 1) {
    return "GPT_5_1";
  }
  if (minorVersion != null && minorVersion >= 2 && suffix.startsWith("pro")) {
    return "GPT_5_2_PLUS_PRO";
  }
  if (minorVersion != null && minorVersion >= 2 && suffix.includes("codex")) {
    return "GPT_5_2_PLUS_CODEX";
  }
  if (minorVersion != null && minorVersion >= 2) {
    return "GPT_5_2_PLUS_BASE";
  }

  return null;
}

function normalizeModelId(modelId: string): string {
  return modelId.trim().toLowerCase().split("/").pop()?.split("?")[0] ?? "";
}

function clampReasoningLevel(
  requestedLevel: ReasoningLevel,
  supportedLevels: ReasoningLevel[],
): ReasoningLevel {
  const requestedPriority = REASONING_PRIORITY.indexOf(requestedLevel);
  const sortedSupportedLevels = [...supportedLevels].sort(
    (left, right) => REASONING_PRIORITY.indexOf(left) - REASONING_PRIORITY.indexOf(right),
  );

  return (
    sortedSupportedLevels.find((level) => REASONING_PRIORITY.indexOf(level) >= requestedPriority) ??
    sortedSupportedLevels[sortedSupportedLevels.length - 1]
  );
}
