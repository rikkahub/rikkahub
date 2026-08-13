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
encoding. A clean final SQLite close may leave only the main database, while
an active or incompletely checkpointed database may also require its WAL.

## Scope

Change only RikkaHub host helper scripts and host tests. Do not change Android
source, the installed APK, app data, provider configuration, credentials,
LiveKit worker state, or managed-device infrastructure. Do not build, install,
deploy, restart, take over a lane, or use unmanaged ADB.

## Interface

Add one helper operation:

```text
resolve-binding --mdev-owner OWNER --package PACKAGE --binding-output PATH \
  --created-after-epoch-ms INCLUSIVE --created-before-epoch-ms EXCLUSIVE
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

The creation bounds must be decimal epoch-millisecond values with
`INCLUSIVE < EXCLUSIVE` and a span no greater than 30 minutes. They come from
a separately authorized, immutable conversation-creation window; the helper
does not derive or widen them.

Failure is nonzero, emits only the helper's fixed error surface, and leaves the
destination absent. The operation is read-only on the device. Once the
destination is committed, later signal or standard-output failure cannot
change the successful exit status.

## Data Flow

1. Before private work, re-exec `resolve-binding` once through a minimal Python
   signal-mask launcher. It blocks HUP/INT/TERM in the current process and
   execs the Bash helper, so the mask remains inherited throughout capture and
   the terminal publisher handoff.
2. Validate runtime, managed owner, debug package identity, and the private
   output destination.
3. Validate the caller-supplied creation window, then open a mode-`0700` local
   temporary directory.
4. Capture one of exactly two allowed source topologies:
   `databases/rikka_hub` alone, or `databases/rikka_hub` plus
   `databases/rikka_hub-wal`. Never transfer `databases/rikka_hub-shm`.
   SQLite documents that SHM contains no database content and is unnecessary
   for recovery; a clean final close normally checkpoints and removes both WAL
   and SHM. See [SQLite WAL-mode file format](https://www.sqlite.org/walformat.html).
5. Require each captured source to be a regular non-symlink file. The main
   database must be between 512 bytes and 64 MiB; an extant WAL must be between
   32 bytes and 64 MiB; their aggregate must not exceed 128 MiB.
6. Before capture, record the exact main/WAL topology and compute each source's
   byte size and SHA-256 content digest on-device. Read each source through the
   production managed `run-as ... sh -c` transport, encode it on-device with
   Base64, and decode it only inside the private host directory. Recompute the
   device topology, sizes, and content digests after capture and require exact
   equality with the pre-capture values. Independently require each decoded
   host file's size and digest to equal its bound device values.
7. Open the local snapshot read-only with host Python `sqlite3`. For a
   main+WAL topology, allow SQLite to reconstruct a host-local WAL index inside
   the private temporary directory; never copy the device SHM. Read only `id`
   and `create_at` from `ConversationEntity`; never read titles, nodes,
   `update_at`, prompts, or messages.
8. Restrict candidates to `create_at >= INCLUSIVE` and
   `create_at < EXCLUSIVE`. Require canonical UUIDs and integer creation
   timestamps. Select the single row with the greatest `create_at`; an empty
   set, equal maximum timestamps, malformed row, invalid snapshot, or changed
   source fails closed.
9. Retain only the selected UUID in command-local memory. Close SQLite and
   remove the database, WAL, any host-created SHM, Base64 intermediates, and
   every other local temporary. Cleanup failure is terminal and must occur
   while the caller's destination is still absent.
10. After cleanup is proven complete, `exec` a private binding publisher as the
   terminal process. It reopens and revalidates the destination parent, writes
   the exact UUID payload into a mode-`0600` anonymous `O_TMPFILE` inode,
   fsyncs and byte-verifies it, installs its fixed-error pre-link handlers,
   unblocks the inherited HUP/INT/TERM mask so pending signals fail closed,
   then ignores those signals immediately before atomically linking the inode
   descriptor-relative through `/proc/self/fd`. That link is the final
   fallible commit. After a successful link, fixed success output is
   best-effort only and cannot change exit `0`; the publisher immediately
   calls `os._exit(0)`. No shell cleanup or trap runs afterward.

## Selection Contract

The intended verification conversation is the uniquely newest conversation by
`create_at` inside a separately authorized creation window of at most 30
minutes. The bounds are immutable inputs to the resolver. `update_at` is not a
selection input, so later activity on an older conversation cannot displace
the intended freshly created conversation. The helper does not inspect titles
or message content to infer intent. If the maximum in-window `create_at` is not
unique, it refuses to choose.

## Security And Privacy

- Never print or record a conversation UUID, database path from the device,
  database bytes, titles, nodes, messages, prompts, transcripts, credentials,
  or managed-device output.
- Never copy a database snapshot outside the validated private temporary
  directory.
- Never mutate, checkpoint, lock, or otherwise open the device database for
  writing.
- Never transfer or rely on the device SHM file.
- Never overwrite or delete a caller-supplied binding destination.
- Remove all private snapshots before invoking the anonymous-inode publisher.
- Treat the publisher's descriptor-relative link as the commit boundary. No
  unlink, cleanup, trap, signal handling, or outcome-affecting reporting
  follows it.
- Block HUP/INT/TERM before any private resolver work and keep them blocked
  across the Bash-to-publisher exec. The publisher alone installs the pre-link
  failure handlers and unblocks them; no default-disposition handoff window is
  permitted after private work begins.

## Error Handling

Validation, transport, topology, size, digest, decoding, SQLite, selection,
cleanup, and pre-commit publication failures are distinct internally but
remain on the helper's sanitized fixed error surface. They leave the
destination absent. No failure automatically retries or invokes a call. The
verification controller counts such a failure as a zero-attempt round.

Because the publisher replaces Bash with `exec`, every publisher failure before
the link emits only `voice-step.error=operation failed` on stderr, best-effort,
then exits nonzero. It never emits exception details or dynamic values.

After the anonymous inode is linked, the operation is committed success.
Handled signals are already ignored, cleanup is already complete, fixed output
is best-effort, and the publisher exits `0` without returning to Bash.

## Tests

Extend the host fake-`mdev` harness before implementation to prove:

- an inactive call service resolves the uniquely newest in-window canonical
  UUID without printing it from both a stable main-only snapshot and a stable
  main+WAL snapshot;
- the destination is mode `0600`, single-link, byte-exact, and atomically
  published;
- an older conversation whose `update_at` is later than the intended row does
  not win selection;
- absent candidates, out-of-window candidates, equal maximum `create_at`
  timestamps, invalid windows, and windows longer than 30 minutes fail closed;
- a missing main database, a WAL without its main database, malformed or
  symlinked components, below/above-limit files, aggregate size overflow,
  decoded size/digest mismatch, pre/post topology changes, and pre/post content
  changes fail closed and leave no destination or residue;
- device SHM is never read or transferred;
- invalid/pre-existing/insecure output destinations are rejected;
- managed child processes inherit no private host database descriptors;
- injected snapshot cleanup failure occurs before publication, returns
  nonzero, and leaves the destination absent;
- publisher `O_TMPFILE`, short-write, parent-replacement, and destination-link
  races fail before commit without overwrite or residue;
- a handled signal before link fails with no destination, while a handled
  signal or output fault after link cannot retract the destination or change
  committed exit `0`;
- the existing helper operations and complete host suite remain unchanged.

After the focused regression passes, run publisher syntax checks, focused
helper tests, the complete host-only helper suite, diff/privacy checks, and an
independent scoped review before another physical-phone round.
