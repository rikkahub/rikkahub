#!/usr/bin/env python3
"""Phase 26.2 — backfill missing translated strings into existing locale files.

Each locale's translations live in scripts/i18n_translations/<locale>.xml as a
flat list of fully-formed <string name="...">...</string> lines (exactly as they
should appear in the resource file — entities, \\n, %1$s etc. already correct).

For each locale this appends ONLY the keys that locale is actually missing vs
values/strings.xml, inserted before </resources>. Idempotent: a key the file
already has is skipped. A missing key with no translation line is reported.

Run: python3 scripts/i18n_backfill.py <locale> [<locale> ...]
     e.g.  zh   zh-rTW   ja   ko-rKR   ru
"""
import re
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
BASE = os.path.join(RES, "values", "strings.xml")


def keys_of(path):
    return set(re.findall(r'<string name="([^"]+)"', open(path, encoding="utf-8").read()))


def load_translation_lines(locale):
    fn = os.path.join(HERE, "i18n_translations", f"{locale}.xml")
    out = {}
    for raw in open(fn, encoding="utf-8"):
        line = raw.rstrip("\n")
        m = re.search(r'<string name="([^"]+)"', line)
        if m:
            out[m.group(1)] = line.strip()
    return out


def backfill(locale):
    path = os.path.join(RES, f"values-{locale}", "strings.xml")
    src = open(path, encoding="utf-8").read()
    have = keys_of(path)
    base_keys = keys_of(BASE)
    translations = load_translation_lines(locale)
    missing = sorted(k for k in base_keys if k not in have)
    add, no_tr = [], []
    for k in missing:
        if k in translations:
            add.append("  " + translations[k])
        else:
            no_tr.append(k)
    if no_tr:
        print(f"  WARNING: {len(no_tr)} missing keys have no translation line:")
        for k in no_tr:
            print(f"    - {k}")
    if not add:
        print(f"  values-{locale}: nothing to add")
        return
    block = (
        "  <!-- Phase 26.2 — backfilled fork-added strings (2026-05-14) -->\n"
        + "\n".join(add)
        + "\n</resources>"
    )
    open(path, "w", encoding="utf-8").write(src.replace("</resources>", block))
    print(f"  values-{locale}: added {len(add)} strings")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    for loc in sys.argv[1:]:
        print(f"== {loc} ==")
        backfill(loc)
