# Real-Room Private Conversation Binding Resolver Design

## Goal

Allow the host-side real-room helper to resolve the intended existing RikkaHub
conversation after prior call cleanup has left `VoiceAgentCallService`
inactive. The resolved UUID remains private and is used only by the bounded
fixture-backed LiveKit verification controller.

## Root Cause

The digital E2E plan resolves `conversationId` only from the active call
service. Exact bound cleanup stops that service, so a correctly cleaned device
has no service binding to read. The installed app retains valid conversations
in its Room database, but Android has no device-side `sqlite3` executable and
raw database bytes are not safe over the managed text transport without
encoding.

## Scope

Change only RikkaHub host helper scripts and host tests. Do not change Android
source, the installed APK, app data, provider configuration, credentials,
LiveKit worker state, or managed-device infrastructure. Do not build, install,
deploy, restart, take over a lane, or use unmanaged ADB.

## Interface

Add one helper operation:

```text
resolve-binding --mdev-owner OWNER --package PACKAGE --binding-output PATH
```

`PATH` must be an absent absolute destination beneath a canonical,
owner-controlled mode-`0700` directory. On success the helper atomically
publishes exactly one lowercase canonical UUID plus a trailing newline as a
mode-`0600`, single-link regular file. Standard output remains fixed and does
not contain the UUID:

```text
voice-step.status=ok
voice-step.operation=resolve-binding
voice-step.binding=resolved
```

Failure is nonzero, emits only the helper's fixed error surface, and leaves the
destination absent. The operation is read-only on the device.

## Data Flow

1. Validate runtime, managed owner, debug package identity, and the private
   output destination.
2. Open a mode-`0700` local temporary directory.
3. Read `databases/rikka_hub`, `databases/rikka_hub-wal`, and
   `databases/rikka_hub-shm` through the production managed `run-as ... sh -c`
   transport. Encode on-device with Base64 and decode only inside the private
   host directory.
4. Require all three device files to be regular, non-symlink files. Record
   bounded metadata before and after capture, require exact stability, and
   require decoded host sizes to equal device sizes.
5. Open the snapshot read-only with host Python `sqlite3`. Read only `id` and
   `update_at` from `ConversationEntity`; never read titles, nodes, prompts, or
   messages.
6. Require every candidate used for selection to have a canonical UUID and
   integer update timestamp. Select the single row with the greatest
   `update_at`; a tie, empty set, malformed row, invalid snapshot, or changed
   source fails closed.
7. Publish the UUID privately and remove every database snapshot and temporary
   file before returning.

## Selection Contract

The intended verification conversation is the uniquely most-recent persisted
conversation. This matches the prior authorization that created a fresh
persisted conversation for the LiveKit attempt. The helper does not inspect
titles or message content to infer intent. If the most-recent timestamp is not
unique, it refuses to choose.

## Security And Privacy

- Never print or record a conversation UUID, database path from the device,
  database bytes, titles, nodes, messages, prompts, transcripts, credentials,
  or managed-device output.
- Never copy a database snapshot outside the validated private temporary
  directory.
- Never mutate, checkpoint, lock, or otherwise open the device database for
  writing.
- Never overwrite or delete a caller-supplied binding destination.
- Cleanup failure is terminal and blocks a live attempt.

## Error Handling

Validation, transport, stability, decoding, SQLite, selection, publication,
and cleanup failures are distinct internally but remain on the helper's
sanitized fixed error surface. No failure automatically retries or invokes a
call. The verification controller counts such a failure as a zero-attempt
round.

## Tests

Extend the host fake-`mdev` harness before implementation to prove:

- an inactive call service with a valid three-file WAL snapshot resolves the
  uniquely latest canonical UUID without printing it;
- the destination is mode `0600`, single-link, byte-exact, and atomically
  published;
- equal latest timestamps fail closed;
- missing, malformed, symlinked, size-mismatched, or changing database
  components fail closed and leave no destination or residue;
- invalid/pre-existing/insecure output destinations are rejected;
- managed child processes inherit no private host database descriptors;
- the existing helper operations and complete host suite remain unchanged.

After the focused regression passes, run publisher syntax checks, focused
helper tests, the complete host-only helper suite, diff/privacy checks, and an
independent scoped review before another physical-phone round.

