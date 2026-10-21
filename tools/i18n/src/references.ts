import { Glob } from "bun";
import { join } from "node:path";

const REFERENCE_PATTERNS = [/\bR\.string\.(\w+)/g, /@string\/(\w+)/g];

/** 系统保留的条目，即使代码中没有引用也不视为未使用 */
export const RESERVED_KEYS = new Set(["app_name"]);

/** 扫描源码中引用的字符串 key */
export async function findReferencedKeys(
  projectRoot: string,
  patterns: string[],
): Promise<Set<string>> {
  const referenced = new Set<string>();
  for (const pattern of patterns) {
    for await (const file of new Glob(pattern).scan({ cwd: projectRoot, onlyFiles: true })) {
      if (file.includes("/build/")) continue;
      const content = await Bun.file(join(projectRoot, file)).text();
      for (const regex of REFERENCE_PATTERNS) {
        for (const match of content.matchAll(regex)) referenced.add(match[1]);
      }
    }
  }
  return referenced;
}
