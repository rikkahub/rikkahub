# QuickJS execution boundary

The bundled `wang.harlon.quickjs:wrapper-android:3.2.3` exposes memory and stack limits, but it does not expose
QuickJS's runtime interrupt handler or an execution-time limit. `evaluate()` is synchronous. Cancelling an outer
coroutine cannot stop it, and calling `destroy()` from another thread is not a supported interruption mechanism.

To avoid a timed-out script permanently occupying an agent or IO worker thread, arbitrary QuickJS execution is disabled:

- `eval_javascript` is no longer registered by `LocalTools`; its retained builder returns a deterministic disabled error
  without creating a QuickJS context, protecting legacy direct callers.
- Custom JavaScript search and scraping return an explicit unsupported-operation failure without evaluating configured
  scripts.

Syntax highlighting continues to use its bundled, application-controlled Prism script and is outside this arbitrary
script-tool boundary. Re-enable arbitrary JavaScript only after moving evaluation to a process that can be terminated or
after adopting a QuickJS binding with a supported interrupt API; add execution-time, memory, stack, and cancellation
tests at that time.
