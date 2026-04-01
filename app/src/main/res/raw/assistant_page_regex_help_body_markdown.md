# What this editor does

Regex rules rewrite text at specific points in the chat pipeline.

You can use them to:
- clean imported SillyTavern formatting
- normalize names or punctuation before sending to the model
- hide noisy text only in the UI
- target ST-style placements such as user input, AI output, world info, slash commands, or reasoning

## Quick start

1. Give the rule a clear name.
2. Write the pattern in **Find Regex**.
3. Write the output in **Replace String**.
4. Decide whether it should affect the real message, only the UI, or only the prompt.
5. If this rule came from ST, set **ST placements** so the timing matches the original script.

## Demo 1: unify nickname variants

```text
Goal:
Turn "Alicia" and "Ally" into "Alice"

Find Regex:
Alicia|Ally

Replace String:
Alice
```

## Demo 2: clean a captured group before writing it back

```text
Goal:
Keep only the useful part of a match

Find Regex:
Name:\s*(.*)

Replace String:
$1

Trim captured strings:
[
]
```

## Important fields

- **Trim captured strings**: remove extra wrapper text from captured groups before the replacement is written back.
- **RAW / ESCAPED macro substitution**: resolve macros such as `{{char}}` and `{{user}}` inside the regex itself before matching.
- **ST placements**: if this is not empty, it overrides the generic scopes above.
- **Run on edit**: also apply the rule when an existing message is edited.

> Rule of thumb: if you are copying a regex from SillyTavern, set ST placements first. If you are building a general app-wide cleanup rule, start from generic scopes.
