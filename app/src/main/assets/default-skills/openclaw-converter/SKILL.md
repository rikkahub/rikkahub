---
name: openclaw-converter
description: Convert OpenClaw skills from ClawHub (or raw markdown) into RikkaHub-compatible skills. Use whenever the user gives you an OpenClaw skill URL, a GitHub link to an OpenClaw skill, or raw OpenClaw skill markdown to convert.
auto_load: false
---

# OpenClaw to RikkaHub Skill Converter

Convert OpenClaw skills from ClawHub (or raw markdown) into RikkaHub-compatible skills. Apply this whenever the user gives you an OpenClaw skill URL, a GitHub link to an OpenClaw skill, or raw OpenClaw skill markdown to convert.

## How to Fetch the Source

1. ClawHub URL (`https://clawhub.ai/<owner>/<slug>`): the page is a JS single-page app, so a plain fetch returns empty. Use the in-app browser instead: `browser_open` the URL, wait for content to render, extract the rendered SKILL.md text (cap around 16000 chars), then close the browser.
2. GitHub raw URL: if you know the repo, try `https://raw.githubusercontent.com/<owner>/<repo>/main/SKILL.md`. Some skills live under `https://github.com/<owner>/<repo>/tree/main/skills/<name>`.
3. Raw markdown from the user: if the user pastes the SKILL.md directly, use it as-is.

## Conversion Rules

### Paths

| OpenClaw | RikkaHub |
|---|---|
| `~/.openclaw/workspace/` | `~/` |
| `~/.openclaw/workspace/.learnings/` | `~/learnings/` |
| `~/.openclaw/skills/<name>/` | Installed via `skill_install_from_text`; files go to `~/` |
| Any project-root `.learnings/` | `~/learnings/` |

### Tool references

| OpenClaw tool / concept | RikkaHub equivalent | Notes |
|---|---|---|
| `sessions_list` | Not available | Remove the section or note the limitation |
| `sessions_history` | Not available | Remove the section or note the limitation |
| `sessions_send` | `telegram_send_message` | Cross-session becomes cross-device notification |
| `sessions_spawn` | `subagent_dispatch` | Only if the user has sub-agents enabled |
| `clawdhub install <name>` | `skill_install_from_url` or `skill_install_from_text` | Replace install instructions |
| `openclaw hooks enable` | `schedule_job` or `workflow_create` | Hooks become scheduled/workflow automation |
| `memory` (OpenClaw) | `memory_tool` (create/edit/delete) | Same concept, different API |
| Shell commands | `termux_run_command` | Prefix root commands with `su -c` |
| File read/write | `write_text_file`, `read_file`, `list_files` | Same paths, adapted |

### Promotion targets

| OpenClaw target | RikkaHub target |
|---|---|
| `CLAUDE.md` | A project-level file (if the project exists) or `~/learnings/` |
| `AGENTS.md` | A RikkaHub skill file or `memory_tool` |
| `SOUL.md` | `memory_tool` for behavioral patterns |
| `TOOLS.md` | Update the relevant skill's content |
| `MEMORY.md` | `memory_tool` |
| `.github/copilot-instructions.md` | Keep as-is for GitHub Copilot users |

### Sections to remove or replace

- "OpenClaw Setup" / "OpenClaw Workspace Structure": replace with RikkaHub workspace paths.
- "Inter-Session Communication": remove `sessions_*` tools; if the concept is valuable, suggest `telegram_send_message` as a cross-session notification workaround.
- "Hook Integration" / "Enable Hook": replace with RikkaHub workflows (`workflow_create`) or scheduled jobs (`schedule_job`).
- "Claude Code / Codex Setup": remove entirely (other agent platforms).
- Installation via `clawdhub` or `git clone`: replace with "install via `skill_install_from_url` or `skill_install_from_text`" and RikkaHub-compatible paths.

### Sections to keep as-is

Logging formats (LEARNINGS.md / ERRORS.md structure), detection triggers, priority guidelines, area tags, best practices (unless they reference removed tools), and the core workflow / quick-reference table.

### Format header

RikkaHub skills use a simple frontmatter block: `name`, `description`, and `auto_load`. Strip any OpenClaw YAML frontmatter and use the first `# Heading` as the title.

### Naming

Lowercase with hyphens, max 40 chars. Keep the original name when possible.

## Post-Install Checklist

After `skill_install_from_text` returns ok:
1. Confirm the skill installed and auto-enabled.
2. If the skill needs directories (e.g. `~/learnings/`), create them with `write_text_file`.
3. If the skill references external tools (whisper, docker, etc.), note the dependencies.
4. Log the conversion to `~/learnings/LEARNINGS.md` as a `best_practice`.

## Example Conversion Flow

```
User: "fetch this skill and convert it https://clawhub.ai/owner/skill-name"

1. browser_open(url)
2. wait for the rendered article/markdown
3. extract the page text (cap ~16000 chars)
4. close the browser
5. apply the conversion rules above to produce adapted markdown
6. skill_install_from_text(content=adapted, name="skill-name",
     source_label="Converted from <url> for RikkaHub")
7. initialise any required directories
8. confirm to the user
```

## Known Edge Cases

- Minimal skills (a few paragraphs): only paths and tool names need changing.
- Skills with hook scripts: the `scripts/` directory and hook setup are OpenClaw-specific; replace with RikkaHub workflows/scheduled jobs or remove.
- Skills referencing `~/.openclaw/` paths: replace all with `~/` equivalents.
- Skills with YAML frontmatter: strip the delimited block at the top.
- Skills already installed: `skill_install_from_text` with the same name updates in place. Do not rename to force a new install; updating is preferred.

## Triggers

Apply this skill when the user gives you a ClawHub URL, says "convert this OpenClaw skill", says "make this work here" with OpenClaw skill content, pastes a raw OpenClaw SKILL.md to install, or asks to "fetch and convert" a skill from another agent platform.
