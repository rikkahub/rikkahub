/**
 * 调用真实 API 检查翻译质量，默认跳过。
 * 运行: I18N_LIVE=1 bun test test/translator.live.test.ts
 */
import { describe, expect, test } from "bun:test";
import { loadConfig } from "../src/config";
import { Translator } from "../src/translator";

const config = loadConfig();
const chinese = config.targets.find((l) => l.code === "values-zh")!;

describe.skipIf(!process.env.I18N_LIVE)("live translation", () => {
  const translator = new Translator(config);
  const translate = (entries: Record<string, string>) =>
    translator.translateBatch(new Map(Object.entries(entries)), chinese);

  test("prompt is translated as 提示词", async () => {
    const result = await translate({
      system_prompt: "System Prompt",
      prompt_template: "Prompt Template",
    });
    expect(result.system_prompt).toContain("提示词");
    expect(result.prompt_template).toContain("提示词");
  }, 60_000);

  test("placeholders and brand names are preserved", async () => {
    const result = await translate({
      welcome_user: "Welcome, %1$s!",
      items_count: "You have %d items",
      provider_openai: "OpenAI Provider",
    });
    expect(result.welcome_user).toContain("%1$s");
    expect(result.items_count).toContain("%d");
    expect(result.provider_openai).toContain("OpenAI");
  }, 60_000);

  test("response keys match the input", async () => {
    const entries = {
      setting_title: "Settings",
      cancel_button: "Cancel",
      confirm_button: "Confirm",
    };
    const result = await translate(entries);
    expect(Object.keys(result).sort()).toEqual(Object.keys(entries).sort());
  }, 60_000);
});
