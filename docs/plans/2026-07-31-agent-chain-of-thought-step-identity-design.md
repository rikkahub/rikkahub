# Agent Chain-of-Thought Step Identity Design

## Problem

`ChainOfThought` renders visible steps with positional composition identity. Its uncontrolled step implementation keeps
expanded state with `remember`, and the new arrow rotation also owns animation state. If a step is inserted before an
existing expanded step, Compose can reuse that slot for the new item, transferring expansion and animation state to the
wrong Agent action.

The live chat currently wraps reasoning and tool content in handwritten keys, which protects the common non-empty ID
case. The generic component, export path, preview, and future callers have no identity contract. The tool fallback uses
the entire step hash when `toolCallId` is blank, so streamed input/output changes can also change the key and reset UI
state.

## Options considered

1. Keep call-site keys and improve only the blank-ID fallback. This leaves the reusable list unsafe by default and
   allows future callers to repeat the bug.
2. Use each step object itself as a default key. Data-class equality includes streamed content, so updates can still
   change identity; equal duplicate values can also collide.
3. Require `ChainOfThought` callers to provide `stepKey`, and preserve the original message-part index in every
   `ThinkingStep`. This is selected because list identity becomes an explicit boundary contract and remains stable while
   a part's text, output, approval, or animation state changes.

## Design

Add `sourceIndex` to `ThinkingStep.ReasoningStep` and `ThinkingStep.ToolStep` when `groupMessageParts` walks the
original message parts. The index is stable for the existing append/update streaming model and does not depend on
mutable part content or late-arriving telemetry IDs.

Make `stepKey: (T) -> Any` a required `ChainOfThought` parameter. Wrap each visible step with Compose
`key(stepKey(step))` at the same level that owns iteration. The live chat and export paths use `sourceIndex`; the
preview uses its unique label; tests use their explicit fixture IDs. Remove the nested chat-only `key` wrappers so
identity has one owner.

This does not animate list insertion itself. It guarantees that existing expansion, answer draft composition, and arrow
animation state remain attached to the correct reasoning/tool step when the visible list changes. It also avoids
retaining outgoing approval controls solely for decorative motion.

## Verification

- A Compose contract expands step B, inserts step A before it, and requires B to remain expanded while A stays
  collapsed.
- A JVM contract groups interleaved message parts and requires reasoning/tool source indices to match their original
  positions.
- Every `ChainOfThought` call site must compile with an explicit key selector.
- Production and Android-test Kotlin compilation, focused Agent/tool JVM tests, changed-line length checks, and
  `git diff --check` must pass.
