/** 译文校验与规范化。值均为 strings.xml 中 <string> 内部的原始 XML。 */
import { XMLValidator } from "fast-xml-parser";

// Android/Java 格式符，例如 %s、%d、%1$s、%.2f、%%
const PLACEHOLDER = /%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]/g;
const TAG = /<[^>]+>/g;
const UNESCAPED_QUOTE = /(?<!\\)(['"])/g;
const BARE_AMPERSAND = /&(?!(?:[a-zA-Z]+|#\d+|#x[0-9a-fA-F]+);)/g;
// XMLValidator 不检查实体是否已定义，aapt 只认 XML 预定义实体
const UNKNOWN_ENTITY = /&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)[a-zA-Z]+;/;

/** 值不能作为 <string> 内容时返回错误信息 */
export function validateXml(value: string): string | null {
  const result = XMLValidator.validate(`<string>${value}</string>`);
  if (result !== true) return `invalid XML: ${result.err.msg}`;
  const entity = value.match(UNKNOWN_ENTITY);
  if (entity) return `invalid XML: unknown entity ${entity[0]}`;
  return null;
}

/** 返回排序后的格式符，允许译文调整顺序 */
export function extractPlaceholders(value: string): string[] {
  return [...value.matchAll(PLACEHOLDER)].map((m) => m[0]).sort();
}

function extractTags(value: string): string[] {
  return [...value.matchAll(TAG)].map((m) => m[0]).sort();
}

/** 只处理标签之外的文本，避免改动标签属性里的引号 */
function mapText(value: string, transform: (text: string) => string): string {
  return value
    .split(/(<[^>]+>)/)
    .map((part, i) => (i % 2 === 1 ? part : transform(part)))
    .join("");
}

/** 转义裸 ' 和 "，aapt 会报错或静默丢弃它们 */
export function escapeAndroidQuotes(value: string): string {
  return mapText(value, (text) => text.replace(UNESCAPED_QUOTE, "\\$1"));
}

/** 把用户输入的纯文本转成可放进 <string> 的内容 */
export function textToXml(value: string): string {
  return escapeAndroidQuotes(
    value.replace(BARE_AMPERSAND, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"),
  );
}

/** 修正模型输出中常见的转义问题 */
export function normalizeTranslation(value: string): string {
  return escapeAndroidQuotes(mapText(value, (text) => text.replace(BARE_AMPERSAND, "&amp;")));
}

/** 译文不能安全替换原文时返回错误信息 */
export function validateTranslation(source: string, translated: string): string | null {
  if (!translated.trim()) return "empty translation";
  const xmlError = validateXml(translated);
  if (xmlError) return xmlError;
  const srcPh = extractPlaceholders(source);
  const dstPh = extractPlaceholders(translated);
  if (srcPh.join() !== dstPh.join()) {
    return `placeholder mismatch: [${srcPh}] vs [${dstPh}]`;
  }
  if (source.split("\\n").length !== translated.split("\\n").length) {
    return "line break count mismatch";
  }
  if (extractTags(source).join() !== extractTags(translated).join()) {
    return "markup mismatch";
  }
  return null;
}
