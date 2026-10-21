import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import c from "highlight.js/lib/languages/c";
import cmake from "highlight.js/lib/languages/cmake";
import cpp from "highlight.js/lib/languages/cpp";
import csharp from "highlight.js/lib/languages/csharp";
import css from "highlight.js/lib/languages/css";
import dart from "highlight.js/lib/languages/dart";
import diff from "highlight.js/lib/languages/diff";
import dockerfile from "highlight.js/lib/languages/dockerfile";
import glsl from "highlight.js/lib/languages/glsl";
import go from "highlight.js/lib/languages/go";
import ini from "highlight.js/lib/languages/ini";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import kotlin from "highlight.js/lib/languages/kotlin";
import latex from "highlight.js/lib/languages/latex";
import lua from "highlight.js/lib/languages/lua";
import markdown from "highlight.js/lib/languages/markdown";
import php from "highlight.js/lib/languages/php";
import powershell from "highlight.js/lib/languages/powershell";
import properties from "highlight.js/lib/languages/properties";
import python from "highlight.js/lib/languages/python";
import ruby from "highlight.js/lib/languages/ruby";
import rust from "highlight.js/lib/languages/rust";
import sql from "highlight.js/lib/languages/sql";
import swift from "highlight.js/lib/languages/swift";
import typescript from "highlight.js/lib/languages/typescript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";

// 与 App 端 highlight 模块（highlight/src/main/java/me/rerere/highlight/languages）支持的语言保持一致
const LANGUAGES = {
  bash,
  c,
  cmake,
  cpp,
  csharp,
  css,
  dart,
  diff,
  dockerfile,
  glsl,
  go,
  ini,
  java,
  javascript,
  json,
  kotlin,
  latex,
  lua,
  markdown,
  php,
  powershell,
  properties,
  python,
  ruby,
  rust,
  sql,
  swift,
  typescript,
  xml,
  yaml,
};

for (const [name, language] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, language);
}

/** 返回 highlight.js 能识别的语言名（含别名），不支持时返回 null */
export function resolveHighlightLanguage(language: string): string | null {
  const normalized = language.trim().toLowerCase();
  if (!normalized || !hljs.getLanguage(normalized)) {
    return null;
  }
  return normalized;
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

const SPAN_OR_NEWLINE = /<span[^>]*>|<\/span>|\n/g;

/**
 * 把高亮后的 HTML 按行切开。块注释、多行字符串这类跨行的 span 会在行尾关闭、下一行重新打开，
 * 保证每一行都是独立完整的 HTML，方便逐行渲染行号和自动换行。
 */
function splitHtmlLines(html: string): string[] {
  const lines: string[] = [];
  const openTags: string[] = [];
  let current = "";
  let lastIndex = 0;

  for (const match of html.matchAll(SPAN_OR_NEWLINE)) {
    current += html.slice(lastIndex, match.index);
    lastIndex = match.index + match[0].length;

    if (match[0] === "\n") {
      lines.push(current + "</span>".repeat(openTags.length));
      current = openTags.join("");
    } else if (match[0] === "</span>") {
      openTags.pop();
      current += match[0];
    } else {
      openTags.push(match[0]);
      current += match[0];
    }
  }

  lines.push(current + html.slice(lastIndex));
  return lines;
}

/** 返回逐行的 HTML；language 为 null 时只做转义，不高亮 */
export function highlightToLines(code: string, language: string | null): string[] {
  if (!language) {
    return escapeHtml(code).split("\n");
  }

  try {
    return splitHtmlLines(hljs.highlight(code, { language, ignoreIllegals: true }).value);
  } catch {
    return escapeHtml(code).split("\n");
  }
}
