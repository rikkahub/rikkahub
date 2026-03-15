#!/usr/bin/env python3
"""Ask Gemini for UI/UX and frontend advice."""

from __future__ import annotations

import argparse
import json
import os
import sys
import textwrap
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_BASE_URL = "http://127.0.0.1:8317/v1beta"
DEFAULT_MODEL = "gemini-3.1-pro-high"
DEFAULT_TIMEOUT = 260
DEFAULT_TEMPERATURE = 1
DEFAULT_MAX_OUTPUT_TOKENS = 65536
DEFAULT_MAX_FILE_CHARS = 120000
DEFAULT_SYSTEM_PROMPT = textwrap.dedent(
    """
    You are Gemini, acting as a senior UI/UX and frontend design advisor for Codex.
    Your job is to help a strong coding agent make better product-facing decisions.

    Prioritize concrete, opinionated, implementable guidance for:
    - visual hierarchy and layout
    - spacing, typography, color, and contrast
    - interaction design, motion, and perceived polish
    - responsive behavior and edge cases
    - accessibility and usability risks
    - frontend implementation tradeoffs

    Adapt to the platform in the prompt, whether it is web, React, HTML/CSS, Android
    Compose, or another UI stack. Be direct about weak design choices and explain why
    they are weak. Offer stronger alternatives that Codex can realistically implement.

    Avoid generic praise, trend-chasing, or vague advice. If context is incomplete,
    state what is missing and then give the best provisional guidance you can.
    Prefer concise, high-signal feedback over long essays.
    """
).strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send a UI/UX or frontend prompt to Gemini and print the response."
    )
    parser.add_argument("--prompt", help="Primary prompt text. If omitted, read from stdin.")
    parser.add_argument(
        "--file",
        action="append",
        default=[],
        help="Attach a relevant text file as extra context. Repeat for multiple files.",
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("CODEX_GEMINI_UIUX_BASE_URL", DEFAULT_BASE_URL),
        help="Gemini API base URL or full generateContent endpoint.",
    )
    parser.add_argument(
        "--model",
        default=os.getenv("CODEX_GEMINI_UIUX_MODEL", DEFAULT_MODEL),
        help="Gemini model name.",
    )
    parser.add_argument(
        "--api-key",
        default=os.getenv("CODEX_GEMINI_UIUX_API_KEY") or os.getenv("GEMINI_API_KEY"),
        help="Gemini API key. Defaults to CODEX_GEMINI_UIUX_API_KEY or GEMINI_API_KEY.",
    )
    parser.add_argument(
        "--system-prompt",
        default=os.getenv("CODEX_GEMINI_UIUX_SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT),
        help="Override the default frontend-focused system prompt.",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=_env_float("CODEX_GEMINI_UIUX_TEMPERATURE", DEFAULT_TEMPERATURE),
        help="Generation temperature.",
    )
    parser.add_argument(
        "--max-output-tokens",
        type=int,
        default=_env_int("CODEX_GEMINI_UIUX_MAX_OUTPUT_TOKENS", DEFAULT_MAX_OUTPUT_TOKENS),
        help="Maximum output tokens.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=_env_int("CODEX_GEMINI_UIUX_TIMEOUT", DEFAULT_TIMEOUT),
        help="HTTP timeout in seconds.",
    )
    parser.add_argument(
        "--max-file-chars",
        type=int,
        default=DEFAULT_MAX_FILE_CHARS,
        help="Maximum characters to read from each attached file.",
    )
    parser.add_argument(
        "--format",
        choices=("text", "json"),
        default="text",
        help="Output format.",
    )
    return parser.parse_args()


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise SystemExit(f"{name} must be an integer, got: {raw!r}") from exc


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise SystemExit(f"{name} must be a float, got: {raw!r}") from exc


def read_prompt(prompt_arg: str | None) -> str:
    if prompt_arg:
        return prompt_arg.strip()

    if sys.stdin.isatty():
        raise SystemExit("Provide --prompt or pipe prompt text via stdin.")

    prompt = sys.stdin.read().strip()
    if not prompt:
        raise SystemExit("Prompt is empty.")
    return prompt


def read_context_files(paths: list[str], max_chars: int) -> str:
    if not paths:
        return ""

    blocks = []
    for raw_path in paths:
        path = Path(raw_path).expanduser()
        if not path.is_file():
            raise SystemExit(f"Attached file does not exist: {path}")

        content = path.read_text(encoding="utf-8", errors="replace")
        truncated = ""
        if len(content) > max_chars:
            content = content[:max_chars]
            truncated = f"\n[Truncated to first {max_chars} characters.]"

        blocks.append(
            "\n".join(
                [
                    f"File: {path}",
                    "```text",
                    content,
                    "```",
                    truncated,
                ]
            ).strip()
        )

    return "\n\n".join(blocks)


def build_user_text(prompt: str, file_context: str) -> str:
    sections = [f"Task:\n{prompt.strip()}"]
    if file_context:
        sections.append(f"Relevant context files:\n{file_context}")
    return "\n\n".join(sections)


def build_endpoint(base_url: str, model: str) -> str:
    base_url = base_url.strip()
    if not base_url:
        raise SystemExit("Base URL is empty.")

    if "{model}" in base_url:
        return base_url.format(model=model)

    normalized = base_url.rstrip("/")
    if normalized.endswith(":generateContent"):
        return normalized

    quoted_model = urllib.parse.quote(model, safe="-._")
    return f"{normalized}/models/{quoted_model}:generateContent"


def build_payload(system_prompt: str, user_text: str, temperature: float, max_output_tokens: int) -> dict:
    return {
        "system_instruction": {
            "parts": [{"text": system_prompt}],
        },
        "contents": [
            {
                "role": "user",
                "parts": [{"text": user_text}],
            }
        ],
        "generationConfig": {
            "temperature": temperature,
            "maxOutputTokens": max_output_tokens,
        },
    }


def call_gemini(endpoint: str, api_key: str, payload: dict, timeout: int) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "x-goog-api-key": api_key,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Gemini request failed with HTTP {exc.code}:\n{response_body}") from exc
    except urllib.error.URLError as exc:
        raise SystemExit(f"Gemini request failed: {exc.reason}") from exc


def extract_text(response: dict) -> str:
    candidates = response.get("candidates") or []
    for candidate in candidates:
        content = candidate.get("content") or {}
        parts = content.get("parts") or []
        texts = [part.get("text", "") for part in parts if part.get("text")]
        if texts:
            return "\n".join(texts).strip()

    prompt_feedback = response.get("promptFeedback")
    if prompt_feedback:
        return json.dumps({"promptFeedback": prompt_feedback}, ensure_ascii=False, indent=2)

    return json.dumps(response, ensure_ascii=False, indent=2)


def main() -> None:
    args = parse_args()
    if not args.api_key:
        raise SystemExit(
            "Missing Gemini API key. Set CODEX_GEMINI_UIUX_API_KEY or GEMINI_API_KEY, "
            "or pass --api-key."
        )

    prompt = read_prompt(args.prompt)
    file_context = read_context_files(args.file, args.max_file_chars)
    endpoint = build_endpoint(args.base_url, args.model)
    payload = build_payload(
        system_prompt=args.system_prompt,
        user_text=build_user_text(prompt, file_context),
        temperature=args.temperature,
        max_output_tokens=args.max_output_tokens,
    )
    response = call_gemini(endpoint=endpoint, api_key=args.api_key, payload=payload, timeout=args.timeout)

    if args.format == "json":
        print(json.dumps(response, ensure_ascii=False, indent=2))
        return

    print(extract_text(response))


if __name__ == "__main__":
    main()
