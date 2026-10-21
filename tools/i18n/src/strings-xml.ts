/**
 * Android strings.xml 的读写。
 *
 * 文件被切分为文本片段和 <string> 元素，只改动被修改的元素，其余内容（注释、
 * <plurals>、<string-array>、空白和顺序）原样保留。值是元素内部的原始 XML
 * （不解码实体），因此 &amp;、\' 和内联标签都能往返不变。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

interface StringElement {
  name: string;
  /** 开始标签中 name 之外的原始属性串，例如 ` name="a" translatable="false"` */
  attrs: string;
  value: string;
  translatable: boolean;
  /** 未修改时原样输出 */
  raw?: string;
}

type Segment = string | StringElement;

const EMPTY_FILE = "<?xml version='1.0' encoding='UTF-8'?>\n<resources>\n</resources>\n";

// `<string` 后必须是空白、> 或 /，避免匹配到 <string-array>
const STRING_ELEMENT = /<string(?=[\s>/])([^>]*?)(?:\/>|>([\s\S]*?)<\/string\s*>)/g;
const COMMENT = /<!--[\s\S]*?-->/g;

export class StringsFile {
  private segments: Segment[];
  private elements = new Map<string, StringElement>();
  private indent: string;
  private dirty = false;

  private constructor(
    readonly path: string,
    content: string,
  ) {
    this.segments = parseSegments(content);
    for (const segment of this.segments) {
      if (typeof segment !== "string") this.elements.set(segment.name, segment);
    }
    this.indent = content.match(/\n([ \t]+)<string[\s>/]/)?.[1] ?? "  ";
  }

  static load(path: string): StringsFile {
    return new StringsFile(path, existsSync(path) ? readFileSync(path, "utf-8") : EMPTY_FILE);
  }

  get exists(): boolean {
    return existsSync(this.path);
  }

  /** 所有条目 {name: value}，保持文件中的顺序 */
  entries(): Map<string, string> {
    return new Map([...this.elements].map(([name, e]) => [name, e.value]));
  }

  /** 需要翻译的条目（排除 translatable="false"） */
  translatableEntries(): Map<string, string> {
    return new Map(
      [...this.elements].filter(([, e]) => e.translatable).map(([name, e]) => [name, e.value]),
    );
  }

  has(name: string): boolean {
    return this.elements.has(name);
  }

  get(name: string): string | undefined {
    return this.elements.get(name)?.value;
  }

  /** 更新已有条目，不存在则追加到末尾 */
  set(name: string, value: string): void {
    const existing = this.elements.get(name);
    if (existing) {
      if (existing.value === value) return;
      existing.value = value;
      existing.raw = undefined;
    } else {
      this.append({ name, attrs: ` name="${name}"`, value, translatable: true });
    }
    this.dirty = true;
  }

  delete(name: string): boolean {
    const element = this.elements.get(name);
    if (!element) return false;
    const index = this.segments.indexOf(element);
    const before = this.segments[index - 1];
    const after = this.segments[index + 1];
    // 元素独占一行时连同缩进和换行一起删除
    if (typeof before === "string" && typeof after === "string") {
      const lineOnly = /\n[ \t]*$/.test(before) && /^\r?\n/.test(after);
      const merged = lineOnly
        ? before.replace(/[ \t]*$/, "") + after.replace(/^\r?\n/, "")
        : before + after;
      // 合并相邻文本，后续删除相邻元素时才能判断它是否独占一行
      this.segments.splice(index - 1, 3, merged);
    } else {
      this.segments.splice(index, 1);
    }
    this.elements.delete(name);
    this.dirty = true;
    return true;
  }

  serialize(): string {
    return this.segments
      .map((s) => (typeof s === "string" ? s : (s.raw ?? `<string${s.attrs}>${s.value}</string>`)))
      .join("");
  }

  /** 有改动时写盘，返回是否写入 */
  save(): boolean {
    if (!this.dirty) return false;
    mkdirSync(dirname(this.path), { recursive: true });
    writeFileSync(this.path, this.serialize());
    this.dirty = false;
    return true;
  }

  private append(element: StringElement): void {
    const last = this.segments.length - 1;
    const tail = this.segments[last];
    const close = typeof tail === "string" ? tail.lastIndexOf("</resources>") : -1;
    if (typeof tail !== "string" || close < 0) {
      throw new Error(`${this.path}: 找不到 </resources>`);
    }
    let head = tail.slice(0, close).replace(/[ \t]*$/, "");
    if (!head.endsWith("\n")) head += "\n";
    this.segments.splice(last, 1, head + this.indent, element, "\n" + tail.slice(close));
    this.elements.set(element.name, element);
  }
}

function parseSegments(content: string): Segment[] {
  // 注释替换为等长空白，使下标与原文对齐且不会匹配到注释里的 <string>
  const masked = content.replace(COMMENT, (c) => " ".repeat(c.length));
  const segments: Segment[] = [];
  let cursor = 0;
  for (const match of masked.matchAll(STRING_ELEMENT)) {
    const attrs = match[1];
    const name = attrs.match(/\bname\s*=\s*"([^"]*)"/)?.[1];
    if (!name) continue;
    const start = match.index;
    const end = start + match[0].length;
    const valueStart = start + "<string".length + attrs.length + 1;
    segments.push(content.slice(cursor, start));
    segments.push({
      name,
      attrs: attrs.replace(/\s*\/$/, ""),
      value: match[2] === undefined ? "" : content.slice(valueStart, valueStart + match[2].length),
      translatable: !/\btranslatable\s*=\s*"false"/.test(attrs),
      raw: content.slice(start, end),
    });
    cursor = end;
  }
  segments.push(content.slice(cursor));
  return segments;
}
