import { expect, test } from "bun:test";
import { loadConfig } from "../src/config";
import { Translator } from "../src/translator";

test("glossary is appended to the prompt", () => {
  const config = loadConfig();
  const translator = new Translator(config);
  const entries = new Map([["system_prompt", "System Prompt"]]);
  const lang = (code: string) => config.targets.find((l) => l.code === code)!;

  const arabic = translator.buildPrompt(entries, lang("values-ar"));
  expect(arabic).toContain("Glossary for Arabic");
  expect(arabic).toContain("مطالبة");
  expect(arabic).toContain('"system_prompt": "System Prompt"');

  expect(translator.buildPrompt(entries, lang("values-zh"))).toContain("提示词");
  // 未配置术语表的语言不追加术语段
  expect(translator.buildPrompt(entries, lang("values-ru"))).not.toContain("Glossary");
});
