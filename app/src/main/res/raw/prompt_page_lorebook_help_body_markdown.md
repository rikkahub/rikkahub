# What lorebooks do

Lorebooks are keyword-triggered world info.

Each lorebook contains many entries. Each entry watches recent context and, when matched, injects its content into the prompt.

## Good use cases

- setting facts
- relationship notes
- faction or location summaries
- recurring rules that should appear only when relevant

## Quick start

1. Create a lorebook.
2. Bind it to the assistants that should use it.
3. Add entries with short, specific keywords.
4. Write concise entry content.
5. Test with real chat messages, then tighten keywords if it triggers too often.

## Demo

```text
Lorebook: Kingdom of Vale

Entry keywords:
vale, royal family, queen elira

Entry content:
Vale is a coastal kingdom ruled by Queen Elira. The royal family values diplomacy first and open war only as a last resort.
```

## Book-level settings

- **Recursive scanning**: triggered entries may help trigger more entries in later passes.
- **Token budget**: caps how much content this lorebook can contribute.
- **Assistant bindings**: keep one lorebook shared only where it belongs.

## Practical advice

- Prefer several small entries over one giant entry.
- If one keyword is too broad, add secondary keywords or selective logic.
- Use **Constant Active** sparingly.

> If something should appear only when context mentions it, lorebook is usually the right tool.
