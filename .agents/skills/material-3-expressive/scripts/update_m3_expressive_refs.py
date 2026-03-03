#!/usr/bin/env python3
"""Update Material 3 Expressive references from m3.material.io.

- Refresh component spec token files (with resolved values).
- Refresh foundation token files (color, motion, shape, typography, state).
- Refresh guidance files from component/style overview content JSON.
"""

from __future__ import annotations

import argparse
import datetime
import html
import json
import re
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Set, Tuple

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[3]
REFS = ROOT / "skills" / "material-3-expressive" / "references"

SPEC_COMPONENTS = [
    ("App Bars", "https://m3.material.io/components/app-bars/specs", REFS / "m3-app-bars-specs-tokens.md"),
    ("Buttons", "https://m3.material.io/components/buttons/specs", REFS / "m3-buttons-specs-tokens.md"),
    ("Button Groups", "https://m3.material.io/components/button-groups/specs", REFS / "m3-button-groups-specs-tokens.md"),
    ("Extended FAB", "https://m3.material.io/components/extended-fab/specs", REFS / "m3-extended-fab-specs-tokens.md"),
    ("FAB Menu", "https://m3.material.io/components/fab-menu/specs", REFS / "m3-fab-menu-specs-tokens.md"),
    ("FABs", "https://m3.material.io/components/floating-action-button/specs", REFS / "m3-fabs-specs-tokens.md"),
    ("Icon Buttons", "https://m3.material.io/components/icon-buttons/specs", REFS / "m3-icon-buttons-specs-tokens.md"),
    ("Split Button", "https://m3.material.io/components/split-button/specs", REFS / "m3-split-button-specs-tokens.md"),
    ("Carousel", "https://m3.material.io/components/carousel/specs", REFS / "m3-carousel-specs-tokens.md"),
    ("Loading Indicator", "https://m3.material.io/components/loading-indicator/specs", REFS / "m3-loading-indicator-specs-tokens.md"),
    ("Progress Indicators", "https://m3.material.io/components/progress-indicators/specs", REFS / "m3-progress-indicators-specs-tokens.md"),
    ("Navigation Bar", "https://m3.material.io/components/navigation-bar/specs", REFS / "m3-navigation-bar-specs-tokens.md"),
    ("Navigation Rail", "https://m3.material.io/components/navigation-rail/specs", REFS / "m3-navigation-rail-specs-tokens.md"),
    ("Toolbars", "https://m3.material.io/components/toolbars/specs", REFS / "m3-toolbars-specs-tokens.md"),
    ("Bottom Sheets", "https://m3.material.io/components/bottom-sheets/specs", REFS / "m3-bottom-sheets-specs-tokens.md"),
    ("Side Sheets", "https://m3.material.io/components/side-sheets/specs", REFS / "m3-side-sheets-specs-tokens.md"),
    ("Dialogs", "https://m3.material.io/components/dialogs/specs", REFS / "m3-dialogs-specs-tokens.md"),
]

SPECIAL_SPECS = [
    ("Elevation", "https://m3.material.io/styles/elevation/specs", REFS / "m3-elevation-specs-tokens.md", r"/ELEVATION\.[^/]+\.json"),
]

GUIDELINES = [
    ("App Bars (M3 Expressive)", ["https://m3.material.io/components/app-bars/overview"], REFS / "m3-app-bars.md"),
    ("Buttons (M3 Expressive)", ["https://m3.material.io/components/buttons/overview"], REFS / "m3-buttons.md"),
    ("Button Groups (M3 Expressive)", ["https://m3.material.io/components/button-groups/overview"], REFS / "m3-button-groups.md"),
    ("Split Button (M3 Expressive)", ["https://m3.material.io/components/split-button/overview"], REFS / "m3-split-button.md"),
    ("Icon Buttons (M3 Expressive)", ["https://m3.material.io/components/icon-buttons/overview"], REFS / "m3-icon-buttons.md"),
    ("Floating Action Button (M3 Expressive)", ["https://m3.material.io/components/floating-action-button/overview"], REFS / "m3-fabs.md"),
    ("Extended FAB (M3 Expressive)", ["https://m3.material.io/components/extended-fab/overview"], REFS / "m3-extended-fab.md"),
    ("FAB Menu (M3 Expressive)", ["https://m3.material.io/components/fab-menu/overview"], REFS / "m3-fab-menu.md"),
    ("Carousel (M3 Expressive)", ["https://m3.material.io/components/carousel/overview"], REFS / "m3-carousel.md"),
    ("Loading Indicator (M3 Expressive)", ["https://m3.material.io/components/loading-indicator/overview"], REFS / "m3-loading-indicator.md"),
    ("Progress Indicators (M3 Expressive)", ["https://m3.material.io/components/progress-indicators/overview"], REFS / "m3-progress-indicators.md"),
    ("Navigation Bar (M3 Expressive)", ["https://m3.material.io/components/navigation-bar/overview"], REFS / "m3-navigation-bar.md"),
    ("Navigation Rail (M3 Expressive)", ["https://m3.material.io/components/navigation-rail/overview"], REFS / "m3-navigation-rail.md"),
    ("Toolbars (M3 Expressive)", ["https://m3.material.io/components/toolbars/overview"], REFS / "m3-toolbars.md"),
    ("Sheets (M3 Expressive)", [
        "https://m3.material.io/components/bottom-sheets/overview",
        "https://m3.material.io/components/side-sheets/overview",
    ], REFS / "m3-sheets.md"),
    ("Dialogs (M3 Expressive)", ["https://m3.material.io/components/dialogs/overview"], REFS / "m3-dialogs.md"),
    ("Elevation (M3 Expressive)", ["https://m3.material.io/styles/elevation/overview"], REFS / "m3-elevation.md"),
    ("Color System (M3 Expressive)", ["https://m3.material.io/styles/color/system/overview"], REFS / "m3-color-system.md"),
    ("Typography (M3 Expressive)", ["https://m3.material.io/styles/typography/overview"], REFS / "m3-typography.md"),
    ("Typography Fonts (M3 Expressive)", ["https://m3.material.io/styles/typography/fonts"], REFS / "m3-typography-fonts.md"),
    ("Typography Type Scale (M3 Expressive)", ["https://m3.material.io/styles/typography/type-scale-tokens"], REFS / "m3-typography-type-scale-tokens.md"),
    ("Motion Physics System (M3 Expressive)", ["https://m3.material.io/styles/motion/overview/how-it-works"], REFS / "m3-motion-physics.md"),
    ("Motion Physics Specs (M3 Expressive)", ["https://m3.material.io/styles/motion/overview/specs"], REFS / "m3-motion-specs.md"),
    ("Shape (M3 Expressive)", ["https://m3.material.io/styles/shape"], REFS / "m3-shape.md"),
    ("Shape Corner Radius Scale (M3 Expressive)", ["https://m3.material.io/styles/shape/corner-radius-scale"], REFS / "m3-shape-corner-radius-scale.md"),
    ("Shape Morph (M3 Expressive)", ["https://m3.material.io/styles/shape/morph"], REFS / "m3-shape-morph.md"),
]

FOUNDATION_FILES = {
    "color": REFS / "m3-color-foundation-tokens.md",
    "typography": REFS / "m3-typography-foundation-tokens.md",
    "motion": REFS / "m3-motion-foundation-tokens.md",
    "shape": REFS / "m3-shape-foundation-tokens.md",
    "state": REFS / "m3-state-foundation-tokens.md",
}

NON_ASCII_MAP = {
    "\u2013": "-",
    "\u2014": "-",
    "\u2018": "'",
    "\u2019": "'",
    "\u201c": "\"",
    "\u201d": "\"",
    "\u2026": "...",
    "\u00a0": " ",
}

FILTER_PATTERNS = [
    r"Availability & resources",
    r"\bMore on\b",
    r"View baseline tokens",
    r"Tokens & specs",
    r"\bAnatomy\b",
    r"M3 Expressive update",
]


class FetchError(RuntimeError):
    pass


def normalize_ascii(text: str) -> str:
    for k, v in NON_ASCII_MAP.items():
        text = text.replace(k, v)
    # Remove any remaining non-ascii characters.
    text = text.encode("ascii", errors="ignore").decode("ascii")
    return text


def sanitize_line(text: str) -> str:
    text = normalize_ascii(text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def collect_resources(page, url: str) -> Set[str]:
    resources: Set[str] = set()

    def on_response(resp):
        resources.add(resp.url)

    page.on("response", on_response)
    page.goto(url, wait_until="networkidle", timeout=90_000)
    page.wait_for_timeout(1500)
    page.remove_listener("response", on_response)
    return resources


def filter_urls(urls: Iterable[str], pattern: str) -> List[str]:
    regex = re.compile(pattern)
    return sorted({u for u in urls if regex.search(u)})


def pick_latest(urls: Sequence[str]) -> str:
    if not urls:
        raise FetchError("No candidate URLs found")
    return sorted(urls)[-1]


def load_json(url: str) -> dict:
    with urllib_request(url) as f:
        return json.load(f)


def urllib_request(url: str):
    import urllib.request

    return urllib.request.urlopen(url)


def extract_list_items(html_value: str) -> List[str]:
    html_value = re.sub(r"<app-froala-tooltip-element[^>]*>(.*?)</app-froala-tooltip-element>", r"\1", html_value)
    items = re.findall(r"<li>(.*?)</li>", html_value, flags=re.DOTALL)
    results: List[str] = []
    for item in items:
        text = re.sub(r"<[^>]+>", " ", item)
        text = html.unescape(text)
        text = sanitize_line(text)
        if text:
            results.append(text)
    if results:
        return results
    # Fallback: strip all tags to a single line.
    text = re.sub(r"<[^>]+>", " ", html_value)
    text = html.unescape(text)
    text = sanitize_line(text)
    return [text] if text else []


def extract_guidance(content_json: dict) -> List[str]:
    items: List[str] = []
    for section in content_json.get("sections", []):
        for block in section.get("contentBlocks", []):
            for chunk in block.get("contentChunks", []):
                html_value = chunk.get("htmlValue")
                if html_value:
                    items.extend(extract_list_items(html_value))
    # Deduplicate preserving order
    seen: Set[str] = set()
    deduped: List[str] = []
    for item in items:
        if item and item not in seen:
            deduped.append(item)
            seen.add(item)
    return deduped


def should_drop_guidance(text: str) -> bool:
    if len(text.split()) < 3:
        return True
    if re.search(r"-{3,}|\|{3,}", text):
        return True
    for pattern in FILTER_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE):
            return True
    return False


def filter_guidance_items(items: Sequence[str]) -> List[str]:
    filtered: List[str] = []
    for item in items:
        cleaned = sanitize_line(item)
        if not cleaned:
            continue
        if should_drop_guidance(cleaned):
            continue
        filtered.append(cleaned)
    return filtered


def write_guidelines(title: str, source_urls: Sequence[str], out_path: Path, bullets: Sequence[str]) -> None:
    lines: List[str] = [f"# {title}", ""]
    if len(source_urls) == 1:
        lines += ["Source URL:", source_urls[0]]
    else:
        lines += ["Source URLs:"] + [f"- {u}" for u in source_urls]
    lines += [f"Generated: {datetime.date.today().isoformat()}"]
    lines += ["", "Extracted guidance"]
    if bullets:
        lines += [f"- {b}" for b in bullets]
    else:
        lines.append("- (No bullet content found)")
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# Token parsing helpers

def fmt_number(n):
    try:
        if isinstance(n, float) and n.is_integer():
            return str(int(n))
    except Exception:
        pass
    return str(n)


def format_length(length_obj):
    unit = length_obj.get("unit", "")
    unit_map = {"DIPS": "dp", "POINTS": "pt", "PIXELS": "px", "PERCENT": "%"}
    unit = unit_map.get(unit, unit.lower())
    val = length_obj.get("value")
    if val is None:
        val = 0
    if isinstance(val, float) and val.is_integer():
        val = int(val)
    return f"{val}{unit}"


def format_color(color_obj):
    r = round(color_obj.get("red", 0) * 255)
    g = round(color_obj.get("green", 0) * 255)
    b = round(color_obj.get("blue", 0) * 255)
    a = color_obj.get("alpha", 1)
    if a >= 1:
        return f"#{r:02X}{g:02X}{b:02X}"
    aa = round(a * 255)
    return f"#{r:02X}{g:02X}{b:02X}{aa:02X}"


def format_cubic_bezier(cb):
    parts = []
    for key in ["x0", "y0", "x1", "y1"]:
        if key in cb:
            parts.append(f"{key}={cb[key]}")
    return f"cubic-bezier({', '.join(parts)})"


def token_group_key(token_name: str) -> str:
    parts = token_name.split(".")
    if len(parts) >= 4:
        return ".".join(parts[:4])
    return token_name


def group_token_names(token_names: Sequence[str]) -> Dict[str, List[str]]:
    grouped: Dict[str, List[str]] = {}
    for name in token_names:
        key = token_group_key(name)
        grouped.setdefault(key, []).append(name)
    for key in grouped:
        grouped[key].sort()
    return dict(sorted(grouped.items(), key=lambda kv: kv[0]))


def select_value(values, default_tags: Set[str]):
    values = [v for v in values if not v.get("undefined")]
    if not values:
        return None

    def score(v):
        try:
            return int(v.get("specificityScore", 0))
        except Exception:
            return 0

    defaults = [v for v in values if v.get("contextTags") and all(t in default_tags for t in v.get("contextTags", []))]
    if defaults:
        return sorted(defaults, key=score, reverse=True)[0]

    no_ctx = [v for v in values if not v.get("contextTags")]
    if no_ctx:
        return sorted(no_ctx, key=score, reverse=True)[0]

    return sorted(values, key=score, reverse=True)[0]


def build_maps(json_urls: Sequence[str]) -> Tuple[Dict[str, str], Dict[str, List[dict]], Set[str]]:
    tokens: Dict[str, str] = {}
    values_by_id: Dict[str, List[dict]] = {}
    default_tags: Set[str] = set()

    for url in json_urls:
        data = load_json(url)
        system = data.get("system", {})
        for t in system.get("tokens", []):
            token_id = t["name"].split("/tokens/")[1]
            token_name = t.get("tokenName")
            if token_name and token_name not in tokens:
                tokens[token_name] = token_id
        for v in system.get("values", []):
            name = v.get("name", "")
            if "/tokens/" not in name:
                continue
            token_id = name.split("/tokens/")[1].split("/")[0]
            values_by_id.setdefault(token_id, []).append(v)
        for group in system.get("contextTagGroups", []):
            default_tags.add(group.get("defaultTag"))
    return tokens, values_by_id, default_tags


def build_resolver(tokens: Dict[str, str], values_by_id: Dict[str, List[dict]], default_tags: Set[str]):
    from functools import lru_cache

    @lru_cache(maxsize=None)
    def resolve(token_name: str, depth: int = 0):
        if depth > 10:
            return None
        token_id = tokens.get(token_name)
        if not token_id:
            return None
        vals = values_by_id.get(token_id, [])
        if not vals:
            return None
        v = select_value(vals, default_tags)
        if not v:
            return None
        if "tokenName" in v:
            return resolve(v["tokenName"], depth + 1) or v["tokenName"]
        if "customComposite" in v:
            props = v["customComposite"].get("properties", {})
            parts = []
            for key in sorted(props.keys()):
                token_ref = props[key].get("tokenName")
                if token_ref:
                    parts.append(f"{key}={resolve(token_ref, depth+1) or token_ref}")
            return f"composite({', '.join(parts)})" if parts else "composite"
        if "durationMs" in v:
            return f"{v['durationMs']}ms"
        if "cubicBezier" in v:
            return format_cubic_bezier(v["cubicBezier"])
        if "motionPath" in v:
            return v["motionPath"]
        if "svgPath" in v:
            return v["svgPath"]
        if "color" in v:
            return format_color(v["color"])
        if "length" in v:
            return format_length(v["length"])
        if "opacity" in v:
            return fmt_number(v["opacity"])
        if "elevation" in v:
            return format_length(v["elevation"])
        if "numeric" in v:
            return fmt_number(v["numeric"])
        if "fontSize" in v:
            return format_length(v["fontSize"])
        if "lineHeight" in v:
            return format_length(v["lineHeight"])
        if "fontTracking" in v:
            return format_length(v["fontTracking"])
        if "fontWeight" in v:
            return fmt_number(v["fontWeight"])
        if "fontNames" in v:
            return ",".join(v["fontNames"].get("values", []))
        if "axisValue" in v:
            ax = v["axisValue"]
            return f"{ax.get('tag')}={ax.get('value')}"
        if "textTransform" in v:
            return v["textTransform"]
        if "shape" in v:
            return v["shape"].get("family") or str(v["shape"])
        if "type" in v:
            t = v["type"]
            parts = []
            if "fontNameTokenName" in t:
                parts.append(f"font={resolve(t['fontNameTokenName'], depth+1) or t['fontNameTokenName']}")
            if "fontWeightTokenName" in t:
                parts.append(f"weight={resolve(t['fontWeightTokenName'], depth+1) or t['fontWeightTokenName']}")
            if "fontSizeTokenName" in t:
                parts.append(f"size={resolve(t['fontSizeTokenName'], depth+1) or t['fontSizeTokenName']}")
            if "fontTrackingTokenName" in t:
                parts.append(f"tracking={resolve(t['fontTrackingTokenName'], depth+1) or t['fontTrackingTokenName']}")
            if "lineHeightTokenName" in t:
                parts.append(f"lineHeight={resolve(t['lineHeightTokenName'], depth+1) or t['lineHeightTokenName']}")
            return f"type({', '.join(parts)})" if parts else str(t)
        return None

    return resolve


def write_tokens(title: str, source_urls: Sequence[str], out_path: Path, token_names: Sequence[str], resolver) -> None:
    lines: List[str] = [f"# {title}", ""]
    if len(source_urls) == 1:
        lines += ["Source URL:", source_urls[0]]
    else:
        lines += ["Source URLs:"] + [f"- {u}" for u in source_urls]
    lines += ["", "Extracted design tokens"]

    grouped = group_token_names(token_names)
    lines += ["", "Token group index"]
    if grouped:
        lines += [f"- {group}" for group in grouped.keys()]
    else:
        lines.append("- (No token groups found)")

    for group, names in grouped.items():
        lines += ["", f"## {group}"]
        for token_name in names:
            value = resolver(token_name)
            if value:
                lines.append(f"- {token_name}: {value}")
            else:
                lines.append(f"- {token_name}: (unresolved)")
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def update_specs(playwright) -> List[str]:
    browser = playwright.chromium.launch()
    page = browser.new_page()
    updated: List[str] = []

    # Component spec token tables
    for title, spec_url, out_path in SPEC_COMPONENTS:
        resources = collect_resources(page, spec_url)
        token_urls = filter_urls(resources, r"/TOKEN_TABLE\.[^/]+\.json")
        if not token_urls:
            raise FetchError(f"No token tables found for {spec_url}")
        tokens, values_by_id, default_tags = build_maps(token_urls)
        resolve = build_resolver(tokens, values_by_id, default_tags)
        write_tokens(f"{title} Specs Tokens (M3 Expressive)", [spec_url], out_path, list(tokens.keys()), resolve)
        updated.append(str(out_path))

    # Special specs (elevation)
    for title, spec_url, out_path, pattern in SPECIAL_SPECS:
        resources = collect_resources(page, spec_url)
        urls = filter_urls(resources, pattern)
        if not urls:
            print(f"WARNING: No matching resources found for {spec_url} ({pattern}); skipping.", file=sys.stderr)
            continue
        tokens, values_by_id, default_tags = build_maps(urls)
        resolve = build_resolver(tokens, values_by_id, default_tags)
        write_tokens(f"{title} Tokens (M3 Expressive)", [spec_url], out_path, list(tokens.keys()), resolve)
        updated.append(str(out_path))

    browser.close()
    return updated


def update_foundations(playwright) -> List[str]:
    browser = playwright.chromium.launch()
    page = browser.new_page()
    updated: List[str] = []

    # Motion/shape/state foundation (used for fallback base)
    resources = collect_resources(page, "https://m3.material.io/styles/motion/overview/specs")
    unspec_urls = filter_urls(resources, r"/TOKEN_TYPE_UNSPECIFIED\.[^/]+\.json")
    unspec_url = pick_latest(unspec_urls)

    # Color foundation
    resources = collect_resources(page, "https://m3.material.io/styles/color/system/overview")
    color_urls = filter_urls(resources, r"/COLOR\.[^/]+\.json")
    if color_urls:
        color_url = pick_latest(color_urls)
    else:
        color_url = unspec_url.replace("TOKEN_TYPE_UNSPECIFIED", "COLOR")
        print(f"WARNING: No COLOR resource found on page; falling back to {color_url}", file=sys.stderr)

    # Typography foundation
    resources = collect_resources(page, "https://m3.material.io/styles/typography/overview")
    typo_urls = filter_urls(resources, r"/TYPOGRAPHY\.[^/]+\.json")
    if typo_urls:
        typo_url = pick_latest(typo_urls)
    else:
        typo_url = unspec_url.replace("TOKEN_TYPE_UNSPECIFIED", "TYPOGRAPHY")
        print(f"WARNING: No TYPOGRAPHY resource found on page; falling back to {typo_url}", file=sys.stderr)

    # Color
    color_tokens, color_values, color_defaults = build_maps([color_url])
    color_resolve = build_resolver(color_tokens, color_values, color_defaults)
    write_tokens("Color Foundation Tokens (M3 Expressive)", [color_url], FOUNDATION_FILES["color"], list(color_tokens.keys()), color_resolve)
    updated.append(str(FOUNDATION_FILES["color"]))

    # Typography
    typo_tokens, typo_values, typo_defaults = build_maps([typo_url])
    typo_resolve = build_resolver(typo_tokens, typo_values, typo_defaults)
    write_tokens("Typography Foundation Tokens (M3 Expressive)", [typo_url], FOUNDATION_FILES["typography"], list(typo_tokens.keys()), typo_resolve)
    updated.append(str(FOUNDATION_FILES["typography"]))

    # Motion / Shape / State from TOKEN_TYPE_UNSPECIFIED
    unspec_tokens, unspec_values, unspec_defaults = build_maps([unspec_url])
    unspec_resolve = build_resolver(unspec_tokens, unspec_values, unspec_defaults)

    motion_tokens = [t for t in unspec_tokens.keys() if t.startswith("md.sys.motion")]
    write_tokens("Motion Foundation Tokens (M3 Expressive)", [unspec_url], FOUNDATION_FILES["motion"], motion_tokens, unspec_resolve)
    updated.append(str(FOUNDATION_FILES["motion"]))

    shape_tokens = [t for t in unspec_tokens.keys() if t.startswith("md.sys.shape")]
    write_tokens("Shape Foundation Tokens (M3 Expressive)", [unspec_url], FOUNDATION_FILES["shape"], shape_tokens, unspec_resolve)
    updated.append(str(FOUNDATION_FILES["shape"]))

    state_tokens = [t for t in unspec_tokens.keys() if t.startswith("md.sys.state")]
    write_tokens("State Foundation Tokens (M3 Expressive)", [unspec_url], FOUNDATION_FILES["state"], state_tokens, unspec_resolve)
    updated.append(str(FOUNDATION_FILES["state"]))

    browser.close()
    return updated


def update_guidelines(playwright) -> List[str]:
    browser = playwright.chromium.launch()
    page = browser.new_page()
    updated: List[str] = []

    for title, source_urls, out_path in GUIDELINES:
        bullets: List[str] = []
        for url in source_urls:
            resources = collect_resources(page, url)
            content_urls = filter_urls(resources, r"/_dsm/content/m3/[^/]+/[^/]+\.json")
            if not content_urls:
                raise FetchError(f"No content JSON found for {url}")
            content_url = pick_latest(content_urls)
            content_json = load_json(content_url)
            bullets.extend(extract_guidance(content_json))
        # Deduplicate across multiple URLs
        seen: Set[str] = set()
        merged: List[str] = []
        for item in bullets:
            if item and item not in seen:
                merged.append(item)
                seen.add(item)
        write_guidelines(title, source_urls, out_path, filter_guidance_items(merged))
        updated.append(str(out_path))

    browser.close()
    return updated


def main() -> int:
    parser = argparse.ArgumentParser(description="Update M3 Expressive references.")
    parser.add_argument("--skip-guidelines", action="store_true", help="Skip guidance file updates")
    parser.add_argument("--skip-specs", action="store_true", help="Skip spec token updates")
    parser.add_argument("--skip-foundations", action="store_true", help="Skip foundation token updates")
    args = parser.parse_args()

    updated: List[str] = []
    try:
        with sync_playwright() as playwright:
            if not args.skip_specs:
                updated += update_specs(playwright)
            if not args.skip_foundations:
                updated += update_foundations(playwright)
            if not args.skip_guidelines:
                updated += update_guidelines(playwright)
    except FetchError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    for path in updated:
        print(f"updated: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
