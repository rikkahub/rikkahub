---
name: "skill-creator"
description: "Use when the user wants to create, update, package, or improve a local skill, including SKILL.md frontmatter, folder layout, templates, and zip-ready skill bundles."
---

# Skill Creator

Use this when the task is about authoring or revising a skill.

## Workflow

1. Identify the skill's trigger condition and keep it specific.
2. Create one directory per skill under `skills/`.
3. Write `SKILL.md` with required YAML frontmatter:
   - `name`
   - `description`
4. Keep the body concise. Put only the workflow and decision rules there.
5. Add `scripts/`, `references/`, or `assets/` only when they materially help.
6. For zip sharing, package the whole skill directory with `SKILL.md` at the skill root.

## Template

```md
---
name: "example-skill"
description: "Use when the user needs ..."
---

# Overview

State when to use the skill, what to inspect, and the steps to follow.
```

## Quality Bar

- Prefer short, high-signal instructions over long explanations.
- Do not create extra docs like README or changelog unless the task explicitly needs them.
- If variants exist, keep `SKILL.md` brief and move detailed material into `references/`.
