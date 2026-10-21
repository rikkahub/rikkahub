#!/usr/bin/env bun
/** Android strings.xml 翻译管理 CLI */
import { defineCommand, runMain } from "citty";
import { relative } from "node:path";
import {
  loadConfig,
  stringsPath,
  type Config,
  type LanguageConfig,
  type ModuleConfig,
} from "./config";
import { findReferencedKeys, RESERVED_KEYS } from "./references";
import { StringsFile } from "./strings-xml";
import { createLimiter, errorMessage, Translator } from "./translator";
import { textToXml, validateXml } from "./validation";

// citty 不支持重复选项，多个值用逗号分隔
const moduleArg = {
  type: "string",
  alias: "m",
  valueHint: "name",
  description: "模块名（默认第一个模块）",
} as const;
const modulesArg = {
  type: "string",
  alias: "m",
  valueHint: "a,b",
  description: "模块名，逗号分隔（默认全部）",
} as const;
const langsArg = {
  type: "string",
  alias: "l",
  valueHint: "code,...",
  description: "目标语言代码，逗号分隔（默认所有目标语言）",
} as const;
const rawArg = { type: "boolean", description: "按原始 XML 写入，不转义 & < > 和引号" } as const;
const translateArgs = {
  concurrency: {
    type: "string",
    alias: "c",
    default: "8",
    valueHint: "n",
    description: "并发请求数",
  },
  retries: { type: "string", default: "3", valueHint: "n", description: "每批最大尝试次数" },
} as const;
const batchArgs = {
  module: modulesArg,
  lang: langsArg,
  ...translateArgs,
  "dry-run": { type: "boolean", description: "只统计待翻译条目，不调用翻译" },
} as const;

const add = defineCommand({
  meta: { name: "add", description: "添加/更新英文条目并翻译到所有语言" },
  args: {
    key: { type: "positional", required: true, description: "条目 key" },
    value: { type: "positional", required: true, description: "英文原文" },
    module: moduleArg,
    "skip-translate": { type: "boolean", description: "只写入英文，不翻译" },
    raw: rawArg,
    ...translateArgs,
  },
  async run({ args }) {
    const config = loadConfig();
    const module = pickModule(config, args.module);
    const xml = toXml(args.value, args.raw);

    const source = StringsFile.load(stringsPath(config, module, config.source.code));
    if (!source.exists) fail(`源语言文件不存在: ${source.path}`);
    const existed = source.has(args.key);
    source.set(args.key, xml);
    source.save();
    log(`${existed ? "更新" : "添加"} ${display(config, source.path)}: ${args.key} = ${xml}`);

    if (args["skip-translate"]) {
      if (existed) log("注意：已跳过翻译，其他语言仍是旧译文");
      return;
    }

    const translator = createTranslator(config);
    const limit = createLimiter(intOption(args.concurrency, "concurrency"));
    const retries = intOption(args.retries, "retries");
    const results = await Promise.all(
      config.targets.map(async (lang) => ({
        lang,
        result: await translator.translateEntries(new Map([[args.key, xml]]), lang, {
          limit,
          retries,
        }),
      })),
    );

    let failed = 0;
    for (const { lang, result } of results) {
      const translated = result.translations.get(args.key);
      if (translated === undefined) {
        console.error(`  ✗ ${lang.code}: ${result.failures.get(args.key)}`);
        failed++;
        continue;
      }
      const file = StringsFile.load(stringsPath(config, module, lang.code));
      file.set(args.key, translated);
      file.save();
      log(`  ✓ ${lang.code}: ${translated}`);
    }
    if (failed > 0) fail(`${failed} 种语言翻译失败，可运行 translate-missing 补全`);
  },
});

const set = defineCommand({
  meta: { name: "set", description: "手动设置某个语言的条目值" },
  args: {
    key: { type: "positional", required: true, description: "条目 key" },
    value: { type: "positional", required: true, description: "值" },
    lang: { type: "string", alias: "l", valueHint: "code", description: "语言代码（默认源语言）" },
    module: moduleArg,
    raw: rawArg,
  },
  run({ args }) {
    const config = loadConfig();
    const module = pickModule(config, args.module);
    const lang = pickLanguage(config, args.lang ?? config.source.code);
    const xml = toXml(args.value, args.raw);

    if (
      lang !== config.source &&
      !StringsFile.load(stringsPath(config, module, config.source.code)).has(args.key)
    ) {
      log(`注意：源语言中没有 ${args.key}`);
    }
    const file = StringsFile.load(stringsPath(config, module, lang.code));
    file.set(args.key, xml);
    file.save();
    log(`✓ ${display(config, file.path)}: ${args.key} = ${xml}`);
  },
});

const remove = defineCommand({
  meta: { name: "remove", description: "从所有语言中删除条目" },
  args: {
    keys: { type: "positional", required: true, description: "要删除的 key，可以有多个" },
    module: moduleArg,
  },
  run({ args }) {
    const config = loadConfig();
    const module = pickModule(config, args.module);
    const keys = args._;
    const removed = new Map<string, string[]>(keys.map((k) => [k, []]));
    for (const lang of [config.source, ...config.targets]) {
      const file = StringsFile.load(stringsPath(config, module, lang.code));
      for (const key of keys) if (file.delete(key)) removed.get(key)!.push(lang.code);
      file.save();
    }
    let missing = 0;
    for (const [key, langs] of removed) {
      if (langs.length === 0) {
        console.error(`✗ ${module.name} 中没有 ${key}`);
        missing++;
      } else {
        log(`✓ 已删除 ${key}（${langs.length} 个语言文件）`);
      }
    }
    if (missing > 0) process.exitCode = 1;
  },
});

const list = defineCommand({
  meta: { name: "list", description: "列出条目" },
  args: {
    module: moduleArg,
    missing: { type: "boolean", description: "只显示缺少译文的条目" },
    match: { type: "string", valueHint: "regex", description: "按 key 或原文过滤（忽略大小写）" },
  },
  run({ args }) {
    const config = loadConfig();
    const module = pickModule(config, args.module);
    const regex = compileRegex(args.match);
    const source = StringsFile.load(stringsPath(config, module, config.source.code));
    const targets = config.targets.map((lang) => ({
      lang,
      file: StringsFile.load(stringsPath(config, module, lang.code)),
    }));
    const translatable = source.translatableEntries();

    let shown = 0;
    for (const [key, value] of source.entries()) {
      if (regex && !regex.test(key) && !regex.test(value)) continue;
      const missing = translatable.has(key)
        ? targets.filter((t) => !t.file.has(key)).map((t) => t.lang.code)
        : [];
      if (args.missing && missing.length === 0) continue;
      shown++;
      const preview = value.length > 60 ? `${value.slice(0, 57)}...` : value;
      log(`${key.padEnd(40)} ${preview}`);
      if (missing.length > 0) log(`${"".padEnd(40)} 缺少: ${missing.join(", ")}`);
    }
    log(`\n${module.name}: 显示 ${shown} / ${source.entries().size} 条`);
  },
});

const translateMissing = defineCommand({
  meta: { name: "translate-missing", description: "批量翻译缺失的条目" },
  args: batchArgs,
  run({ args }) {
    return runBatch(loadConfig(), args, "缺失", (source, target) =>
      filterMap(source, (key) => !target.has(key)),
    );
  },
});

const retranslate = defineCommand({
  meta: {
    name: "retranslate",
    description: "重新翻译已有条目（例如更新术语表后），只覆盖已存在的译文",
  },
  args: {
    ...batchArgs,
    match: { type: "string", valueHint: "regex", description: "按英文原文选择条目（忽略大小写）" },
    key: {
      type: "string",
      valueHint: "a,b",
      description: "按 key 选择条目，逗号分隔，与 --match 取并集",
    },
  },
  run({ args }) {
    const regex = compileRegex(args.match);
    const keys = new Set(splitList(args.key));
    if (!regex && keys.size === 0) fail("请至少指定 --match 或 --key");
    // 只覆盖已有译文，缺失条目交给 translate-missing
    return runBatch(loadConfig(), args, "重新翻译", (source, target) =>
      filterMap(source, (key, value) => target.has(key) && (keys.has(key) || !!regex?.test(value))),
    );
  },
});

const unused = defineCommand({
  meta: { name: "unused", description: "检测代码中未引用的条目和只存在于译文中的条目" },
  args: {
    module: modulesArg,
    delete: { type: "boolean", description: "删除检测到的条目" },
  },
  async run({ args }) {
    const config = loadConfig();
    if (config.sourcePatterns.length === 0) fail("config.yml 中未配置 source_patterns");
    const referenced = await findReferencedKeys(config.projectRoot, config.sourcePatterns);
    let total = 0;

    for (const module of pickModules(config, splitList(args.module))) {
      const source = StringsFile.load(stringsPath(config, module, config.source.code));
      if (!source.exists) continue;
      const targets = config.targets.map((lang) =>
        StringsFile.load(stringsPath(config, module, lang.code)),
      );

      const unusedKeys = [...source.entries().keys()].filter(
        (key) => !referenced.has(key) && !RESERVED_KEYS.has(key),
      );
      if (unusedKeys.length > 0) {
        log(`[${module.name}] 未引用 ${unusedKeys.length} 条:`);
        for (const key of unusedKeys) log(`  ${key}`);
      }
      // 只存在于译文中、源语言已没有的条目
      for (const target of targets) {
        const orphans = [...target.entries().keys()].filter((key) => !source.has(key));
        if (orphans.length === 0) continue;
        log(`[${module.name}] ${display(config, target.path)} 多余 ${orphans.length} 条:`);
        for (const key of orphans) log(`  ${key}`);
        if (args.delete) for (const key of orphans) target.delete(key);
        total += orphans.length;
      }
      total += unusedKeys.length;

      if (args.delete) {
        for (const file of [source, ...targets]) {
          for (const key of unusedKeys) file.delete(key);
          file.save();
        }
      }
    }

    if (total === 0) log("没有发现未使用的条目");
    else if (args.delete) log(`✓ 已删除 ${total} 条`);
    else
      log(`\n共 ${total} 条，加 --delete 删除（动态拼接 key 引用的条目也会被列出，删除前请确认）`);
  },
});

const testConnection = defineCommand({
  meta: { name: "test-connection", description: "测试 AI 服务连接" },
  async run() {
    const config = loadConfig();
    log(`Base URL: ${config.baseURL}`);
    log(`Model: ${config.translation.model}`);
    try {
      const reply = await createTranslator(config).testConnection();
      log(`✓ 连接成功${reply ? `，响应: ${reply}` : ""}`);
    } catch (e) {
      fail(`✗ 连接失败: ${errorMessage(e)}`);
    }
  },
});

async function runBatch(
  config: Config,
  args: {
    module?: string;
    lang?: string;
    concurrency: string;
    retries: string;
    "dry-run"?: boolean;
  },
  action: string,
  select: (source: Map<string, string>, target: StringsFile) => Map<string, string>,
) {
  const modules = pickModules(config, splitList(args.module));
  const langs = pickTargets(config, splitList(args.lang));
  const concurrency = intOption(args.concurrency, "concurrency");
  const retries = intOption(args.retries, "retries");

  const jobs: {
    label: string;
    lang: LanguageConfig;
    target: StringsFile;
    selected: Map<string, string>;
  }[] = [];
  for (const module of modules) {
    const source = StringsFile.load(stringsPath(config, module, config.source.code));
    if (!source.exists) continue;
    const entries = source.translatableEntries();
    for (const lang of langs) {
      const target = StringsFile.load(stringsPath(config, module, lang.code));
      const selected = select(entries, target);
      if (selected.size === 0) continue;
      const label = `[${module.name}/${lang.code}]`;
      log(`${label} ${action} ${selected.size} 条`);
      jobs.push({ label, lang, target, selected });
    }
  }
  if (jobs.length === 0) return log("没有需要翻译的条目");
  if (args["dry-run"]) return;

  const translator = createTranslator(config);
  const limit = createLimiter(concurrency);
  const total = jobs.reduce((sum, job) => sum + job.selected.size, 0);
  let done = 0;
  const failures: string[] = [];

  await Promise.all(
    jobs.map(async ({ label, lang, target, selected }) => {
      const result = await translator.translateEntries(selected, lang, {
        limit,
        retries,
        onBatchDone(count) {
          done += count;
          progress(`翻译中 ${done}/${total}`);
        },
      });
      for (const [key, value] of result.translations) target.set(key, value);
      target.save();
      log(`${label} ✓ 写入 ${result.translations.size}/${selected.size} 条`);
      for (const [key, error] of result.failures) failures.push(`  ${label} ${key}: ${error}`);
    }),
  );

  if (failures.length > 0) {
    console.error(
      `\n${failures.length} 条翻译失败（未写入，可重新运行）：\n${failures.join("\n")}`,
    );
    process.exit(1);
  }
  log("完成！");
}

function createTranslator(config: Config): Translator {
  if (!config.apiKey) fail("未设置 OPENAI_API_KEY（可写在 tools/i18n/.env）");
  return new Translator(config);
}

/** 纯文本转义后写入；--raw 时原样写入，但必须是合法的 XML 片段 */
function toXml(value: string, raw: boolean | undefined): string {
  if (!raw) return textToXml(value);
  const error = validateXml(value);
  if (error) fail(error);
  return value;
}

function splitList(value: string | undefined): string[] {
  return (value ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function pickModule(config: Config, name: string | undefined): ModuleConfig {
  if (!name) return config.modules[0];
  return pickModules(config, [name])[0];
}

function pickModules(config: Config, names: string[]): ModuleConfig[] {
  if (names.length === 0) return config.modules;
  const unknown = names.filter((n) => !config.modules.some((m) => m.name === n));
  if (unknown.length > 0) {
    fail(
      `未找到模块 ${unknown.join(", ")}，可用模块：${config.modules.map((m) => m.name).join(", ")}`,
    );
  }
  return config.modules.filter((m) => names.includes(m.name));
}

function pickLanguage(config: Config, code: string): LanguageConfig {
  const lang = [config.source, ...config.targets].find((l) => l.code === code);
  if (!lang) fail(`未找到语言 ${code}，可用语言：${config.targets.map((l) => l.code).join(", ")}`);
  return lang;
}

function pickTargets(config: Config, codes: string[]): LanguageConfig[] {
  if (codes.length === 0) return config.targets;
  return codes.map((code) => {
    const lang = pickLanguage(config, code);
    if (lang === config.source) fail(`${code} 是源语言，不能作为翻译目标`);
    return lang;
  });
}

function filterMap(map: Map<string, string>, predicate: (key: string, value: string) => boolean) {
  return new Map([...map].filter(([k, v]) => predicate(k, v)));
}

function compileRegex(pattern: string | undefined): RegExp | undefined {
  if (!pattern) return undefined;
  try {
    return new RegExp(pattern, "i");
  } catch (e) {
    fail(`无效的正则表达式: ${errorMessage(e)}`);
  }
}

function intOption(value: string, name: string): number {
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1) fail(`--${name} 必须是正整数`);
  return n;
}

function display(config: Config, path: string): string {
  return relative(config.projectRoot, path);
}

let progressShown = false;

function progress(message: string) {
  if (!process.stdout.isTTY) return;
  process.stdout.write(`\r\x1b[K${message}`);
  progressShown = true;
}

function clearProgress() {
  if (!progressShown) return;
  process.stdout.write("\r\x1b[K");
  progressShown = false;
}

function log(message: string) {
  clearProgress();
  console.log(message);
}

function fail(message: string): never {
  clearProgress();
  console.error(message);
  process.exit(1);
}

await runMain(
  defineCommand({
    meta: { name: "i18n", description: "Android strings.xml 翻译管理" },
    subCommands: {
      add,
      set,
      remove,
      list,
      "translate-missing": translateMissing,
      retranslate,
      unused,
      "test-connection": testConnection,
    },
  }),
);
