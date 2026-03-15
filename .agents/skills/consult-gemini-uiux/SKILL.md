---
name: consult-gemini-uiux
description: >-
  Use this skill when Codex needs a Gemini second opinion for UI/UX or frontend
  work, including layout direction, visual hierarchy, spacing, typography,
  color choices, interaction design, motion, responsive behavior,
  accessibility, design critique, polish passes, or frontend implementation
  tradeoffs in React, HTML/CSS, Android Compose, and similar UI stacks.
---

# Consult Gemini UI/UX

## Overview

Use this skill to ask Gemini for concrete frontend and design guidance before
implementing, while refining, or when reviewing UI work that looks technically
correct but visually weak.

## Workflow

1. Gather only the context Gemini actually needs: user goal, target platform,
   relevant constraints, and the specific UI files or snippets under
   discussion.
2. Run `scripts/ask_gemini_frontend.py` with a targeted prompt instead of dumping the whole codebase.
3. Ask for actionable output: visual direction, UX risks, implementation
   tradeoffs, accessibility concerns, or concrete polish ideas.
4. Treat Gemini as an advisor, not an authority. Keep suggestions that fit the
   product and ignore generic or style-breaking advice.
5. Translate the useful parts into actual code and verify the result in the local UI.

## Script

`scripts/ask_gemini_frontend.py` sends a Gemini `generateContent` request with a
frontend-focused system prompt. The script supports:

- Environment-variable configuration for base URL, model, API key, and optional system prompt override
- Prompt input from `--prompt` or stdin
- Extra context from repeated `--file` arguments
- Plain-text output by default, or full JSON with `--format json`
- Basic generation controls such as timeout, temperature, and max output tokens

## Environment

- `CODEX_GEMINI_UIUX_API_KEY`: preferred API key variable
- `GEMINI_API_KEY`: fallback API key variable
- `CODEX_GEMINI_UIUX_BASE_URL`: default `https://generativelanguage.googleapis.com/v1beta`
- `CODEX_GEMINI_UIUX_MODEL`: default `gemini-2.5-pro`
- `CODEX_GEMINI_UIUX_SYSTEM_PROMPT`: optional full override for the baked-in
  system prompt
- `CODEX_GEMINI_UIUX_TIMEOUT`: optional timeout in seconds
- `CODEX_GEMINI_UIUX_TEMPERATURE`: optional generation temperature
- `CODEX_GEMINI_UIUX_MAX_OUTPUT_TOKENS`: optional max output tokens

## Usage

Ask for design critique or implementation advice:

```bash
python3 .agents/skills/consult-gemini-uiux/scripts/ask_gemini_frontend.py \
  --prompt "Review this settings screen and suggest a stronger hierarchy, spacing system, and CTA treatment." \
  --file app/src/main/java/me/rerere/rikkahub/ui/settings/SettingsScreen.kt
```

Pipe a prompt from stdin and attach multiple files:

```bash
printf '%s\n' "Give me 3 stronger visual directions for this onboarding flow, and mention accessibility risks." | \
python3 .agents/skills/consult-gemini-uiux/scripts/ask_gemini_frontend.py \
  --file web-ui/src/pages/Onboarding.tsx \
  --file web-ui/src/styles/onboarding.css
```

## Constraints

- Do not send secrets, `.env` files, or unrelated private source to Gemini.
- Prefer the smallest useful context window. Large dumps usually reduce signal.
- Preserve the existing design system when working inside an established
  product unless the user explicitly asks for a redesign.
- Skip this skill when the task is backend-only or when the user clearly does not want outside model consultation.
