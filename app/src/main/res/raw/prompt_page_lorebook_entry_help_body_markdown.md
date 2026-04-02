# Editing one lorebook entry

This is where you define **when** the entry triggers and **what** it injects.

## Recommended workflow

1. Write primary keywords.
2. Add content.
3. Choose injection position.
4. Test in chat.
5. Only then add advanced controls like probability, groups, sticky, cooldown, or recursion flags.

## Demo 1: simple keyword entry

```text
Primary keywords:
lighthouse, harbor beacon

Content:
The Old Harbor lighthouse has been abandoned for 20 years, but locals still believe its lamp turns on before storms.
```

## Demo 2: make a broad trigger safer

```text
Primary keyword:
king

Secondary keywords:
vale, elira

Selective logic:
Any secondary keyword
```

That means the entry will not fire just because someone said "king" in a random context.

## Important sections

- **Trigger conditions**: keywords, regex mode, case sensitivity, whole word matching, probability, and extra trigger sources.
- **Injection settings**: where the content goes and which role it uses.
- **Advanced compatibility**: ST-style group, delay, sticky, cooldown, recursion, and export metadata.

## Practical advice

- Keep content factual and compact.
- Prefer precise keywords over giant entry content.
- Use advanced compatibility only when you actually need ST-like runtime behavior.

> If you are unsure, leave advanced settings alone and get the basic trigger working first.
