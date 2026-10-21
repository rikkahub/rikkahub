import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { generateText, Output, type LanguageModel } from "ai";
import { z } from "zod";
import type { Config, LanguageConfig } from "./config";
import { normalizeTranslation, validateTranslation } from "./validation";

export interface TranslateResult {
  translations: Map<string, string>;
  /** key -> 最后一次的错误信息 */
  failures: Map<string, string>;
}

export type Limiter = <T>(task: () => Promise<T>) => Promise<T>;

export function createLimiter(concurrency: number): Limiter {
  let active = 0;
  const queue: (() => void)[] = [];
  return async (task) => {
    if (active >= concurrency) await new Promise<void>((resolve) => queue.push(resolve));
    active++;
    try {
      return await task();
    } finally {
      active--;
      queue.shift()?.();
    }
  };
}

const ResponseSchema = z.record(z.string(), z.string());

export class Translator {
  private readonly model: LanguageModel;

  constructor(private readonly config: Config) {
    if (!config.translation.structured_outputs) {
      // json_object 模式下 SDK 会对每次请求警告不支持 schema，prompt 已描述输出格式
      globalThis.AI_SDK_LOG_WARNINGS = false;
    }
    const provider = createOpenAICompatible({
      name: "i18n",
      baseURL: config.baseURL,
      apiKey: config.apiKey,
      supportsStructuredOutputs: config.translation.structured_outputs,
    });
    this.model = provider(config.translation.model);
  }

  async testConnection(): Promise<string> {
    const { text } = await generateText({
      model: this.model,
      prompt: "Reply exactly with OK to confirm the connection.",
      temperature: 0,
      maxOutputTokens: 16,
    });
    return text.trim();
  }

  buildPrompt(entries: Map<string, string>, language: LanguageConfig): string {
    let prompt = this.config.translation.prompt_template
      .replaceAll("{target_language}", () => language.name)
      .replaceAll("{source_strings}", () => JSON.stringify(Object.fromEntries(entries), null, 2));
    const terms = Object.entries(language.glossary);
    if (terms.length > 0) {
      prompt +=
        `\n\nGlossary for ${language.name} (mandatory, use these renderings ` +
        `consistently, inflecting for grammar where needed):\n` +
        terms.map(([en, tr]) => `- ${en} -> ${tr}`).join("\n");
    }
    return prompt;
  }

  /** 单次请求翻译一批条目，返回模型给出的原始结果 */
  async translateBatch(
    entries: Map<string, string>,
    language: LanguageConfig,
  ): Promise<Record<string, string>> {
    const { output } = await generateText({
      model: this.model,
      temperature: this.config.translation.temperature,
      output: Output.object({ schema: ResponseSchema }),
      prompt: this.buildPrompt(entries, language),
    });
    return output;
  }

  /**
   * 分批并发翻译，并校验每条译文；未通过的条目在下一轮重试。
   * limit 可在多个调用之间共享，以限制总并发数。
   */
  async translateEntries(
    entries: Map<string, string>,
    language: LanguageConfig,
    options: { limit: Limiter; retries: number; onBatchDone?: (count: number) => void },
  ): Promise<TranslateResult> {
    const translations = new Map<string, string>();
    const failures = new Map<string, string>();
    const keys = [...entries.keys()];
    const batchSize = this.config.translation.batch_size;

    const runBatch = async (batchKeys: string[]) => {
      const pending = new Map(batchKeys.map((k) => [k, entries.get(k)!]));
      for (let attempt = 0; attempt < options.retries && pending.size > 0; attempt++) {
        let result: Record<string, string>;
        try {
          result = await options.limit(() => this.translateBatch(pending, language));
        } catch (e) {
          for (const key of pending.keys()) failures.set(key, errorMessage(e));
          continue;
        }
        for (const [key, source] of pending) {
          const raw = result[key];
          if (typeof raw !== "string") {
            failures.set(key, "missing in response");
            continue;
          }
          const value = normalizeTranslation(raw);
          const error = validateTranslation(source, value);
          if (error) {
            failures.set(key, `${error}: ${JSON.stringify(value)}`);
            continue;
          }
          translations.set(key, value);
          failures.delete(key);
          pending.delete(key);
        }
      }
      options.onBatchDone?.(batchKeys.length);
    };

    const batches: string[][] = [];
    for (let i = 0; i < keys.length; i += batchSize) batches.push(keys.slice(i, i + batchSize));
    await Promise.all(batches.map(runBatch));
    return { translations, failures };
  }
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message.split("\n")[0] : String(e);
}
