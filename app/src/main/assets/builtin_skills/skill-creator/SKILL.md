---
name: skill-creator
description: >-
  Create new Agent Skills or update existing ones. Use when the user wants to make a skill,
  turn a workflow, prompt, or set of instructions into a reusable skill, or improve a skill in /skills.
---

# Skill Creator

A skill is a directory with a `SKILL.md` file (YAML frontmatter + Markdown instructions) and optional supporting files. Only the `name` and `description` of enabled skills are always in context; the body is loaded with the `use_skill` tool when a request matches, and supporting files are loaded only when needed.

## Where skills live

- User skills: `/skills/<name>/` in the workspace. This directory is writable; create and edit files there directly with `workspace_write_file` / `workspace_edit_file`.
- Built-in skills: `/builtin_skills/<name>/`, read-only. To customize one, copy it to `/skills/<same-name>/` and edit the copy; a user skill overrides a built-in skill with the same name.
- A new skill shows up in the app's skill list automatically, but is disabled by default. Always tell the user to enable it for their assistant (Skills tab of the extensions panel in the chat input, or the assistant's extension settings).

## Workflow

1. **Understand the use cases.** Figure out what the skill should do, when it should trigger, and what good output looks like. If the conversation already shows the workflow (e.g. "turn what we just did into a skill"), extract it instead of asking. Ask only about what you cannot infer, and keep questions few.
2. **Plan the contents.** Decide what goes in `SKILL.md`, what belongs in reference files, and what repetitive or fragile work should be a script.
3. **Check for conflicts.** Run `ls /skills`. If `/skills/<name>/` already exists, read it first and confirm with the user before overwriting.
4. **Write the files.**
5. **Validate** with the checklist below. Run every script at least once.
6. **Report** the files created, remind the user to enable the skill, and suggest a prompt to try it.

## SKILL.md format

```markdown
---
name: my-skill
description: What the skill does, and when to use it.
---

# My Skill

Instructions...
```

Frontmatter rules:

- The file must start with `---` on the first line, and the frontmatter must be closed with `---`.
- `name` (required): MUST be identical to the directory name. Use lowercase letters, digits and hyphens, at most 64 characters. Never use `/` or `\`, and never start with `.`.
- `description` (required): at most 1024 characters, longer text is truncated. This is the only part the model sees before loading the skill, so state both what the skill does and when to use it, including the keywords a user would likely say. Write it in third person.
- `compatibility` (optional): environment requirements, e.g. `Requires a workspace with python3`.
- All values must be plain strings. Quote a value, or use a `>-` folded block, if it contains `: ` or `#`, or starts with a special character such as `[`, `{`, `*`, `&`, `!`, `|`, `>`, `'`, `"`, `%` or `@`.

The body contains instructions for the model that will use the skill. Write in the imperative, and keep it focused. Keep it under about 500 lines and move details into reference files.

## Supporting files

Use this layout:

```
/skills/my-skill/
├── SKILL.md
├── references/   # detailed docs, loaded on demand
├── scripts/      # executable helpers
└── assets/       # templates and other files used in the output
```

- **References**: link each one from `SKILL.md` with a relative Markdown link, e.g. `[API reference](references/api.md)`. The `use_skill` tool only loads files whose paths appear in such links. Say when each file should be read. Keep references one level deep, and do not chain references to other references.
- **Scripts**: use them for deterministic or repetitive work where writing code each time would be wasteful or error-prone.
  - Scripts only run when the assistant has a workspace.
  - In `SKILL.md`, refer to scripts by absolute path (e.g. `/skills/my-skill/scripts/convert.py`) so they work from any working directory, and document their arguments and output.
  - Start each script with a shebang and run `chmod +x` on it.
  - The workspace rootfs is chosen by the user, so do not assume any interpreter or package is installed. Check with `command -v python3` (or similar), and declare requirements in `compatibility`.
- **Assets**: `use_skill` can only read text files. Binary files can only be used from the workspace.

Do not add README, CHANGELOG or other files that do not help the model do the task.

## Writing guidelines

- Be concise. The model is already capable, so only include knowledge it would not have: domain rules, conventions, exact formats, pitfalls and preferences.
- Prefer concrete examples over abstract rules. Show input/output pairs when the format matters.
- Match the level of detail to how fragile the task is. Give exact steps or a script for error-prone operations. Give guidance and let the model decide for open-ended tasks.
- Give output templates when the result must follow a fixed structure.
- For a multi-step process, use a numbered checklist the model can follow.

## Without a workspace

If workspace tools are unavailable, you cannot write files. Instead, output the complete `SKILL.md` in a single code block, and tell the user to add it manually from the Agent Skills page (tap **+**, choose **Add manually**, and paste the content). A skill with multiple files can be packed as a `.zip` and added with **Import from file**.

## Validation checklist

- [ ] The directory name equals the `name` field.
- [ ] The frontmatter parses as YAML: it starts on line 1, is closed with `---`, and special characters are quoted.
- [ ] `description` says what the skill does and when to use it, in at most 1024 characters.
- [ ] Every file linked from `SKILL.md` exists at that relative path.
- [ ] Every script is executable, has been run successfully, and its requirements are listed in `compatibility`.
- [ ] Nothing in the skill conflicts with or duplicates an existing skill in `/skills` or `/builtin_skills`.
