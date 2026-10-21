import { describe, expect, test } from "bun:test";
import {
  escapeAndroidQuotes,
  extractPlaceholders,
  normalizeTranslation,
  textToXml,
  validateTranslation,
} from "../src/validation";

describe("validateTranslation", () => {
  test("extracts placeholders", () => {
    expect(extractPlaceholders("%1$s has %2$d items, %.1f%%")).toEqual(["%%", "%.1f", "%1$s", "%2$d"]);
    expect(extractPlaceholders("no placeholders")).toEqual([]);
  });

  test("allows reordered placeholders", () => {
    expect(validateTranslation("%1$s sent %2$d", "%2$d أرسل %1$s")).toBeNull();
  });

  test("rejects placeholder mismatch", () => {
    expect(validateTranslation("Welcome, %1$s!", "مرحبًا!")).not.toBeNull();
    expect(validateTranslation("%1$d%%", "%1$d")).not.toBeNull();
  });

  test("rejects line break mismatch", () => {
    expect(validateTranslation("a\\nb", "ab")).not.toBeNull();
    expect(validateTranslation("a\\nb", "أ\\nب")).toBeNull();
  });

  test("rejects empty translation", () => {
    expect(validateTranslation("Hello", "")).not.toBeNull();
    expect(validateTranslation("Hello", "   ")).not.toBeNull();
  });

  test("rejects markup mismatch", () => {
    expect(validateTranslation("<b>Bold</b> text", "粗体文本")).not.toBeNull();
    expect(validateTranslation("<b>Bold</b> text", "<b>粗体</b>文本")).toBeNull();
  });
});

describe("escaping", () => {
  test("escapes bare quotes", () => {
    expect(escapeAndroidQuotes("don't")).toBe("don\\'t");
    expect(escapeAndroidQuotes("don\\'t")).toBe("don\\'t");
    expect(escapeAndroidQuotes('say "hi"')).toBe('say \\"hi\\"');
  });

  test("leaves quotes inside tags alone", () => {
    expect(escapeAndroidQuotes('<xliff:g id="n">%s</xliff:g>\'s')).toBe('<xliff:g id="n">%s</xliff:g>\\\'s');
  });

  test("normalizes bare ampersands but keeps entities", () => {
    expect(normalizeTranslation("A & B &amp; C &#169;")).toBe("A &amp; B &amp; C &#169;");
  });

  test("converts plain text input to xml", () => {
    expect(textToXml("Tom & Jerry's <tag>")).toBe("Tom &amp; Jerry\\'s &lt;tag&gt;");
  });
});
