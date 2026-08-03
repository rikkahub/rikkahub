#!/usr/bin/env bash
set -euo pipefail

umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT_DIR/scripts/voice-agent-real-room-step.sh"
TMP_DIR="$(mktemp -d)"
chmod 700 "$TMP_DIR"
BIN_DIR="$TMP_DIR/bin"
mkdir "$BIN_DIR"
chmod 700 "$BIN_DIR"
ADB_LOG="$TMP_DIR/adb.argv"
TIMEOUT_LOG="$TMP_DIR/timeout.argv"
FAKE_STATE="$TMP_DIR/fake-state.json"
STDOUT_FILE="$TMP_DIR/stdout"
STDERR_FILE="$TMP_DIR/stderr"
TEST_COUNT=0

cleanup() {
  rm -rf -- "$TMP_DIR"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass() {
  TEST_COUNT=$((TEST_COUNT + 1))
}

cat > "$BIN_DIR/timeout" <<'PY'
#!/usr/bin/env python3
import os
import sys


def record(path, argv):
    with open(path, "ab") as handle:
        for value in argv:
            handle.write(value.encode() + b"\0")
        handle.write(b"\0")


record(os.environ["FAKE_TIMEOUT_LOG"], sys.argv[1:])
if os.environ.get("FAKE_TIMEOUT_EXIT") == "124":
    raise SystemExit(124)
arguments = sys.argv[1:]
while arguments and arguments[0].startswith("--"):
    arguments = arguments[1:]
if not arguments or not arguments[0].endswith("s"):
    raise SystemExit(125)
arguments = arguments[1:]
if not arguments:
    raise SystemExit(125)
os.execvpe(arguments[0], arguments, os.environ)
PY
chmod 700 "$BIN_DIR/timeout"

cat > "$BIN_DIR/adb" <<'PY'
#!/usr/bin/env python3
import hashlib
import json
import os
import signal
import sys
from pathlib import Path

EXPECTED_PACKAGE = "me.rerere.rikkahub.debug"
CONTROL = "me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver"
FIXTURE = "me.rerere.rikkahub.voiceagent.debug.VoiceCaptureFixtureDebugReceiver"
SERVICE = "me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
STATE_PATH = Path(os.environ["FAKE_ADB_STATE"])


def record(path, argv):
    with open(path, "ab") as handle:
        for value in argv:
            handle.write(value.encode() + b"\0")
        handle.write(b"\0")


def load_state():
    with STATE_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def save_state(state):
    temporary = STATE_PATH.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(state, handle, separators=(",", ":"))
    os.replace(temporary, STATE_PATH)


def complete(result, data):
    escaped = data.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
    print(f'Broadcast completed: result={result}, data="{escaped}"')


def extras(arguments):
    values = {}
    index = 0
    while index < len(arguments):
        if arguments[index] in {"--es", "--ei", "--el"} and index + 2 < len(arguments):
            values[arguments[index + 1]] = arguments[index + 2]
            index += 3
        else:
            index += 1
    return values


def hash_value(character):
    return "sha256:" + character * 64


def sanitized_events():
    base = {
        "version": 1,
        "voiceSessionHash": hash_value("1"),
        "eventId": "event-1",
        "kind": "job_accepted",
        "observedAt": "2026-08-03T00:00:00Z",
        "eventHash": hash_value("2"),
    }
    job = {
        "userTurnId": "turn-1",
        "requestHash": hash_value("3"),
        "toolCallId": "tool-1",
        "argumentHash": hash_value("4"),
        "jobId": "job-1",
        "ownerHash": hash_value("5"),
        "conversationHash": hash_value("6"),
        "roomHash": hash_value("7"),
        "traceHash": hash_value("8"),
    }
    rows = []
    first = dict(base)
    first.update(job)
    first["promptCharacterCount"] = 17
    rows.append(first)
    second = dict(first)
    second["eventId"] = "event-2"
    second["eventHash"] = hash_value("9")
    second["userTurnId"] = "turn-2"
    second["toolCallId"] = "tool-2"
    second["jobId"] = "job-2"
    rows.append(second)
    terminal = dict(base)
    terminal.update(job)
    terminal["eventId"] = "event-3"
    terminal["eventHash"] = hash_value("a")
    terminal["kind"] = "job_succeeded"
    terminal["resultHash"] = hash_value("b")
    terminal["answerCharacterCount"] = 23
    rows.append(terminal)
    delivery = dict(base)
    delivery["eventId"] = "event-4"
    delivery["eventHash"] = hash_value("c")
    delivery["kind"] = "delivery_announced"
    delivery["toolCallId"] = "tool-1"
    delivery["jobId"] = "job-1"
    delivery["assistantTurnId"] = "assistant-1"
    rows.append(delivery)
    return "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)


def automation_events(state):
    rows = [
        {
            "schemaVersion": 1,
            "sequence": 1,
            "name": "run_prepared",
            "monotonicMs": 1,
            "runHash": state["run_hash"],
            "comparisonHash": state["comparison_hash"],
            "requestedTransport": state["transport"],
        }
    ]
    if state.get("call_active"):
        rows.append(
            {
                "schemaVersion": 1,
                "sequence": 2,
                "name": "call_active",
                "monotonicMs": 2,
                "runHash": state["run_hash"],
                "comparisonHash": state["comparison_hash"],
                "requestedTransport": state["transport"],
                "observedTransport": state["transport"],
            }
        )
    return "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)


def artifact_content(path, state):
    missing_name = os.environ.get("FAKE_ADB_MISSING_ARTIFACT")
    if missing_name and path.endswith(missing_name):
        return None
    if path.endswith("automation-events.jsonl"):
        content = automation_events(state).encode()
        if os.environ.get("FAKE_ADB_EMPTY_ARTIFACT") == "automation-events.jsonl":
            return b""
        if os.environ.get("FAKE_ADB_INCOMPLETE_ARTIFACT") == "automation-events.jsonl":
            return content.rstrip(b"\n")
        return content
    if path.endswith("voice-experience-private.ndjson"):
        content = b'{"private":"fixture-secret"}\n'
        if os.environ.get("FAKE_ADB_EMPTY_ARTIFACT") == "voice-experience-private.ndjson":
            return b""
        if os.environ.get("FAKE_ADB_INCOMPLETE_ARTIFACT") == "voice-experience-private.ndjson":
            return content.rstrip(b"\n")
        return content
    if path.endswith("voice-experience-events.ndjson"):
        content = sanitized_events()
        if os.environ.get("FAKE_ADB_BAD_SANITIZED") == "1":
            content = content.replace(
                '"voiceSessionHash":"' + hash_value("1") + '",',
                '"voiceSessionId":"RAW_SESSION_SECRET",',
                1,
            )
        encoded = content.encode()
        if os.environ.get("FAKE_ADB_EMPTY_ARTIFACT") == "voice-experience-events.ndjson":
            return b""
        if os.environ.get("FAKE_ADB_INCOMPLETE_ARTIFACT") == "voice-experience-events.ndjson":
            return encoded.rstrip(b"\n")
        return encoded
    return None


argv = sys.argv[1:]
record(os.environ["FAKE_ADB_LOG"], argv)
state = load_state()

if argv == ["devices", "-l"]:
    print("List of devices attached")
    print(f'{state["serial"]} device product:phone model:Real device:real transport_id:1')
    if os.environ.get("FAKE_ADB_TWO_DEVICES") == "1":
        print("SECOND_DEVICE device product:phone model:Real device:real transport_id:2")
    raise SystemExit(0)

if len(argv) < 3 or argv[0] != "-s":
    raise SystemExit(2)
serial = argv[1]
command = argv[2:]
if serial != state["serial"]:
    raise SystemExit(3)

if len(command) > 3 and command[:3] == ["shell", "sh", "-c"] and "voice-step-service-status" in command[3]:
    if os.environ.get("FAKE_ADB_AMBIGUOUS_SERVICE") == "1":
        print("invalid")
    elif state.get("call_active"):
        print("active")
    else:
        print("stopped")
    raise SystemExit(0)

if command == ["shell", "echo", "ok"]:
    print("ok")
    raise SystemExit(0)
if command == ["shell", "getprop", "sys.boot_completed"]:
    print("1")
    raise SystemExit(0)
if command == ["shell", "getprop", "init.svc.bootanim"]:
    print("stopped")
    raise SystemExit(0)
if command == ["shell", "getprop", "ro.product.model"]:
    print("RealPhone")
    raise SystemExit(0)
if command == ["shell", "getprop", "ro.build.version.release"]:
    print("16")
    raise SystemExit(0)
if command == ["shell", "getprop", "ro.kernel.qemu"]:
    print("1" if os.environ.get("FAKE_ADB_EMULATOR") == "1" else "0")
    raise SystemExit(0)
if command == ["shell", "getprop", "ro.hardware"]:
    print("ranchu" if os.environ.get("FAKE_ADB_EMULATOR") == "1" else "physical-hardware")
    raise SystemExit(0)
if command == ["shell", "pm", "path", EXPECTED_PACKAGE]:
    print(f"package:/data/app/{EXPECTED_PACKAGE}/base.apk")
    raise SystemExit(0)
if command == ["shell", "dumpsys", "package", EXPECTED_PACKAGE]:
    print(f"Package [{EXPECTED_PACKAGE}]")
    print("  flags=[ DEBUGGABLE HAS_CODE ]")
    print("  VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=true")
    print(f"  {EXPECTED_PACKAGE}/{SERVICE}")
    print(f"  {EXPECTED_PACKAGE}/{CONTROL}")
    print(f"  {EXPECTED_PACKAGE}/{FIXTURE}")
    raise SystemExit(0)
if command[:3] == ["shell", "run-as", EXPECTED_PACKAGE]:
    if os.environ.get("FAKE_ADB_NO_RUN_AS") == "1":
        raise SystemExit(1)
    tail = command[3:]
    if tail == ["id"]:
        print("uid=12345(u0_a123) gid=12345(u0_a123)")
        raise SystemExit(0)
    if tail and tail[0] == "mkdir":
        raise SystemExit(0)
    if tail[:2] == ["sh", "-c"]:
        script = tail[2] if len(tail) > 2 else ""
        if "voice-step-protected-root" in script:
            print("ready")
            raise SystemExit(0)
        if "voice-step-trace-probe" in script:
            print("present")
            raise SystemExit(0)
        if "cat > \"$1\"" in script:
            remote_path = tail[-1]
            content = sys.stdin.buffer.read()
            state.setdefault("remote_files", {})[remote_path] = {
                "size": len(content),
                "hash": "sha256:" + hashlib.sha256(content).hexdigest(),
            }
            save_state(state)
            raise SystemExit(0)
        if "voice-step-fixture-metadata" in script:
            remote_path = tail[-1]
            metadata = state.get("remote_files", {}).get(remote_path)
            if metadata is None:
                raise SystemExit(1)
            print(f'{metadata["size"]}\n{metadata["hash"]}')
            raise SystemExit(0)
        if "voice-step-artifact-presence" in script:
            for remote_path in tail[-3:]:
                if artifact_content(remote_path, state) is None:
                    raise SystemExit(1)
            print("present\npresent\npresent")
            raise SystemExit(0)
        if "voice-step-source-metadata" in script:
            remote_path = tail[-1]
            content = artifact_content(remote_path, state)
            if content is None:
                raise SystemExit(1)
            state["metadata_reads"] = state.get("metadata_reads", 0) + 1
            save_state(state)
            suffix = state["metadata_reads"] if os.environ.get("FAKE_ADB_ARTIFACT_CHANGES") == "1" else 0
            print(f"regular:600:{len(content)}:1:1:{suffix}")
            raise SystemExit(0)
    if tail[:2] == ["rm", "-rf"]:
        if os.environ.get("FAKE_ADB_FAIL_REMOVE") == "1":
            raise SystemExit(1)
        state["fixtures_removed"] = True
        save_state(state)
        raise SystemExit(0)

if command[:4] == ["shell", "am", "broadcast", "--user"]:
    action = command[command.index("-a") + 1]
    values = extras(command)
    if os.environ.get("FAKE_ADB_MALFORMED_BROADCAST") == action:
        print("uncontrolled malformed receiver output")
        raise SystemExit(0)
    if action.endswith(".STATUS"):
        if os.environ.get("FAKE_ADB_FAIL_STATUS") == "1":
            raise SystemExit(1)
        status_destination = os.environ.get("FAKE_ADB_CREATE_DESTINATION_ON_STATUS")
        if status_destination:
            Path(status_destination).write_text("raced", encoding="utf-8")
        data = "\n".join(
            [
                "status=ok",
                "action=status",
                f'run_state={state["automation_state"]}',
                f'run_hash={state["run_hash"] if state["automation_state"] != "idle" else "none"}',
                f'comparison_hash={state["comparison_hash"] if state["automation_state"] != "idle" else "none"}',
                f'requested_transport={state["transport"] if state["automation_state"] != "idle" else "none"}',
                f'event_count={state.get("event_count", 17)}',
                "network=wifi",
                "validated=true",
            ]
        )
        complete(0, data)
        raise SystemExit(0)
    if action.endswith(".PREPARE"):
        state["automation_state"] = "active"
        state["run_hash"] = values["run_hash"]
        state["comparison_hash"] = values["comparison_hash"]
        state["transport"] = values["transport"]
        save_state(state)
        data = "status=ok\naction=prepare"
    elif action.endswith(".MARK"):
        data = f'status=ok\naction=mark\nboundary={values.get("boundary", "")}'
    elif action.endswith(".FINALIZE"):
        state["automation_state"] = "finalized"
        save_state(state)
        data = "status=ok\naction=finalize"
    elif action.endswith("ARM_CAPTURE_FIXTURE"):
        data = f'status=ok\naction=arm\ntoken={state["fixture_token"]}'
    elif action.endswith("STAGE_CAPTURE_FIXTURE"):
        if os.environ.get("FAKE_ADB_STAGE_REJECT") == "1":
            complete(1, "status=error\nerror=invalid_request")
            raise SystemExit(0)
        data = "status=ok\naction=stage\naccepted=true"
    elif action.endswith("TRIGGER_CAPTURE_FIXTURE"):
        if os.environ.get("FAKE_ADB_TRIGGER_REJECT") == "1":
            complete(1, "status=error\nerror=invalid_request")
            raise SystemExit(0)
        data = "status=ok\naction=trigger\naccepted=true"
    else:
        complete(1, "status=error\nerror=invalid_request")
        raise SystemExit(0)
    complete(0, data)
    raise SystemExit(0)

if command[:3] == ["shell", "am", "start-foreground-service"]:
    action = command[command.index("-a") + 1]
    if action.endswith(".END") and os.environ.get("FAKE_ADB_FAIL_END") == "1":
        raise SystemExit(1)
    if action.endswith(".START"):
        state["call_active"] = True
        state["trace_id"] = "trace-new"
    elif action.endswith(".END") and os.environ.get("FAKE_ADB_SERVICE_STAYS_ACTIVE") != "1":
        state["call_active"] = False
    save_state(state)
    print("Starting service: controlled")
    raise SystemExit(0)

if command == ["shell", "dumpsys", "activity", "services", EXPECTED_PACKAGE]:
    if state.get("call_active"):
        print(f"ServiceRecord{{controlled {EXPECTED_PACKAGE}/{SERVICE}}}")
    else:
        print("ACTIVITY MANAGER SERVICES (dumpsys activity services)")
    raise SystemExit(0)

if command[:4] == ["exec-out", "run-as", EXPECTED_PACKAGE, "cat"]:
    remote_path = command[4]
    if remote_path.endswith("latest-trace-id.txt"):
        if (
            os.environ.get("FAKE_ADB_SIGNAL_ON_TRACE") == "1"
            and state.get("call_active")
            and state.get("signal_sent") is not True
        ):
            state["signal_sent"] = True
            save_state(state)
            os.kill(os.getppid(), signal.SIGTERM)
            raise SystemExit(143)
        destination = os.environ.get("FAKE_ADB_CREATE_DESTINATION_ON_TRACE")
        if destination and state.get("call_active") and state.get("destination_created") is not True:
            Path(destination).write_text("raced", encoding="utf-8")
            state["destination_created"] = True
            save_state(state)
        print(state.get("trace_id", "trace-old"))
        raise SystemExit(0)
    content = artifact_content(remote_path, state)
    if content is None:
        raise SystemExit(1)
    state["artifact_reads"] = state.get("artifact_reads", 0) + 1
    artifact_read = state["artifact_reads"]
    destination = os.environ.get("FAKE_ADB_CREATE_CAPTURE_DESTINATION")
    create_on_read = int(os.environ.get("FAKE_ADB_CREATE_CAPTURE_ON_READ", "0"))
    if destination and artifact_read == create_on_read:
        Path(destination).write_text("raced", encoding="utf-8")
    signal_on_read = int(os.environ.get("FAKE_ADB_SIGNAL_ON_ARTIFACT_READ", "0"))
    save_state(state)
    if signal_on_read and artifact_read == signal_on_read:
        os.kill(os.getppid(), signal.SIGTERM)
        raise SystemExit(143)
    if os.environ.get("FAKE_ADB_ARTIFACT_CHANGES") == "1":
        if artifact_read % 2 == 0:
            content += b'{"changed":true}\n'
    sys.stdout.buffer.write(content)
    raise SystemExit(0)

raise SystemExit(9)
PY
chmod 700 "$BIN_DIR/adb"

export PATH="$BIN_DIR:$PATH"
export FAKE_ADB_LOG="$ADB_LOG"
export FAKE_TIMEOUT_LOG="$TIMEOUT_LOG"
export FAKE_ADB_STATE="$FAKE_STATE"
export VOICE_STEP_ADB_TIMEOUT_SECONDS=10
export VOICE_STEP_WAIT_TIMEOUT_SECONDS=2
export VOICE_STEP_MAX_WAIT_ATTEMPTS=2
export VOICE_STEP_POLL_SECONDS=0

reset_fake() {
  : > "$ADB_LOG"
  : > "$TIMEOUT_LOG"
  cat > "$FAKE_STATE" <<'JSON'
{"serial":"DEVICE_SECRET_123","automation_state":"idle","run_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","comparison_hash":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","transport":"livekit_experimental","fixture_token":"fixture-1","trace_id":"trace-old","call_active":false,"event_count":17,"remote_files":{}}
JSON
  chmod 600 "$FAKE_STATE"
  unset FAKE_ADB_TWO_DEVICES FAKE_ADB_EMULATOR FAKE_ADB_NO_RUN_AS FAKE_TIMEOUT_EXIT
  unset FAKE_ADB_MALFORMED_BROADCAST FAKE_ADB_SIGNAL_ON_TRACE
  unset FAKE_ADB_CREATE_DESTINATION_ON_TRACE FAKE_ADB_STAGE_REJECT
  unset FAKE_ADB_TRIGGER_REJECT FAKE_ADB_SERVICE_STAYS_ACTIVE
  unset FAKE_ADB_ARTIFACT_CHANGES FAKE_ADB_MISSING_ARTIFACT FAKE_ADB_BAD_SANITIZED
  unset FAKE_ADB_EMPTY_ARTIFACT FAKE_ADB_INCOMPLETE_ARTIFACT
  unset FAKE_ADB_CREATE_CAPTURE_DESTINATION FAKE_ADB_CREATE_CAPTURE_ON_READ
  unset FAKE_ADB_SIGNAL_ON_ARTIFACT_READ FAKE_ADB_FAIL_END FAKE_ADB_FAIL_REMOVE
  unset FAKE_ADB_FAIL_STATUS FAKE_ADB_AMBIGUOUS_SERVICE
  unset FAKE_ADB_CREATE_DESTINATION_ON_STATUS
}

make_fixture() {
  local destination="$1"
  printf '\001\002\003\004\005\006\007\010' > "$destination"
  chmod 600 "$destination"
}

make_second_fixture() {
  local destination="$1"
  printf '\011\012\013\014\015\016\017\020' > "$destination"
  chmod 600 "$destination"
}

activate_fake_run() {
  python3 - "$FAKE_STATE" <<'PY'
import json
import os
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    state = json.load(handle)
state["automation_state"] = "active"
state["call_active"] = True
state["trace_id"] = "trace-new"
temporary = path + ".active"
with open(temporary, "w", encoding="utf-8") as handle:
    json.dump(state, handle, separators=(",", ":"))
os.replace(temporary, path)
PY
}

finalize_fake_run() {
  local call_active="${1:-false}"
  python3 - "$FAKE_STATE" "$call_active" <<'PY'
import json
import os
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    state = json.load(handle)
state["automation_state"] = "finalized"
state["call_active"] = sys.argv[2] == "true"
state["trace_id"] = "trace-new"
temporary = path + ".finalized"
with open(temporary, "w", encoding="utf-8") as handle:
    json.dump(state, handle, separators=(",", ":"))
os.replace(temporary, path)
PY
}

assert_no_capture_temps() {
  local directory="$1"
  if find "$directory" -maxdepth 1 -name '.voice-step-capture.*' -print -quit | grep -q .; then
    fail "capture-cleanup test: unpublished same-directory temporary file remained"
  fi
}

write_valid_state() {
  local destination="$1"
  local package="${2:-me.rerere.rikkahub.debug}"
  python3 - "$destination" "$package" <<'PY'
import json
import os
import sys

payload = {
    "schemaVersion": 1,
    "serial": "DEVICE_SECRET_123",
    "package": sys.argv[2],
    "conversationId": "CONVERSATION_SECRET_123",
    "runHash": "sha256:" + "a" * 64,
    "comparisonHash": "sha256:" + "b" * 64,
    "fixtureToken": "fixture-1",
    "traceId": "trace-new",
    "transport": "livekit_experimental",
}
descriptor = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, separators=(",", ":"))
    handle.write("\n")
PY
}

run_helper() {
  : > "$STDOUT_FILE"
  : > "$STDERR_FILE"
  set +e
  "$HELPER" "$@" >"$STDOUT_FILE" 2>"$STDERR_FILE"
  RUN_STATUS=$?
  set -e
}

assert_private_output_absent() {
  local combined
  combined="$(<"$STDOUT_FILE")$(<"$STDERR_FILE")"
  local marker
  for marker in \
    DEVICE_SECRET_123 CONVERSATION_SECRET_123 PRIVATE_TRACE RAW_SESSION_SECRET \
    fixture-1 trace-new trace-old \
    fixture-secret PROMPT_SECRET TRANSCRIPT_SECRET ANSWER_SECRET; do
    [[ "$combined" != *"$marker"* ]] || fail "private-output test: helper disclosed a private value"
  done
}

assert_exact_output() {
  local expected="$1"
  if [[ "$RUN_STATUS" -ne 0 ]]; then
    if [[ "$(<"$STDERR_FILE")" =~ ^voice-step.error=[A-Za-z[:space:]-]+$ ]]; then
      fail "success-output test: operation failed ($(tr '\n' ' ' < "$STDERR_FILE"))"
    fi
    fail "success-output test: operation failed"
  fi
  if [[ "$(<"$STDOUT_FILE")" != "$expected" ]]; then
    if [[ "$(<"$STDOUT_FILE")" =~ ^voice-step\.[A-Za-z_]+=[A-Za-z_]+([[:space:]]+voice-step\.[A-Za-z_]+=[A-Za-z_]+)*$ ]]; then
      fail "success-output test: stdout was not the fixed contract ($(tr '\n' ' ' < "$STDOUT_FILE"))"
    fi
    fail "success-output test: stdout was not the fixed contract"
  fi
  [[ ! -s "$STDERR_FILE" ]] || fail "success-output test: successful operation wrote stderr"
  assert_private_output_absent
  pass
}

assert_rejected() {
  local command_text="$1"
  local expected="$2"
  local -a arguments=()
  if [[ -n "$command_text" ]]; then
    read -r -a arguments <<< "$command_text"
  fi
  reset_fake
  run_helper "${arguments[@]}"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "rejection test: invalid invocation succeeded"
  [[ "$(<"$STDERR_FILE")" == *"$expected"* ]] || fail "rejection test: fixed diagnostic mismatch"
  [[ ! -s "$STDOUT_FILE" ]] || fail "rejection test: invalid invocation wrote stdout"
  assert_private_output_absent
  pass
}

assert_operation_exists() {
  local operation="$1"
  reset_fake
  run_helper "$operation"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "operation-exists test: incomplete invocation succeeded"
  [[ "$(<"$STDERR_FILE")" != *"invalid operation"* ]] || fail "operation-exists test: named operation is absent"
  pass
}

command_count() {
  local needle="$1"
  python3 - "$ADB_LOG" "$needle" <<'PY'
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
needle = sys.argv[2].encode()
print(sum(any(needle in argument for argument in command) for command in commands))
PY
}

command_sequence_present() {
  python3 - "$ADB_LOG" "$@" <<'PY'
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
needles = [value.encode() for value in sys.argv[2:]]
position = -1
for needle in needles:
    try:
        position = next(
            index
            for index in range(position + 1, len(commands))
            if any(needle in argument for argument in commands[index])
        )
    except StopIteration:
        raise SystemExit(1)
PY
}

assert_no_adb_mutations() {
  python3 - "$ADB_LOG" <<'PY' || fail "validation-order test: ADB mutation occurred after failed validation"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
mutation_tokens = {
    b"start-foreground-service",
    b"mkdir",
    b"rm",
}
mutation_actions = {
    b"me.rerere.rikkahub.voiceagent.automation.PREPARE",
    b"me.rerere.rikkahub.voiceagent.automation.MARK",
    b"me.rerere.rikkahub.voiceagent.automation.FINALIZE",
    b"me.rerere.rikkahub.debug.voiceagent.ARM_CAPTURE_FIXTURE",
    b"me.rerere.rikkahub.debug.voiceagent.STAGE_CAPTURE_FIXTURE",
    b"me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE",
}
for command in commands:
    if (
        any(token in mutation_tokens for token in command)
        or any(action in command for action in mutation_actions)
        or b'cat > "$1"' in command
    ):
        raise SystemExit(1)
PY
}

selected() {
  local operation="$1"
  [[ "$#" -gt 0 ]]
  if [[ "$SELECT_ALL" -eq 1 ]]; then
    return 0
  fi
  local requested
  for requested in "${SELECTED_OPERATIONS[@]}"; do
    [[ "$requested" == "$operation" ]] && return 0
  done
  return 1
}

run_general_validation_tests() {
  assert_rejected '' 'usage: voice-agent-real-room-step.sh OPERATION [options]'
  assert_rejected 'unknown' 'invalid operation'
  local operation
  for operation in preflight start inject interrupt status finalize capture end; do
    assert_operation_exists "$operation"
    assert_rejected "$operation --unknown-option value" 'unknown option'
  done

  assert_rejected 'inject --role invalid' 'invalid fixture role'
  assert_rejected 'preflight --serial DEVICE_SECRET_123 --serial SECOND --package me.rerere.rikkahub.debug' 'repeated option'
  assert_rejected 'start --state one --state two' 'repeated option'
  assert_rejected 'inject --state one --state two' 'repeated option'
  assert_rejected 'interrupt --fixture one --fixture two' 'repeated option'
  assert_rejected 'status --state one --state two' 'repeated option'
  assert_rejected 'finalize --state one --state two' 'repeated option'
  assert_rejected 'capture --automation-output one --automation-output two' 'repeated option'
  assert_rejected 'end --cleanup-output one --cleanup-output two' 'repeated option'

  reset_fake
  local fixture="$TMP_DIR/fixture-validation.pcm"
  make_fixture "$fixture"
  run_helper start --state relative-state --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "absolute-output test: relative start state succeeded"
  assert_no_adb_mutations
  pass

  local state="$TMP_DIR/validation-state.json"
  write_valid_state "$state"
  run_helper capture --state "$state" --automation-output relative-output \
    --private-voice-output "$TMP_DIR/private.ndjson" \
    --sanitized-voice-output "$TMP_DIR/sanitized.ndjson"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "absolute-output test: relative capture output succeeded"
  assert_no_adb_mutations
  pass

  run_helper capture --state "$state" --automation-output "$TMP_DIR/alias.ndjson" \
    --private-voice-output "$TMP_DIR/alias.ndjson" \
    --sanitized-voice-output "$TMP_DIR/sanitized-alias.ndjson"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "output-alias test: aliased destinations succeeded"
  assert_no_adb_mutations
  pass

  local invalid_output="$TMP_DIR/invalid-output"
  mkdir "$invalid_output"
  run_helper capture --state "$state" --automation-output "$invalid_output" \
    --private-voice-output "$TMP_DIR/private-directory-test.ndjson" \
    --sanitized-voice-output "$TMP_DIR/sanitized-directory-test.ndjson"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "output-type test: directory destination succeeded"
  assert_no_adb_mutations
  pass
  rmdir "$invalid_output"

  mkfifo "$invalid_output"
  run_helper capture --state "$state" --automation-output "$invalid_output" \
    --private-voice-output "$TMP_DIR/private-fifo-test.ndjson" \
    --sanitized-voice-output "$TMP_DIR/sanitized-fifo-test.ndjson"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "output-type test: FIFO destination succeeded"
  assert_no_adb_mutations
  pass
  rm "$invalid_output"

  ln -s "$TMP_DIR/not-created" "$invalid_output"
  run_helper capture --state "$state" --automation-output "$invalid_output" \
    --private-voice-output "$TMP_DIR/private-symlink-test.ndjson" \
    --sanitized-voice-output "$TMP_DIR/sanitized-symlink-test.ndjson"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "output-type test: symlink destination succeeded"
  assert_no_adb_mutations
  pass
  rm "$invalid_output"

  run_helper end --state "$state" --cleanup-output relative-cleanup
  [[ "$RUN_STATUS" -ne 0 ]] || fail "absolute-output test: relative cleanup output succeeded"
  assert_no_adb_mutations
  pass

  local invalid="$TMP_DIR/invalid-input"
  mkdir "$invalid"
  run_helper start --state "$TMP_DIR/directory-state.json" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$invalid"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-type test: directory fixture succeeded"
  assert_no_adb_mutations
  pass
  rmdir "$invalid"

  mkfifo "$invalid"
  run_helper start --state "$TMP_DIR/fifo-state.json" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$invalid"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-type test: FIFO fixture succeeded"
  assert_no_adb_mutations
  pass
  rm "$invalid"

  ln -s "$fixture" "$invalid"
  run_helper start --state "$TMP_DIR/symlink-state.json" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$invalid"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-type test: symlink fixture succeeded"
  assert_no_adb_mutations
  pass
  rm "$invalid"

  chmod 644 "$fixture"
  run_helper start --state "$TMP_DIR/mode-state.json" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-mode test: permissive fixture succeeded"
  assert_no_adb_mutations
  pass
  chmod 600 "$fixture"

  : > "$fixture"
  run_helper start --state "$TMP_DIR/empty-state.json" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-content test: zero-byte PCM succeeded"
  assert_no_adb_mutations
  pass

  local malformed="$TMP_DIR/malformed-state.json"
  printf '{not-json}\n' > "$malformed"
  chmod 600 "$malformed"
  run_helper status --state "$malformed"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-schema test: malformed state succeeded"
  assert_no_adb_mutations
  pass

  local wrong_package="$TMP_DIR/wrong-package-state.json"
  write_valid_state "$wrong_package" me.rerere.rikkahub.release
  run_helper status --state "$wrong_package"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-package test: wrong package succeeded"
  assert_no_adb_mutations
  pass

  local state_link="$TMP_DIR/state-link.json"
  ln -s "$state" "$state_link"
  run_helper status --state "$state_link"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-type test: symlink state succeeded"
  assert_no_adb_mutations
  pass


  local state_directory="$TMP_DIR/state-directory"
  mkdir "$state_directory"
  run_helper status --state "$state_directory"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-type test: directory state succeeded"
  assert_no_adb_mutations
  pass
  rmdir "$state_directory"

  local state_fifo="$TMP_DIR/state-fifo"
  mkfifo "$state_fifo"
  run_helper status --state "$state_fifo"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-type test: FIFO state succeeded"
  assert_no_adb_mutations
  pass
  rm "$state_fifo"

  chmod 644 "$state"
  run_helper status --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-mode test: permissive state succeeded"
  assert_no_adb_mutations
  pass

  reset_fake
  export FAKE_ADB_TWO_DEVICES=1
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "device-count test: multiple devices succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  export FAKE_ADB_EMULATOR=1
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "physical-device test: emulator properties succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  export FAKE_ADB_NO_RUN_AS=1
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "run-as test: missing run-as succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  export FAKE_TIMEOUT_EXIT=124
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "timeout test: timed-out ADB succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  VOICE_STEP_ADB_TIMEOUT_SECONDS=0 run_helper preflight \
    --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "timeout-validation test: zero timeout succeeded"
  [[ ! -s "$ADB_LOG" ]] || fail "timeout-validation test: ADB ran before timeout validation"
  pass
}

run_preflight_tests() {
  reset_fake
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=preflight\nvoice-step.device=ready\nvoice-step.package=ready\nvoice-step.automation=ready\nvoice-step.protected_path=ready'
  [[ "$(command_count devices)" == "2" ]] || fail "preflight-command test: device-ready helper was not reused after exact enumeration"
  [[ "$(command_count broadcast)" == "1" && "$(command_count .STATUS)" == "1" ]] ||
    fail "preflight-read-only test: STATUS was not the sole broadcast"
  [[ "$(command_count start-foreground-service)" == "0" ]] || fail "preflight-read-only test: service was started"
  [[ "$(command_count mkdir)" == "0" ]] || fail "preflight-read-only test: remote directory was created"
  [[ "$(command_count rm)" == "0" ]] || fail "preflight-read-only test: remote file was removed"
  pass

  reset_fake
  python3 - "$FAKE_STATE" <<'PY'
import json, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["automation_state"] = "active"
json.dump(state, open(path, "w", encoding="utf-8"), separators=(",", ":"))
PY
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "preflight-idle test: active automation succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass
}

run_start_tests() {
  reset_fake
  local fixture="$TMP_DIR/start-fixture.pcm"
  local state="$TMP_DIR/start-state.json"
  make_fixture "$fixture"
  run_helper start --state "$state" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  [[ -f "$state" && ! -L "$state" && "$(stat -c '%a' "$state")" == "600" ]] ||
    fail "start-publication test: state was not a mode-0600 regular file"
  python3 - "$state" <<'PY' || fail "start-state test: private state contract mismatch"
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
assert list(state) == ["schemaVersion", "serial", "package", "conversationId", "runHash", "comparisonHash", "fixtureToken", "traceId", "transport"]
assert state == {
    "schemaVersion": 1,
    "serial": "DEVICE_SECRET_123",
    "package": "me.rerere.rikkahub.debug",
    "conversationId": "CONVERSATION_SECRET_123",
    "runHash": "sha256:" + "a" * 64,
    "comparisonHash": "sha256:" + "b" * 64,
    "fixtureToken": "fixture-1",
    "traceId": "trace-new",
    "transport": "livekit_experimental",
}
PY
  command_sequence_present mkdir 'umask 077; cat > "$1"' PREPARE ARM_CAPTURE_FIXTURE start-foreground-service ||
    fail "start-order test: one start mutation sequence was not preserved"
  [[ "$(command_count PREPARE)" == "1" ]] || fail "start-retry test: PREPARE was not sent exactly once"
  [[ "$(command_count ARM_CAPTURE_FIXTURE)" == "1" ]] || fail "start-retry test: ARM was not sent exactly once"
  [[ "$(command_count start-foreground-service)" == "1" ]] || fail "start-retry test: START was not sent exactly once"
  pass

  reset_fake
  local raced="$TMP_DIR/raced-state.json"
  export FAKE_ADB_CREATE_DESTINATION_ON_TRACE="$raced"
  run_helper start --state "$raced" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "start-race test: late destination race succeeded"
  [[ "$(<"$raced")" == "raced" ]] || fail "start-race test: existing destination was overwritten"
  [[ "$(command_count FINALIZE)" == "1" ]] || fail "start-race cleanup test: automation was not finalized once"
  [[ "$(command_count .END)" == "1" ]] || fail "start-race cleanup test: call was not ended once"
  assert_private_output_absent
  pass

  reset_fake
  local signaled="$TMP_DIR/signaled-state.json"
  export FAKE_ADB_SIGNAL_ON_TRACE=1
  run_helper start --state "$signaled" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "start-signal test: interrupted start succeeded"
  [[ ! -e "$signaled" ]] || fail "start-signal test: interrupted state was published"
  [[ "$(command_count FINALIZE)" == "1" ]] || fail "start-signal cleanup test: automation was not finalized once"
  [[ "$(command_count .END)" == "1" ]] || fail "start-signal cleanup test: call was not ended once"
  assert_private_output_absent
  pass
}

run_inject_tests() {
  local state="$TMP_DIR/inject-state.json"
  local fixture="$TMP_DIR/inject-fixture.pcm"
  local second="$TMP_DIR/inject-second.pcm"
  local role
  for role in request follow_up interruption; do
    reset_fake
    activate_fake_run
    rm -f -- "$state"
    write_valid_state "$state"
    make_fixture "$fixture"
    run_helper inject --state "$state" --fixture "$fixture" --role "$role"
    assert_exact_output $'voice-step.status=ok\nvoice-step.operation=inject\nvoice-step.fixture=accepted'
    [[ "$(command_count STAGE_CAPTURE_FIXTURE)" == "1" ]] ||
      fail "inject-stage test: fixture was not staged exactly once"
    [[ "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "1" ]] ||
      fail "inject-trigger test: fixture was not triggered exactly once"
    [[ "$(command_count PREPARE)" == "0" && "$(command_count FINALIZE)" == "0" &&
       "$(command_count start-foreground-service)" == "0" ]] ||
      fail "inject-scope test: injection changed call lifecycle"
    python3 - "$ADB_LOG" "$role" <<'PY' || fail "inject-path test: role/hash path or broadcast extras were wrong"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
role = sys.argv[2]
expected_path = (
    "files/voice-real-room/" + "a" * 64 + "/" + role +
    "-66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72.pcm"
).encode()
stream = [command for command in commands if b'umask 077; cat > "$1"' in command]
stage = [command for command in commands if any(b"STAGE_CAPTURE_FIXTURE" in value for value in command)]
trigger = [command for command in commands if any(b"TRIGGER_CAPTURE_FIXTURE" in value for value in command)]
assert len(stream) == len(stage) == len(trigger) == 1
assert stream[0][-1] == expected_path
assert b"exec-in" not in stream[0]
assert expected_path in stage[0] and expected_path in trigger[0]
assert b"fixture-1" in stage[0] and b"fixture-1" in trigger[0]
assert b"chunk_bytes" in stage[0] and b"3200" in stage[0]
assert b"chunk_delay_ms" in stage[0] and b"100" in stage[0]
PY
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  make_fixture "$fixture"
  make_second_fixture "$second"
  run_helper inject --state "$state" --fixture "$fixture" --role request
  [[ "$RUN_STATUS" -eq 0 ]] || fail "inject-repeat test: first distinct request failed"
  run_helper inject --state "$state" --fixture "$second" --role request
  [[ "$RUN_STATUS" -eq 0 ]] || fail "inject-repeat test: second distinct request failed"
  [[ "$(command_count STAGE_CAPTURE_FIXTURE)" == "2" &&
     "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "2" ]] ||
    fail "inject-repeat test: repeated distinct requests were retried or skipped"
  python3 - "$ADB_LOG" <<'PY' || fail "inject-repeat test: distinct request hashes aliased"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
paths = []
for command in commands:
    if any(b"STAGE_CAPTURE_FIXTURE" in value for value in command):
        paths.extend(value for value in command if value.startswith(b"files/voice-real-room/"))
assert paths == [
    b"files/voice-real-room/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/request-66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72.pcm",
    b"files/voice-real-room/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/request-74aeae04b0a57b8f19bb67f2b742072ee0b8f9ce5efd298f0134a16791c73607.pcm",
]
PY
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  make_fixture "$fixture"
  export FAKE_ADB_STAGE_REJECT=1
  run_helper inject --state "$state" --fixture "$fixture" --role request
  [[ "$RUN_STATUS" -ne 0 ]] || fail "inject-stale-token test: rejected stage succeeded"
  [[ "$(command_count STAGE_CAPTURE_FIXTURE)" == "1" &&
     "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "0" ]] ||
    fail "inject-stale-token test: rejected stage retried or triggered"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  make_fixture "$fixture"
  export FAKE_ADB_TRIGGER_REJECT=1
  run_helper inject --state "$state" --fixture "$fixture" --role request
  [[ "$RUN_STATUS" -ne 0 ]] || fail "inject-busy-source test: rejected trigger succeeded"
  [[ "$(command_count STAGE_CAPTURE_FIXTURE)" == "1" &&
     "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "1" ]] ||
    fail "inject-busy-source test: rejected trigger was retried"
  assert_private_output_absent
  pass
}

run_interrupt_tests() {
  local state="$TMP_DIR/interrupt-state.json"
  local fixture="$TMP_DIR/interrupt-fixture.pcm"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  make_fixture "$fixture"
  run_helper interrupt --state "$state" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=interrupt\nvoice-step.fixture=accepted'
  command_sequence_present '.MARK' STAGE_CAPTURE_FIXTURE TRIGGER_CAPTURE_FIXTURE ||
    fail "interrupt-order test: MARK did not precede stage and trigger"
  [[ "$(command_count .MARK)" == "1" && "$(command_count STAGE_CAPTURE_FIXTURE)" == "1" &&
     "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "1" ]] ||
    fail "interrupt-retry test: atomic interrupt step retried a mutation"
  python3 - "$ADB_LOG" <<'PY' || fail "interrupt-mark test: boundary/run hash extras were wrong"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
marks = [command for command in commands if any(value.endswith(b".MARK") for value in command)]
assert len(marks) == 1
assert b"boundary" in marks[0] and b"interrupt_started" in marks[0]
assert b"run_hash" in marks[0]
assert b"sha256:" + b"a" * 64 in marks[0]
PY
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  make_fixture "$fixture"
  export FAKE_ADB_MALFORMED_BROADCAST='me.rerere.rikkahub.voiceagent.automation.MARK'
  run_helper interrupt --state "$state" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "interrupt-reply test: malformed MARK succeeded"
  [[ "$(command_count .MARK)" == "1" && "$(command_count STAGE_CAPTURE_FIXTURE)" == "0" ]] ||
    fail "interrupt-reply test: injection ran after malformed MARK"
  assert_private_output_absent
  pass
}

run_status_tests() {
  local state="$TMP_DIR/status-state.json"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  run_helper status --state "$state"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=status\nvoice-step.run_state=active\nvoice-step.call_state=active\nvoice-step.event_count=17\nvoice-step.network=wifi\nvoice-step.validated=true\nvoice-step.voice_events=present\nvoice-step.job_accepted_count=2\nvoice-step.job_terminal_count=1\nvoice-step.delivery_blocked_count=0\nvoice-step.delivery_announced_count=1'
  [[ "$(command_count .STATUS)" == "1" && "$(command_count 'dumpsys')" == "1" &&
     "$(command_count voice-step-artifact-presence)" == "1" ]] ||
    fail "status-scope test: status retried or used an unrelated query"
  [[ "$(command_count PREPARE)" == "0" && "$(command_count start-foreground-service)" == "0" ]] ||
    fail "status-read-only test: status mutated the run"
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_BAD_SANITIZED=1
  run_helper status --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-canonical test: raw session identifier succeeded"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_MISSING_ARTIFACT='voice-experience-private.ndjson'
  run_helper status --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-presence test: missing private artifact succeeded"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  python3 - "$FAKE_STATE" <<'PY'
import json, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["run_hash"] = "sha256:" + "c" * 64
json.dump(state, open(path, "w", encoding="utf-8"), separators=(",", ":"))
PY
  run_helper status --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-binding test: mismatched run hash succeeded"
  assert_private_output_absent
  pass
}

run_finalize_tests() {
  local state="$TMP_DIR/finalize-state.json"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  run_helper finalize --state "$state"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.automation=finalized'
  command_sequence_present FINALIZE STATUS || fail "finalize-order test: finalized status was not read after FINALIZE"
  [[ "$(command_count FINALIZE)" == "1" && "$(command_count STATUS)" == "1" ]] ||
    fail "finalize-retry test: FINALIZE or status was retried"
  [[ "$(command_count start-foreground-service)" == "0" ]] ||
    fail "finalize-scope test: finalize ended the call"
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_MALFORMED_BROADCAST='me.rerere.rikkahub.voiceagent.automation.FINALIZE'
  run_helper finalize --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "finalize-reply test: malformed FINALIZE succeeded"
  [[ "$(command_count FINALIZE)" == "1" ]] || fail "finalize-reply test: malformed FINALIZE was retried"
  assert_private_output_absent
  pass
}

run_capture_tests() {
  local state="$TMP_DIR/capture-state.json"
  local output_dir="$TMP_DIR/capture-output"
  local automation="$output_dir/automation.jsonl"
  local private="$output_dir/private.ndjson"
  local sanitized="$output_dir/sanitized.ndjson"
  mkdir "$output_dir"

  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=capture\nvoice-step.artifacts=published'
  for output in "$automation" "$private" "$sanitized"; do
    [[ -f "$output" && ! -L "$output" && -s "$output" && "$(stat -c '%a' "$output")" == 600 ]] ||
      fail "capture-publication test: published artifact was not nonempty mode-0600 regular data"
  done
  if ! python3 - "$ADB_LOG" "$automation" "$private" "$sanitized" <<'PY'
import json
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
names = [b"automation-events.jsonl", b"voice-experience-private.ndjson", b"voice-experience-events.ndjson"]
for name in names:
    reads = [command for command in commands if b"exec-out" in command and b"cat" in command and any(value.endswith(name) for value in command)]
    assert len(reads) == 2
automation = open(sys.argv[2], "rb").read()
private = open(sys.argv[3], "rb").read()
sanitized = open(sys.argv[4], "rb").read()
assert automation.endswith(b"\n") and b'"name":"run_prepared"' in automation
assert private == b'{"private":"fixture-secret"}\n'
assert sanitized.endswith(b"\n") and b"voiceSessionId" not in sanitized and b"voiceSessionHash" in sanitized
PY
  then
    fail "capture-double-read test: sources were not read exactly twice or output content changed"
  fi
  assert_no_capture_temps "$output_dir"
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_ARTIFACT_CHANGES=1
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-stability test: changing source succeeded"
  [[ ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-stability test: unstable source published a destination"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass

  rm -f -- "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_INCOMPLETE_ARTIFACT='voice-experience-private.ndjson'
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-newline test: incomplete final newline succeeded"
  [[ ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-newline test: invalid source published a destination"
  assert_no_capture_temps "$output_dir"
  pass

  rm -f -- "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_CREATE_CAPTURE_DESTINATION="$automation"
  export FAKE_ADB_CREATE_CAPTURE_ON_READ=6
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-race test: late destination race succeeded"
  [[ "$(<"$automation")" == raced && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-race test: raced destination was overwritten or publication continued"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass

  rm -f -- "$automation" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_SIGNAL_ON_ARTIFACT_READ=3
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-signal test: interrupted capture succeeded"
  [[ ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-signal test: interrupted capture published a destination"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass
}

run_end_tests() {
  local state="$TMP_DIR/end-state.json"
  local cleanup_output="$TMP_DIR/end-cleanup.json"
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=complete'
  [[ "$(<"$cleanup_output")" == '{"schemaVersion":1,"outcome":"complete","callStopped":true,"fixturesRemoved":true,"automationFinalized":true}' ]] ||
    fail "end-record test: complete cleanup record mismatch"
  [[ -f "$cleanup_output" && ! -L "$cleanup_output" && "$(stat -c '%a' "$cleanup_output")" == 600 ]] ||
    fail "end-publication test: cleanup record was not a mode-0600 regular file"
  command_sequence_present '.END' voice-step-service-status 'rm' STATUS ||
    fail "end-order test: END, stop, removal, finalized status order changed"
  [[ "$(command_count .END)" == 1 && "$(command_count 'rm')" == 1 ]] ||
    fail "end-retry test: END or fixture removal retried"
  python3 - "$ADB_LOG" <<'PY' || fail "end-ownership test: fixture removal target widened"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
removals = [command for command in commands if b"rm" in command]
assert len(removals) == 1
assert removals[0][-1] == b"files/voice-real-room/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PY
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  export FAKE_ADB_FAIL_END=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=infrastructure_interruption'
  [[ "$(<"$cleanup_output")" == '{"schemaVersion":1,"outcome":"infrastructure_interruption","callStopped":false,"fixturesRemoved":false,"automationFinalized":false}' ]] ||
    fail "end-infrastructure test: interruption evidence mismatch"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  export FAKE_ADB_FAIL_REMOVE=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=infrastructure_interruption'
  [[ "$(<"$cleanup_output")" == '{"schemaVersion":1,"outcome":"infrastructure_interruption","callStopped":true,"fixturesRemoved":false,"automationFinalized":false}' ]] ||
    fail "end-partial-infrastructure test: proven cleanup booleans mismatch"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  export FAKE_ADB_SERVICE_STAYS_ACTIVE=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  [[ "$(<"$cleanup_output")" == '{"schemaVersion":1,"outcome":"product_failure","callStopped":false,"fixturesRemoved":false,"automationFinalized":true}' ]] ||
    fail "end-product-failure test: active-service evidence mismatch"
  [[ "$(command_count 'rm')" == 0 ]] || fail "end-product-failure test: fixtures removed while service stayed active"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  export FAKE_ADB_AMBIGUOUS_SERVICE=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-ambiguous test: ambiguous service readback published cleanup evidence"
  assert_private_output_absent
  pass

  rm -f -- "$state"
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  export FAKE_ADB_CREATE_DESTINATION_ON_STATUS="$cleanup_output"
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "end-race test: late cleanup destination race succeeded"
  [[ "$(<"$cleanup_output")" == raced ]] || fail "end-race test: existing cleanup destination was overwritten"
  assert_private_output_absent
  pass
}

SELECT_ALL=0
SELECTED_OPERATIONS=("$@")
if [[ "$#" -eq 0 ]]; then
  SELECT_ALL=1
fi
for requested in "${SELECTED_OPERATIONS[@]}"; do
  case "$requested" in
    preflight|start|inject|interrupt|status|finalize|capture|end) ;;
    *) fail "test filter must name a real-room operation" ;;
  esac
done

[[ -x "$HELPER" ]] || fail "voice-agent-real-room-step.sh does not exist"

if [[ "$SELECT_ALL" -eq 1 ]]; then
  run_general_validation_tests
fi
selected preflight && run_preflight_tests
selected start && run_start_tests
selected inject && run_inject_tests
selected interrupt && run_interrupt_tests
selected status && run_status_tests
selected finalize && run_finalize_tests
selected capture && run_capture_tests
selected end && run_end_tests

printf 'PASS: voice-agent-real-room-step (%s assertions)\n' "$TEST_COUNT"
