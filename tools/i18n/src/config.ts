import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parse } from "yaml";
import { z } from "zod";

export const TOOL_DIR = resolve(import.meta.dir, "..");

const ConfigSchema = z.object({
  project_root: z.string().default("../.."),
  modules: z.array(z.object({ name: z.string(), res_path: z.string() })).min(1),
  source_patterns: z.array(z.string()).default([]),
  languages: z
    .array(
      z.object({
        code: z.string(),
        name: z.string(),
        is_source: z.boolean().default(false),
        // 术语表：英文术语 -> 目标语言译法，翻译时注入 prompt
        glossary: z
          .record(z.string(), z.string())
          .nullish()
          .transform((g) => g ?? {}),
      }),
    )
    .min(1),
  translation: z.object({
    model: z.string(),
    batch_size: z.number().int().positive().default(10),
    temperature: z.number().optional(),
    structured_outputs: z.boolean().default(false),
    prompt_template: z.string(),
  }),
});

type RawConfig = z.infer<typeof ConfigSchema>;
export type ModuleConfig = RawConfig["modules"][number];
export type LanguageConfig = RawConfig["languages"][number];

export interface Config {
  projectRoot: string;
  modules: ModuleConfig[];
  sourcePatterns: string[];
  source: LanguageConfig;
  /** 非源语言 */
  targets: LanguageConfig[];
  translation: RawConfig["translation"];
  apiKey: string;
  baseURL: string;
}

/** 只填充尚未设置的变量，与 Bun 自动加载 cwd 下 .env 的行为一致 */
function loadDotEnv(path: string) {
  if (!existsSync(path)) return;
  for (const line of readFileSync(path, "utf-8").split(/\r?\n/)) {
    const match = line.match(/^\s*(?:export\s+)?([\w.]+)\s*=\s*(.*?)\s*$/);
    if (!match) continue;
    const [, key, raw] = match;
    if (process.env[key] !== undefined) continue;
    process.env[key] = raw.replace(/^(['"])(.*)\1$/, "$2");
  }
}

export function loadConfig(path = join(TOOL_DIR, "config.yml")): Config {
  loadDotEnv(join(TOOL_DIR, ".env"));
  const raw = ConfigSchema.parse(parse(readFileSync(path, "utf-8")));

  const sources = raw.languages.filter((l) => l.is_source);
  if (sources.length !== 1) {
    throw new Error(`languages 中必须有且只有一个 is_source 语言，当前 ${sources.length} 个`);
  }

  return {
    projectRoot: resolve(TOOL_DIR, raw.project_root),
    modules: raw.modules,
    sourcePatterns: raw.source_patterns,
    source: sources[0],
    targets: raw.languages.filter((l) => !l.is_source),
    translation: raw.translation,
    apiKey: process.env.OPENAI_API_KEY ?? "",
    baseURL: process.env.OPENAI_BASE_URL || "https://api.openai.com/v1",
  };
}

export function stringsPath(config: Config, module: ModuleConfig, langCode: string): string {
  return join(config.projectRoot, module.res_path, langCode, "strings.xml");
}
