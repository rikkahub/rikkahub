import { describe, expect, test } from "bun:test";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Glob } from "bun";
import { loadConfig } from "../src/config";
import { StringsFile } from "../src/strings-xml";

const SAMPLE = `<?xml version='1.0' encoding='UTF-8'?>
<resources>
    <!-- <string name="commented">x</string> -->
    <string name="a">A &amp; B</string>
    <string name="b" translatable="false">RikkaHub</string>
    <string-array name="arr">
        <item>one</item>
    </string-array>
    <string name="empty"/>
    <string name="c">Don\\'t</string>
</resources>
`;

function sample(content = SAMPLE): StringsFile {
  const path = join(mkdtempSync(join(tmpdir(), "i18n-")), "strings.xml");
  writeFileSync(path, content);
  return StringsFile.load(path);
}

describe("StringsFile", () => {
  test("parses string elements only", () => {
    const file = sample();
    expect([...file.entries()]).toEqual([
      ["a", "A &amp; B"],
      ["b", "RikkaHub"],
      ["empty", ""],
      ["c", "Don\\'t"],
    ]);
    expect([...file.translatableEntries().keys()]).toEqual(["a", "empty", "c"]);
  });

  test("round-trips unchanged content", () => {
    expect(sample().serialize()).toBe(SAMPLE);
  });

  test("updates in place, appends and deletes", () => {
    const file = sample();
    file.set("a", "Changed");
    file.set("empty", "Now set");
    file.set("d", "New");
    file.delete("c");
    file.save();
    expect(readFileSync(file.path, "utf-8")).toBe(`<?xml version='1.0' encoding='UTF-8'?>
<resources>
    <!-- <string name="commented">x</string> -->
    <string name="a">Changed</string>
    <string name="b" translatable="false">RikkaHub</string>
    <string-array name="arr">
        <item>one</item>
    </string-array>
    <string name="empty">Now set</string>
    <string name="d">New</string>
</resources>
`);
  });

  test("deleting appended entries restores the original", () => {
    const file = sample();
    file.set("x", "X");
    file.set("y", "Y");
    const appended = sample(file.serialize());
    appended.delete("x");
    appended.delete("y");
    expect(appended.serialize()).toBe(SAMPLE);
  });

  test("creates a missing file", () => {
    const path = join(mkdtempSync(join(tmpdir(), "i18n-")), "values-xx", "strings.xml");
    const file = StringsFile.load(path);
    expect(file.exists).toBe(false);
    file.set("a", "A");
    file.set("b", "B");
    file.save();
    expect(readFileSync(path, "utf-8")).toBe(
      `<?xml version='1.0' encoding='UTF-8'?>\n<resources>\n  <string name="a">A</string>\n  <string name="b">B</string>\n</resources>\n`,
    );
  });

  test("round-trips every strings.xml in the project", async () => {
    const config = loadConfig();
    let count = 0;
    for (const module of config.modules) {
      const root = join(config.projectRoot, module.res_path);
      for await (const file of new Glob("values*/strings.xml").scan({ cwd: root })) {
        const path = join(root, file);
        expect(StringsFile.load(path).serialize()).toBe(readFileSync(path, "utf-8"));
        count++;
      }
    }
    expect(count).toBeGreaterThan(0);
  });
});
