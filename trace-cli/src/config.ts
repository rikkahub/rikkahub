import { dirname, resolve } from "node:path";
import { parse } from "yaml";
import { z } from "zod";
import { PROVIDERS, type LoadedTraceCase, type TraceConfig } from "./types";

const headersSchema = z.record(z.string(), z.string()).default({});

const traceCaseSchema = z.object({
  name: z.string().trim().min(1),
  provider: z.enum(PROVIDERS),
  model: z.string().trim().min(1).optional(),
  apiKeyEnv: z.string().trim().min(1).optional(),
  auth: z.object({
    header: z.string().trim().min(1),
    scheme: z.string().trim().min(1).optional(),
  }).optional(),
  baseUrl: z.url().optional(),
  endpoint: z.string().trim().min(1).optional(),
  output: z.string().trim().min(1).optional(),
  timeoutMs: z.number().int().positive().optional(),
  headers: headersSchema,
  body: z.record(z.string(), z.unknown()),
});

const configSchema = z.object({
  version: z.literal(1),
  defaults: z.object({
    outputRoot: z.string().trim().min(1).optional(),
    timeoutMs: z.number().int().positive().optional(),
    headers: headersSchema,
  }).default({ headers: {} }),
  traces: z.array(traceCaseSchema).min(1),
});

export async function loadTraceConfig(configPath: string): Promise<TraceConfig> {
  const source = await Bun.file(configPath).text();
  const result = configSchema.safeParse(parse(source));
  if (!result.success) {
    throw new Error(`Invalid trace config:\n${z.prettifyError(result.error)}`);
  }

  const names = new Set<string>();
  for (const trace of result.data.traces) {
    if (names.has(trace.name)) {
      throw new Error(`Duplicate trace name: ${trace.name}`);
    }
    names.add(trace.name);
  }
  return result.data;
}

export function resolveTraceCases(
  config: TraceConfig,
  configPath: string,
  selectedName?: string,
): LoadedTraceCase[] {
  const configDir = dirname(configPath);
  const outputRoot = resolve(
    configDir,
    config.defaults.outputRoot ?? "../ai/src/test/resources/stream-traces/generated",
  );
  const selected = selectedName
    ? config.traces.filter((trace) => trace.name === selectedName)
    : config.traces;

  if (selected.length === 0) {
    throw new Error(`Trace case not found: ${selectedName}`);
  }

  return selected.map((trace) => ({
    ...trace,
    headers: {
      ...config.defaults.headers,
      ...trace.headers,
    },
    outputPath: trace.output
      ? resolve(configDir, trace.output)
      : resolve(outputRoot, trace.provider, trace.name, "events.jsonl"),
    timeoutMs: trace.timeoutMs ?? config.defaults.timeoutMs ?? 120_000,
  }));
}
