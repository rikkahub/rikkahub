/**
 * Neutral runtime-contract package for the chat-turn runtime (issue #243).
 *
 * Everything in this module is platform-neutral and app-neutral: it may depend
 * only on the `:ai` and `:common` modules. It must stay free of app types,
 * Android platform / resource references, Compose, and any persistence layer.
 * That boundary is enforced verbatim by the `:ai-runtime` P1-P3 gates in
 * `.github/workflows/ci.yml`, so this file deliberately avoids naming the
 * banned tokens (those names would otherwise trip the gate against this very
 * comment).
 *
 * The neutral contract types and runtime ports, the app-side adapters and
 * mappers, and the dependency-injection binding land in a later slice; this
 * slice only establishes the module and its boundary gates.
 */
package me.rerere.ai.runtime
