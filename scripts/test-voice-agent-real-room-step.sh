#!/usr/bin/env bash
set -euo pipefail

umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT_DIR/scripts/voice-agent-real-room-step.sh"
LIBRARY="$ROOT_DIR/scripts/voice-agent-real-room-lib.sh"
DIAGNOSTICS="$ROOT_DIR/scripts/voice-agent-real-room-diagnostics.sh"
REAL_TIMEOUT="$(command -v timeout)"
REAL_LN="$(command -v ln)"
REAL_RMDIR="$(command -v rmdir)"
REAL_RM="$(command -v rm)"
REAL_STAT="$(command -v stat)"
CURRENT_UID="$(id -u)"
MDEV_OWNER='OWNER_SECRET_123'
OTHER_MDEV_OWNER='OTHER_OWNER_SECRET_456'
TMP_DIR="$(mktemp -d)"
chmod 700 "$TMP_DIR"
BIN_DIR="$TMP_DIR/bin"
mkdir "$BIN_DIR"
chmod 700 "$BIN_DIR"
MDEV_LOG="$TMP_DIR/mdev.argv"
ADB_LOG="$MDEV_LOG"
TIMEOUT_LOG="$TMP_DIR/timeout.argv"
LN_LOG="$TMP_DIR/ln.argv"
FAKE_STATE="$TMP_DIR/fake-state.json"
STDOUT_FILE="$TMP_DIR/stdout"
STDERR_FILE="$TMP_DIR/stderr"
HELPER_TEMP_ROOT="$TMP_DIR/helper-private-temp"
mkdir "$HELPER_TEMP_ROOT"
chmod 700 "$HELPER_TEMP_ROOT"
REMOTE_APP_DATA_ROOT="$TMP_DIR/remote-app-data"
RMDIR_BOUNDARY_FILE="$TMP_DIR/rmdir-boundary"
TEST_COUNT=0
ACTOR_PID=''
declare -a LAST_PRIVATE_PATHS=()

cleanup() {
  if [[ -n "$ACTOR_PID" ]] && kill -0 "$ACTOR_PID" 2>/dev/null; then
    kill -CONT "$ACTOR_PID" 2>/dev/null || true
    kill -TERM "$ACTOR_PID" 2>/dev/null || true
    wait "$ACTOR_PID" 2>/dev/null || true
  fi
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
import subprocess
import sys


def record(path, argv):
    with open(path, "ab") as handle:
        for value in argv:
            handle.write(value.encode() + b"\0")
        handle.write(b"\0")


record(os.environ["FAKE_TIMEOUT_LOG"], sys.argv[1:])
if os.environ.get("FAKE_TIMEOUT_EXIT") == "124":
    raise SystemExit(124)
exit_match = os.environ.get("FAKE_TIMEOUT_EXIT_MATCH")
if exit_match and any(exit_match in argument for argument in sys.argv[1:]):
    raise SystemExit(124)
if os.environ.get("FAKE_TIMEOUT_ENFORCE") == "1":
    executable = os.environ["REAL_TIMEOUT"]
    os.execv(executable, [executable, *sys.argv[1:]])
arguments = sys.argv[1:]
while arguments and arguments[0].startswith("--"):
    arguments = arguments[1:]
if not arguments or not arguments[0].endswith("s"):
    raise SystemExit(125)
arguments = arguments[1:]
if not arguments:
    raise SystemExit(125)
execute_then_timeout = os.environ.get("FAKE_TIMEOUT_EXECUTE_THEN_TIMEOUT_MATCH")
if execute_then_timeout and any(execute_then_timeout in argument for argument in arguments):
    subprocess.run(arguments, check=False, env=os.environ)
    raise SystemExit(124)
os.execvpe(arguments[0], arguments, os.environ)
PY
chmod 700 "$BIN_DIR/timeout"

cat > "$BIN_DIR/ln" <<'PY'
#!/usr/bin/env python3
import os
import signal
import subprocess
import sys
from pathlib import Path


with open(os.environ["FAKE_LN_LOG"], "ab") as handle:
    for value in sys.argv[1:]:
        handle.write(value.encode() + b"\0")
    handle.write(b"\0")

destination = sys.argv[-1]
if os.environ.get("FAKE_LN_RACE_DESTINATION") == destination:
    Path(destination).write_text("raced", encoding="utf-8")
if os.environ.get("FAKE_LN_SIGNAL_DESTINATION") == destination:
    timing = os.environ.get("FAKE_LN_SIGNAL_TIMING")
    if timing == "before":
        os.kill(os.getppid(), signal.SIGTERM)
        raise SystemExit(143)
    if timing == "after":
        result = subprocess.run([os.environ["REAL_LN"], *sys.argv[1:]], check=False)
        os.kill(os.getppid(), signal.SIGTERM)
        raise SystemExit(result.returncode)
os.execv(os.environ["REAL_LN"], [os.environ["REAL_LN"], *sys.argv[1:]])
PY
chmod 700 "$BIN_DIR/ln"

cat > "$BIN_DIR/rmdir" <<'PY'
#!/usr/bin/env python3
import os
import time
import sys
from pathlib import Path


if os.environ.get("FAKE_BROKER_RMDIR_MODE") == "1":
    boundary = os.environ.get("FAKE_RMDIR_BOUNDARY_FILE")
    if boundary:
        Path(boundary).write_text("reached\n", encoding="utf-8")
    trigger = os.environ.get("FAKE_ADB_ACTOR_TRIGGER")
    result = os.environ.get("FAKE_ADB_ACTOR_RESULT")
    if trigger:
        Path(trigger).write_text("attempt\n", encoding="utf-8")
        time.sleep(0.1)
        if result and Path(result).exists():
            actor_entered = os.environ.get("FAKE_RMDIR_ACTOR_ENTERED")
            if actor_entered:
                Path(actor_entered).write_text("entered\n", encoding="utf-8")
            raise SystemExit(1)
    if os.environ.get("FAKE_RMDIR_FAIL") == "1":
        raise SystemExit(1)

executable = os.environ["REAL_RMDIR"]
os.execv(executable, [executable, *sys.argv[1:]])
PY
chmod 700 "$BIN_DIR/rmdir"

cat > "$BIN_DIR/rm" <<'PY'
#!/usr/bin/env python3
import os
import signal
import sys

match = os.environ.get("FAKE_RM_SIGNAL_MATCH")
if match and any(match in argument for argument in sys.argv[1:]):
    os.kill(os.getppid(), signal.SIGTERM)
os.execv(os.environ["REAL_RM"], [os.environ["REAL_RM"], *sys.argv[1:]])
PY
chmod 700 "$BIN_DIR/rm"

cat > "$BIN_DIR/mdev" <<'PY'
#!/usr/bin/env python3
import hashlib
import json
import os
import signal
import shlex
import subprocess
import sys
import time
from pathlib import Path

EXPECTED_PACKAGE = "me.rerere.rikkahub.debug"
CONTROL = "me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver"
FIXTURE = "me.rerere.rikkahub.voiceagent.debug.VoiceCaptureFixtureDebugReceiver"
SERVICE = "me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
STATE_PATH = Path(os.environ["FAKE_ADB_STATE"])
ANDROID_USER_ID = 0
FIXTURE_OWNERSHIP_NONCE = "0123456789abcdef0123456789abcdef"
REMOTE_APP_DATA_ROOT = Path(os.environ["FAKE_REMOTE_APP_DATA_ROOT"])


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
    suffix = " trailing-junk" if os.environ.get("FAKE_ADB_BROADCAST_TRAILING_JUNK") == "1" else ""
    print(f'Broadcast completed: result={result}, data="{data}"{suffix}')


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


def owner_hash(state):
    values = [
        "sha256:" + hashlib.sha256(os.environ["FAKE_MDEV_OWNER"].encode()).hexdigest(),
        EXPECTED_PACKAGE,
        state["conversation_id"],
        state["run_hash"],
        state["comparison_hash"],
    ]
    return "sha256:" + hashlib.sha256("\0".join(values).encode()).hexdigest()


def remote_host_path(remote_path):
    candidate = Path(remote_path)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError("invalid remote path")
    return REMOTE_APP_DATA_ROOT.joinpath(*candidate.parts)


def fixture_identity(path):
    metadata = path.stat()
    return ":".join([
        str(metadata.st_dev),
        str(metadata.st_ino),
        format(metadata.st_mode, "o"),
        str(metadata.st_uid),
        str(metadata.st_gid),
    ])


def create_real_owned_directory(state, remote_dir, ownership):
    directory = remote_host_path(remote_dir)
    directory.mkdir(mode=0o700)
    marker = directory / ".voice-step-owner"
    descriptor = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(ownership + "\n" + FIXTURE_OWNERSHIP_NONCE + "\n")
    state["remote_directory"] = remote_dir
    state["owner_hash"] = ownership
    state["fixture_parent_identity"] = fixture_identity(directory.parent)
    state["fixture_directory_identity"] = fixture_identity(directory)
    state["fixture_ownership_nonce"] = FIXTURE_OWNERSHIP_NONCE
    return directory


def maybe_block(arguments):
    needle = os.environ.get("FAKE_ADB_BLOCK_MATCH")
    if not needle or not any(needle in value for value in arguments):
        return
    ready = os.environ.get("FAKE_ADB_BLOCK_READY")
    release = os.environ.get("FAKE_ADB_BLOCK_RELEASE")
    if not ready or not release:
        raise SystemExit(97)
    Path(ready).write_text("ready\n", encoding="utf-8")
    deadline = time.monotonic() + 10
    while not Path(release).exists():
        if time.monotonic() >= deadline:
            raise SystemExit(98)
        time.sleep(0.01)


def process_state(pid):
    try:
        for line in Path(f"/proc/{pid}/status").read_text(encoding="utf-8").splitlines():
            if line.startswith("State:"):
                return line.split()[1]
    except (FileNotFoundError, ProcessLookupError):
        return None
    return None


def wait_for_process_state(pid, expected):
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        if process_state(pid) == expected:
            return True
        time.sleep(0.01)
    return False


def canonical_row(row):
    return json.dumps(row, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def private_event_rows():
    voice_session_id = "PRIVATE_TRACE"
    voice_session_hash = "sha256:" + hashlib.sha256(voice_session_id.encode()).hexdigest()
    job = {
        "userTurnId": "turn-1",
        "requestHash": hash_value("3"),
        "toolCallId": "tool-1",
        "argumentHash": hash_value("4"),
        "jobId": "job-1",
        "ownerHash": hash_value("5"),
        "conversationHash": hash_value("6"),
        "voiceSessionHash": voice_session_hash,
        "roomHash": hash_value("7"),
        "traceHash": hash_value("8"),
    }

    def base(sequence, kind):
        return {
            "version": 1,
            "voiceSessionId": voice_session_id,
            "eventId": f"event-{sequence}",
            "kind": kind,
            "observedAt": f"2026-08-03T00:00:{sequence:02d}Z",
        }

    binding = base(1, "session_binding")
    binding.update({key: job[key] for key in (
        "ownerHash", "conversationHash", "voiceSessionHash", "roomHash", "traceHash"
    )})
    accepted = base(2, "job_accepted")
    accepted.update(job)
    accepted["prompt"] = "PROMPT_SECRET"
    answer = "ANSWER_SECRET"
    result_hash = "sha256:" + hashlib.sha256(answer.encode()).hexdigest()
    succeeded = base(3, "job_succeeded")
    succeeded.update(job)
    succeeded["resultHash"] = result_hash
    succeeded["answer"] = answer
    rows = [binding, accepted, succeeded]
    for sequence, kind in ((4, "delivery_eligible"), (5, "speech_started"), (6, "delivery_started")):
        delivery = base(sequence, kind)
        delivery.update({"toolCallId": job["toolCallId"], "jobId": job["jobId"]})
        rows.append(delivery)
    transcript = base(7, "transcript")
    transcript.update({
        "turnId": "assistant-1",
        "role": "assistant",
        "text": "TRANSCRIPT_SECRET",
        "interrupted": False,
        "groundedJobId": job["jobId"],
        "groundedResultHash": result_hash,
    })
    rows.append(transcript)
    announced = base(8, "delivery_announced")
    announced.update({
        "toolCallId": job["toolCallId"],
        "jobId": job["jobId"],
        "assistantTurnId": "assistant-1",
    })
    rows.append(announced)
    return rows


def utf16_length(value):
    return len(value.encode("utf-16-le")) // 2


def sanitized_event_rows():
    rows = []
    for private in private_event_rows():
        kind = private["kind"]
        raw = canonical_row(private)
        public = {
            "version": private["version"],
            "voiceSessionHash": "sha256:" + hashlib.sha256(
                private["voiceSessionId"].encode()
            ).hexdigest(),
            "eventId": private["eventId"],
            "kind": kind,
            "observedAt": private["observedAt"],
            "eventHash": "sha256:" + hashlib.sha256(raw.encode()).hexdigest(),
        }
        if kind == "session_binding":
            fields = ("ownerHash", "conversationHash", "roomHash", "traceHash")
        elif kind == "job_accepted":
            fields = (
                "userTurnId", "requestHash", "toolCallId", "argumentHash", "jobId",
                "ownerHash", "conversationHash", "roomHash", "traceHash",
            )
            public["promptCharacterCount"] = utf16_length(private["prompt"])
        elif kind == "job_succeeded":
            fields = (
                "userTurnId", "requestHash", "toolCallId", "argumentHash", "jobId",
                "ownerHash", "conversationHash", "roomHash", "traceHash", "resultHash",
            )
            public["answerCharacterCount"] = utf16_length(private["answer"])
        elif kind == "transcript":
            fields = (
                "turnId", "role", "interrupted", "groundedJobId", "groundedResultHash",
            )
            public["textCharacterCount"] = utf16_length(private["text"])
        elif kind == "delivery_announced":
            fields = ("toolCallId", "jobId", "assistantTurnId")
        else:
            fields = ("toolCallId", "jobId")
        public.update({field: private[field] for field in fields})
        rows.append(public)

    if os.environ.get("FAKE_ADB_CHECKPOINT_FAILURE") == "single_delivery_order":
        rows[3], rows[4] = rows[4], rows[3]
    corruption = os.environ.get("FAKE_ADB_CAPTURE_CORRUPTION")
    if corruption == "private-reorder":
        pass
    elif corruption == "sanitized-reorder":
        rows[2], rows[3] = rows[3], rows[2]
    elif corruption == "sanitized-duplicate":
        rows.append(dict(rows[-1]))
    elif corruption == "sanitized-orphan":
        orphan = dict(rows[-1])
        orphan["eventId"] = "event-orphan"
        rows.append(orphan)
    elif corruption == "event-id":
        rows[2]["eventId"] = "event-mismatch"
    elif corruption == "kind":
        rows[2]["kind"] = "job_running"
    elif corruption == "timestamp":
        rows[2]["observedAt"] = "2026-08-03T00:01:03Z"
    elif corruption == "private-hash":
        rows[2]["eventHash"] = hash_value("0")
    elif corruption == "forbidden-field":
        rows[2]["answer"] = "ANSWER_SECRET"
    return rows


def private_events():
    rows = private_event_rows()
    corruption = os.environ.get("FAKE_ADB_CAPTURE_CORRUPTION")
    if corruption == "private-reorder":
        rows[2], rows[3] = rows[3], rows[2]
    elif corruption == "private-duplicate":
        rows.append(dict(rows[-1]))
    elif corruption == "private-orphan":
        orphan = dict(rows[-1])
        orphan["eventId"] = "event-private-orphan"
        rows.append(orphan)
    return "".join(canonical_row(row) + "\n" for row in rows)


def sanitized_events():
    return "".join(canonical_row(row) + "\n" for row in sanitized_event_rows())


def automation_event(state, sequence, name, *, observed_transport=None, succeeded=None):
    return {
        "schemaVersion": 1,
        "monotonicMs": sequence,
        "wallClockMs": 1_800_000_000_000 + sequence,
        "runHash": state["run_hash"],
        "comparisonHash": state["comparison_hash"],
        "requestedTransport": state["transport"],
        "observedTransport": observed_transport,
        "name": name,
        "route": None,
        "network": None,
        "lifecycle": None,
        "playbackEpoch": None,
        "byteCount": None,
        "rmsActive": None,
        "audioWindowMicros": None,
        "succeeded": succeeded,
        "correlationKind": None,
        "correlationHash": None,
        "requestedModelHash": None,
        "observedModelHash": None,
        "voiceHash": None,
        "instructionHash": None,
        "directAccountConfigurationHash": None,
        "conversationHash": None,
        "captureSource": None,
        "micBytes": None,
        "fixtureBytes": None,
    }


def automation_events(state):
    rows = [automation_event(state, 1, "run_prepared")]
    call_active_visible_after = int(
        os.environ.get("FAKE_ADB_CALL_ACTIVE_VISIBLE_AFTER", "0")
    )
    if (
        state.get("call_active_recorded")
        and state.get("automation_artifact_reads", 0) >= call_active_visible_after
    ):
        rows.append(
            automation_event(
                state,
                2,
                "call_active",
                observed_transport=state["transport"],
            )
        )
    stop_visible_after = int(os.environ.get("FAKE_ADB_DURABLE_STOP_VISIBLE_AFTER", "0"))
    stop_visible = state.get("automation_artifact_reads", 0) >= stop_visible_after
    if state.get("call_stopped_recorded") and stop_visible:
        rows.append(
            automation_event(
                state,
                3,
                "call_stopped",
                succeeded=os.environ.get("FAKE_ADB_CALL_STOP_FAILED") != "1",
            )
        )
    if state.get("run_finalized_recorded"):
        rows.append(automation_event(state, 4, "run_finalized"))
    malformed = os.environ.get("FAKE_ADB_MALFORMED_DURABLE_ENDING")
    if malformed == "stopped-false" and rows and rows[-1]["name"] in {"call_stopped", "run_finalized"}:
        for row in rows:
            if row["name"] == "call_stopped":
                row["succeeded"] = False
    elif malformed == "event-after-finalized" and any(row["name"] == "run_finalized" for row in rows):
        rows.append(automation_event(state, 5, "network_observed", succeeded=True))
    elif malformed == "binding-mismatch" and rows:
        rows[-1]["comparisonHash"] = hash_value("c")
    elif malformed == "missing-call-stopped":
        rows = [row for row in rows if row["name"] != "call_stopped"]
    elif malformed == "missing-run-finalized":
        rows = [row for row in rows if row["name"] != "run_finalized"]
    elif malformed == "noncanonical-keys" and rows:
        last = rows[-1]
        rows[-1] = {"name": last["name"], **{key: value for key, value in last.items() if key != "name"}}
    return "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)


def artifact_content(path, state):
    missing_name = os.environ.get("FAKE_ADB_MISSING_ARTIFACT")
    if missing_name and path.endswith(missing_name):
        return None
    if path.endswith("automation-events.jsonl"):
        content = automation_events(state).encode()
        if os.environ.get("FAKE_ADB_POST_CLEANUP_ARTIFACT_CHANGE") == "1" and state.get("cleanup_broker_completed"):
            content += b'{"changed_after_cleanup":true}\n'
        if os.environ.get("FAKE_ADB_EMPTY_ARTIFACT") == "automation-events.jsonl":
            return b""
        if os.environ.get("FAKE_ADB_INCOMPLETE_ARTIFACT") == "automation-events.jsonl":
            return content.rstrip(b"\n")
        if os.environ.get("FAKE_ADB_CAPTURE_CRLF") == "automation":
            return content.replace(b"\n", b"\r\n")
        return content
    if path.endswith("voice-experience-private.ndjson"):
        content = private_events().encode()
        if os.environ.get("FAKE_ADB_EMPTY_ARTIFACT") == "voice-experience-private.ndjson":
            return b""
        if os.environ.get("FAKE_ADB_INCOMPLETE_ARTIFACT") == "voice-experience-private.ndjson":
            return content.rstrip(b"\n")
        if os.environ.get("FAKE_ADB_CAPTURE_CRLF") == "private":
            return content.replace(b"\n", b"\r\n")
        return content
    if path.endswith("voice-experience-events.ndjson"):
        content = sanitized_events()
        if os.environ.get("FAKE_ADB_BAD_SANITIZED") == "1":
            session_hash = "sha256:" + hashlib.sha256(b"PRIVATE_TRACE").hexdigest()
            content = content.replace(
                '"voiceSessionHash":"' + session_hash + '"',
                '"voiceSessionId":"RAW_SESSION_SECRET"',
                1,
            )
        encoded = content.encode()
        if os.environ.get("FAKE_ADB_EMPTY_ARTIFACT") == "voice-experience-events.ndjson":
            return b""
        if os.environ.get("FAKE_ADB_INCOMPLETE_ARTIFACT") == "voice-experience-events.ndjson":
            return encoded.rstrip(b"\n")
        if os.environ.get("FAKE_ADB_CAPTURE_CRLF") == "sanitized":
            return encoded.replace(b"\n", b"\r\n")
        return encoded
    return None


closed_diagnostic_parent = os.environ.get("FAKE_ASSERT_CLOSED_DIAGNOSTIC_PARENT")
if closed_diagnostic_parent:
    for descriptor in os.listdir("/proc/self/fd"):
        try:
            resolved = os.readlink(f"/proc/self/fd/{descriptor}")
        except FileNotFoundError:
            continue
        if resolved == closed_diagnostic_parent:
            raise SystemExit(96)

argv = sys.argv[1:]
record(os.environ["FAKE_ADB_LOG"], argv)
if os.environ.get("FAKE_ADB_BLOCK") == "1":
    time.sleep(10)
state = load_state()
expected_prefix = [
    "android", "adb", "--device", "phone", "--owner",
    os.environ["FAKE_MDEV_OWNER"], "--",
]
if argv[:7] != expected_prefix or len(argv) == 7:
    raise SystemExit(64)
command = argv[7:]
argv = [*argv, " ".join(command)]
chmod_match = os.environ.get("FAKE_ADB_CHMOD_MATCH")
chmod_path = os.environ.get("FAKE_ADB_CHMOD_PATH")
if chmod_match and chmod_path and any(chmod_match in value for value in argv):
    os.chmod(chmod_path, 0o500)
exit_match = os.environ.get("FAKE_ADB_EXIT_MATCH")
if exit_match and any(exit_match in value for value in argv):
    raise SystemExit(int(os.environ.get("FAKE_ADB_EXIT_STATUS", "73")))
if command == ["get-state"]:
    if (
        os.environ.get("FAKE_ADB_DEVICE_LOST") == "1"
        or state.get("device_lost_after_force_stop")
        or state.get("device_lost_after_finalize")
    ):
        raise SystemExit(1)
    if (
        state.get("malformed_after_finalize")
        or os.environ.get("FAKE_ADB_MALFORMED_DEVICE_ENUMERATION") == "1"
    ):
        print("unknown")
        raise SystemExit(0)
    device_state = os.environ.get("FAKE_ADB_DEVICE_ENUMERATION_STATE", "device")
    if device_state != "device":
        print(device_state)
        raise SystemExit(1)
    print("device")
    raise SystemExit(0)
if (
    os.environ.get("FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT") == "1"
    and len(command) > 2
    and command[0] in {"shell", "exec-out"}
    and command[1] == "run-as"
    and "sh" in command[2:]
    and "-c" in command[2:]
):
    raise SystemExit(65)

if len(command) == 2 and command[0] in {"shell", "exec-out"}:
    try:
        decoded = shlex.split(command[1], posix=True)
    except ValueError:
        raise SystemExit(64)
    if decoded[:1] == ["run-as"]:
        command = [command[0], *decoded]
argv = ["-s", state["serial"], *command]
maybe_block(argv)

if argv == ["devices", "-l"]:
    if (
        os.environ.get("FAKE_ADB_MALFORMED_DEVICE_ENUMERATION") == "1"
        or state.get("malformed_after_finalize")
    ):
        print("malformed device enumeration")
        raise SystemExit(0)
    print("List of devices attached")
    if (
        os.environ.get("FAKE_ADB_DEVICE_LOST") != "1"
        and not state.get("device_lost_after_force_stop")
        and not state.get("device_lost_after_finalize")
    ):
        device_state = os.environ.get("FAKE_ADB_DEVICE_ENUMERATION_STATE", "device")
        print(f'{state["serial"]} {device_state} product:phone model:Real device:real transport_id:1')
    if os.environ.get("FAKE_ADB_TWO_DEVICES") == "1":
        print("SECOND_DEVICE device product:phone model:Real device:real transport_id:2")
    raise SystemExit(0)

if len(argv) < 3 or argv[0] != "-s":
    raise SystemExit(2)
serial = argv[1]
command = argv[2:]
if serial != state["serial"]:
    raise SystemExit(3)
if (
    state.get("device_lost_after_force_stop")
    or state.get("device_lost_after_finalize")
    or state.get("route_lost_after_finalize")
    or state.get("malformed_after_finalize")
):
    raise SystemExit(1)

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
if command == ["shell", "echo", "voice-step-reachable"]:
    if os.environ.get("FAKE_ADB_FAIL_REACHABILITY_PROBE") == "1":
        raise SystemExit(1)
    print("voice-step-reachable")
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
if command == ["shell", "cmd", "activity", "get-current-user"]:
    if os.environ.get("FAKE_ADB_MALFORMED_ANDROID_USER") == "1":
        print("UserInfo{0:Owner:13}")
    else:
        print(str(state["android_user_id"]))
    raise SystemExit(0)
if command == [
    "shell", "cmd", "package", "list", "packages", "--user",
    str(state["android_user_id"]), "-U", "--show-stopped", EXPECTED_PACKAGE,
]:
    if os.environ.get("FAKE_ADB_MALFORMED_STOPPED_ROW") == "1":
        print(f"package:{EXPECTED_PACKAGE} uid:{state['package_uid']}")
    else:
        stopped = "true" if state.get("package_stopped") else "false"
        print(f"package:{EXPECTED_PACKAGE} stopped={stopped} uid:{state['package_uid']}")
    raise SystemExit(0)
if command == [
    "shell", "cmd", "package", "list", "packages", "--user",
    str(state["android_user_id"]), "--uid", str(state["package_uid"]),
]:
    print(f"package:{EXPECTED_PACKAGE} uid:{state['package_uid']}")
    if os.environ.get("FAKE_ADB_SHARED_UID") == "1":
        print(f"package:com.example.shared uid:{state['package_uid']}")
    raise SystemExit(0)
if command == ["exec-out", "ps", "-A", "-n", "-o", "UID,PID,PPID,STAT,NAME"]:
    state["process_readbacks"] = state.get("process_readbacks", 0) + 1
    save_state(state)
    malformed = os.environ.get("FAKE_ADB_MALFORMED_QUIESCENCE")
    if state.get("force_stop_observed"):
        malformed = os.environ.get("FAKE_ADB_MALFORMED_QUIESCENCE_AFTER_FORCE_STOP", malformed)
    if malformed == "ps-header":
        print("PID UID NAME")
    else:
        print("UID PID PPID STAT NAME")
        if malformed == "ps-row":
            print(f"{state['package_uid']} not-a-pid 1 S bad")
        elif os.environ.get("FAKE_ADB_PACKAGE_PROCESS") == "1":
            print(f"{state['package_uid']} 222 1 S {EXPECTED_PACKAGE}")
        elif os.environ.get("FAKE_ADB_UNSTABLE_QUIESCENCE") == "1" and state["process_readbacks"] % 2 == 0:
            print(f"{state['package_uid']} 223 1 S {EXPECTED_PACKAGE}:late")
    raise SystemExit(0)
if command == [
    "shell", "cmd", "activity", "get-isolated-pids", str(state["package_uid"]),
]:
    state["isolated_readbacks"] = state.get("isolated_readbacks", 0) + 1
    save_state(state)
    if os.environ.get("FAKE_ADB_MALFORMED_QUIESCENCE") == "isolated":
        print("none")
    elif os.environ.get("FAKE_ADB_ISOLATED_PROCESS") == "1":
        print("[321]")
    else:
        print("[]")
    raise SystemExit(0)
if command == [
    "shell", "cmd", "activity", "force-stop", "--user",
    str(state["android_user_id"]), EXPECTED_PACKAGE,
]:
    if (
        os.environ.get("FAKE_ADB_DEVICE_LOST") == "1"
        or os.environ.get("FAKE_ADB_DEVICE_LOST_ON_FORCE_STOP") == "1"
    ):
        state["device_lost_after_force_stop"] = True
        save_state(state)
        raise SystemExit(1)
    if os.environ.get("FAKE_ADB_FAIL_FORCE_STOP") == "1":
        raise SystemExit(1)
    stopped_false = os.environ.get("FAKE_ADB_FORCE_STOP_STOPPED_FALSE") == "1"
    state["package_stopped"] = not stopped_false
    if not stopped_false:
        state["call_active"] = False
    state["force_stop_observed"] = True
    actor_pid = os.environ.get("FAKE_ADB_ACTOR_PID")
    if actor_pid:
        pid = int(actor_pid)
        os.kill(pid, signal.SIGSTOP)
        if not wait_for_process_state(pid, "T"):
            raise SystemExit(1)
        state["actor_sigstop_observed"] = True
    save_state(state)
    signal_name = os.environ.get("FAKE_ADB_SIGNAL_DURING_FORCE_STOP")
    if signal_name:
        os.kill(os.getppid(), getattr(signal, signal_name))
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
run_as_tail = None
if command[:5] == ["shell", "run-as", EXPECTED_PACKAGE, "--user", str(state["android_user_id"])]:
    run_as_tail = command[5:]
elif command[:3] == ["shell", "run-as", EXPECTED_PACKAGE]:
    run_as_tail = command[3:]
if run_as_tail is not None:
    if os.environ.get("FAKE_ADB_NO_RUN_AS") == "1":
        raise SystemExit(1)
    tail = run_as_tail
    if tail == ["id"]:
        print(f"uid={state['package_uid']}(u0_a123) gid={state['package_uid']}(u0_a123)")
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
        if "voice-step-create-owned-directory" in script:
            remote_dir, ownership = tail[-2:]
            if os.environ.get("FAKE_ADB_PREEXISTING_REMOTE_DIR") == "1" or state.get("remote_directory"):
                raise SystemExit(1)
            try:
                directory = create_real_owned_directory(state, remote_dir, ownership)
            except (FileExistsError, OSError, ValueError):
                raise SystemExit(1)
            if os.environ.get("FAKE_ADB_SUBSTITUTE_RUN_DIRECTORY_BEFORE_CREATE_ROLLBACK") == "1":
                state["moved_remote_directory"] = remote_dir + ".moved"
                cleanup_markers = (
                    "exec 5< .",
                    "exec 4< .",
                    'stat -Lc %d:%i /proc/$$/fd/4',
                    'stat -c %d:%i "$name"',
                    'rmdir -- "$name"',
                    'stat -Lc %h /proc/$$/fd/4',
                )
                state["missing_cleanup_markers"] = [
                    marker for marker in cleanup_markers if marker not in script
                ]
                state["substitute_sentinel"] = (
                    "untouched"
                    if not state["missing_cleanup_markers"] and
                    'rm -rf -- "$directory"' not in script
                    else "deleted"
                )
                moved = directory.with_name(directory.name + ".moved")
                directory.rename(moved)
                directory.mkdir(mode=0o700)
                state["remote_directory"] = remote_dir + ".moved"
                save_state(state)
                raise SystemExit(1)
            save_state(state)
            print("\n".join([
                "created",
                f'parent_identity={state["fixture_parent_identity"]}',
                f'directory_identity={state["fixture_directory_identity"]}',
                f"ownership_nonce={FIXTURE_OWNERSHIP_NONCE}",
            ]))
            raise SystemExit(0)
        if "voice-step-stage-owned-fixture" in script:
            remote_dir, remote_path, ownership = tail[-3:]
            if state.get("remote_directory") != remote_dir or state.get("owner_hash") != ownership:
                raise SystemExit(1)
            hostile_type = os.environ.get("FAKE_ADB_REMOTE_DESTINATION_TYPE")
            if hostile_type:
                state.setdefault("remote_files", {})[remote_path] = {"type": hostile_type}
                save_state(state)
                raise SystemExit(1)
            if remote_path in state.get("remote_files", {}):
                raise SystemExit(1)
            if os.environ.get("FAKE_ADB_SUBSTITUTE_STAGE_BEFORE_STREAM") == "1":
                state.setdefault("remote_files", {})[remote_path] = {"type": "symlink"}
                save_state(state)
                if "voice-step-descriptor-owned-stage" in script:
                    raise SystemExit(1)
            content = sys.stdin.buffer.read()
            host_path = remote_host_path(remote_path)
            try:
                descriptor = os.open(host_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "wb") as handle:
                    handle.write(content)
            except (FileExistsError, OSError, ValueError):
                raise SystemExit(1)
            state.setdefault("remote_files", {})[remote_path] = {
                "type": "regular",
                "size": len(content),
                "hash": "sha256:" + hashlib.sha256(content).hexdigest(),
            }
            save_state(state)
            print(f'{len(content)}\nsha256:{hashlib.sha256(content).hexdigest()}')
            raise SystemExit(0)
        if "voice-step-cleanup-broker" in script:
            expected = [
                "files/voice-real-room/" + "a" * 64,
                state["fixture_parent_identity"],
                state["fixture_directory_identity"],
                state["fixture_ownership_nonce"],
                str(state["package_uid"]),
            ]
            if tail[-5:] != expected:
                state["cleanup_receipt_rejected"] = True
                save_state(state)
                raise SystemExit(1)
            if (
                not state.get("package_stopped")
                or state.get("process_readbacks", 0) < 2
                or state.get("isolated_readbacks", 0) < 2
            ):
                state["cleanup_before_quiescence_rejected"] = True
                save_state(state)
                raise SystemExit(1)
            if os.environ.get("FAKE_ADB_FAIL_CLEANUP_BROKER") == "1":
                raise SystemExit(1)
            directory = remote_host_path(tail[-5])
            actor_entered = REMOTE_APP_DATA_ROOT / ".actor-entered-boundary"
            actor_entered.unlink(missing_ok=True)
            environment = os.environ.copy()
            environment.update({
                "FAKE_BROKER_RMDIR_MODE": "1",
                "FAKE_RMDIR_ACTOR_ENTERED": str(actor_entered),
            })
            if os.environ.get("FAKE_ADB_RETAIN_FIXTURE_DIR") == "1":
                environment["FAKE_RMDIR_FAIL"] = "1"
            completed = subprocess.run(
                ["sh", "-c", script, "sh", *tail[-5:]],
                cwd=REMOTE_APP_DATA_ROOT,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                check=False,
            )
            state["cleanup_broker_exact_shell_executed"] = True
            state["rmdir_boundary_observed"] = Path(
                os.environ["FAKE_RMDIR_BOUNDARY_FILE"]
            ).exists()
            state["actor_entered_cleanup_boundary"] = actor_entered.exists()
            save_state(state)
            if completed.returncode != 0:
                raise SystemExit(1)
            if completed.stdout != "removed" or directory.exists() or directory.is_symlink():
                raise SystemExit(1)
            state["remote_directory"] = None
            state["owner_hash"] = None
            state["remote_files"] = {}
            state["fixtures_removed"] = True
            state["cleanup_broker_completed"] = True
            save_state(state)
            print(completed.stdout, end="")
            raise SystemExit(0)
        if "voice-step-remove-owned-directory" in script:
            remote_dir, ownership = tail[-2:]
            if state.get("remote_directory") != remote_dir or state.get("owner_hash") != ownership:
                raise SystemExit(1)
            if os.environ.get("FAKE_ADB_FAIL_REMOVE") == "1":
                raise SystemExit(1)
            if os.environ.get("FAKE_ADB_RETAIN_FIXTURE_DIR") != "1":
                state["remote_directory"] = None
                state["owner_hash"] = None
                state["remote_files"] = {}
                state["fixtures_removed"] = True
                save_state(state)
                print("removed")
            else:
                print("retained")
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
            nanoseconds = (
                state["metadata_reads"]
                if os.environ.get("FAKE_ADB_SUBSECOND_METADATA_CHANGE") == "1"
                else 0
            )
            print(
                f"regular file|1|1|600|1|12345|{len(content)}|"
                f"2026-08-03 00:00:00.{nanoseconds:09d} +0000|"
                f"2026-08-03 00:00:00.{suffix:09d} +0000"
            )
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
    raw_broadcast_path = os.environ.get("FAKE_ADB_RAW_BROADCAST_FILE")
    raw_broadcast_action = os.environ.get("FAKE_ADB_RAW_BROADCAST_ACTION")
    if raw_broadcast_path and raw_broadcast_action and action.endswith(raw_broadcast_action):
        sys.stdout.buffer.write(Path(raw_broadcast_path).read_bytes())
        raise SystemExit(0)
    if os.environ.get("FAKE_ADB_MALFORMED_BROADCAST") == action:
        print("uncontrolled malformed receiver output")
        raise SystemExit(0)
    if action.endswith(".STATUS"):
        restoring = "--include-stopped-packages" in command
        if restoring:
            expected = [
                "shell", "am", "broadcast", "--user", str(state["android_user_id"]),
                "--include-stopped-packages", "-n", f"{EXPECTED_PACKAGE}/{CONTROL}",
                "-a", f"me.rerere.rikkahub.voiceagent.automation.STATUS",
            ]
            if command != expected:
                raise SystemExit(1)
            if os.environ.get("FAKE_ADB_FAIL_RESTORATION") == "1":
                raise SystemExit(1)
            was_stopped = bool(state.get("package_stopped"))
            actor_pid = os.environ.get("FAKE_ADB_ACTOR_PID")
            if actor_pid:
                os.kill(int(actor_pid), signal.SIGCONT)
                state["actor_sigcont_observed"] = True
            state["package_stopped"] = False
            if was_stopped:
                state["automation_state"] = "idle"
                state["restoration_count"] = state.get("restoration_count", 0) + 1
            save_state(state)
            malformed = os.environ.get("FAKE_ADB_MALFORMED_RESTORATION")
            if malformed == "duplicate":
                complete(0, "status=ok\naction=status\nrun_state=idle\nrun_hash=none\ncomparison_hash=none\nrequested_transport=none\nevent_count=0\nnetwork=none\nvalidated=true")
            data = "\n".join([
                "status=ok",
                "action=status",
                "run_state=finalized" if malformed == "wrong-state" or not was_stopped else "run_state=idle",
                "run_hash=" + (state["run_hash"] if not was_stopped else "none"),
                "comparison_hash=" + (state["comparison_hash"] if not was_stopped else "none"),
                "requested_transport=" + (state["transport"] if not was_stopped else "none"),
                "event_count=" + (str(state.get("event_count", 17)) if not was_stopped else "0"),
                "network=none",
                "validated=true",
            ])
            complete(0, data)
            raise SystemExit(0)
        if os.environ.get("FAKE_ADB_FAIL_STATUS") == "1":
            raise SystemExit(1)
        status_destination = os.environ.get("FAKE_ADB_CREATE_DESTINATION_ON_STATUS")
        if status_destination:
            Path(status_destination).write_text("raced", encoding="utf-8")
        state["status_reads"] = state.get("status_reads", 0) + 1
        status_event_count = state.get("event_count", 17)
        status_network = "wifi"
        if os.environ.get("FAKE_ADB_STATUS_EVENT_COUNT_DRIFT") == "1" and state["status_reads"] > 1:
            status_event_count += 1
        if os.environ.get("FAKE_ADB_STATUS_NETWORK_DRIFT") == "1" and state["status_reads"] > 1:
            status_network = "cellular"
        save_state(state)
        data = "\n".join(
            [
                "status=ok",
                "action=status",
                f'run_state={state["automation_state"]}',
                "run_hash=" + (
                    "invalid" if os.environ.get("FAKE_ADB_STATUS_INVALID_RUN_HASH") == "1"
                    else state["run_hash"] if state["automation_state"] != "idle" else "none"
                ),
                f'comparison_hash={state["comparison_hash"] if state["automation_state"] != "idle" else "none"}',
                f'requested_transport={state["transport"] if state["automation_state"] != "idle" else "none"}',
                f"event_count={status_event_count}",
                f"network={status_network}",
                "validated=" + ("false" if os.environ.get("FAKE_ADB_VALIDATED_FALSE") == "1" else "true"),
            ]
        )
        post_finalize_status = os.environ.get("FAKE_ADB_POST_FINALIZE_STATUS")
        if state.get("run_finalized_recorded") and post_finalize_status:
            if post_finalize_status == "active":
                data = data.replace("run_state=finalized", "run_state=active", 1)
            elif post_finalize_status == "idle":
                data = "\n".join([
                    "status=ok", "action=status", "run_state=idle",
                    "run_hash=none", "comparison_hash=none",
                    "requested_transport=none", f"event_count={status_event_count}",
                    f"network={status_network}", "validated=true",
                ])
            elif post_finalize_status == "wrong-binding":
                data = data.replace("run_state=finalized", "run_state=active", 1)
                data = data.replace(state["run_hash"], hash_value("c"), 1)
            elif post_finalize_status == "malformed":
                data = data.replace("run_state=finalized", "run_state=unknown", 1)
            else:
                raise SystemExit(2)
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
    elif action.endswith(".FINALIZE_BOUND"):
        if os.environ.get("FAKE_ADB_FAIL_FINALIZE") == "1":
            raise SystemExit(1)
        finalize_success_data = os.environ.get("FAKE_ADB_FINALIZE_SUCCESS_DATA")
        if finalize_success_data is not None:
            complete(0, finalize_success_data)
            raise SystemExit(0)
        finalize_nonzero_data = os.environ.get("FAKE_ADB_FINALIZE_NONZERO_DATA")
        if finalize_nonzero_data is not None:
            complete(1, finalize_nonzero_data)
            raise SystemExit(0)
        if (
            values.get("run_hash") != state["run_hash"]
            or values.get("comparison_hash") != state["comparison_hash"]
            or values.get("transport") != state["transport"]
            or state["automation_state"] != "active"
        ):
            complete(1, "status=error\nerror=invalid_state")
            raise SystemExit(0)
        if os.environ.get("FAKE_ADB_REJECT_FINALIZE") == "1":
            complete(1, "status=rejected\nreason=call_not_stopped")
            raise SystemExit(0)
        state["automation_state"] = "finalized"
        state["run_finalized_recorded"] = True
        state["device_lost_after_finalize"] = (
            os.environ.get("FAKE_ADB_DEVICE_LOST_AFTER_FINALIZE") == "1"
        )
        state["route_lost_after_finalize"] = (
            os.environ.get("FAKE_ADB_ROUTE_LOST_AFTER_FINALIZE") == "1"
        )
        state["malformed_after_finalize"] = (
            os.environ.get("FAKE_ADB_MALFORMED_AFTER_FINALIZE") == "1"
        )
        save_state(state)
        data = "status=ok\naction=finalize"
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

if command[:5] == [
    "shell", "am", "start-foreground-service", "--user", str(state["android_user_id"]),
]:
    action = command[command.index("-a") + 1]
    values = extras(command)
    if action.endswith(".END_BOUND") and os.environ.get("FAKE_ADB_FAIL_END") == "1":
        raise SystemExit(1)
    if action.endswith(".START"):
        if os.environ.get("FAKE_ADB_START_DOES_NOT_ACTIVATE") != "1":
            state["call_active"] = True
            state["call_active_recorded"] = True
        if os.environ.get("FAKE_ADB_START_RETAINS_TRACE") != "1":
            state["trace_id"] = "trace-new"
        state["conversation_id"] = values.get("conversationId", state["conversation_id"])
    elif action.endswith(".END_BOUND"):
        matches = (
            values.get("conversationId") == state["conversation_id"]
            and values.get("transport") == state["transport"]
            and values.get("run_hash") == state["run_hash"]
            and values.get("comparison_hash") == state["comparison_hash"]
        )
        if matches and os.environ.get("FAKE_ADB_SERVICE_STAYS_ACTIVE") != "1":
            state["call_active"] = False
            state["call_stopped_recorded"] = True
    elif action.endswith(".END") and os.environ.get("FAKE_ADB_SERVICE_STAYS_ACTIVE") != "1":
        state["call_active"] = False
    save_state(state)
    print("Starting service: controlled")
    if os.environ.get("FAKE_ADB_PRIVATE_NOISE") == "1":
        print("ADB_STDOUT_SECRET")
        print("ADB_STDERR_SECRET", file=sys.stderr)
    raise SystemExit(0)

if command == ["shell", "dumpsys", "activity", "services", EXPECTED_PACKAGE]:
    if state.get("call_active"):
        print(f"ServiceRecord{{controlled {EXPECTED_PACKAGE}/{SERVICE}}}")
    else:
        print("ACTIVITY MANAGER SERVICES (dumpsys activity services)")
    raise SystemExit(0)

exec_out_run_as_tail = None
if command[:5] == ["exec-out", "run-as", EXPECTED_PACKAGE, "--user", str(state["android_user_id"])]:
    exec_out_run_as_tail = command[5:]
elif command[:3] == ["exec-out", "run-as", EXPECTED_PACKAGE]:
    exec_out_run_as_tail = command[3:]
if (
    exec_out_run_as_tail is not None
    and exec_out_run_as_tail[:2] == ["sh", "-c"]
    and "voice-step-capture-bundle" in exec_out_run_as_tail[2]
):
    remote_paths = exec_out_run_as_tail[-3:]
    contents = [artifact_content(remote_path, state) for remote_path in remote_paths]
    if any(content is None for content in contents):
        raise SystemExit(1)
    mutation = os.environ.get("FAKE_ADB_MUTATE_CAPTURE_SOURCE_AFTER_READ")
    replacement = os.environ.get("FAKE_ADB_REPLACE_CAPTURE_SOURCE_AFTER_READ")
    if mutation or replacement:
        source_number = mutation or replacement
        if source_number not in {"1", "2", "3"}:
            raise SystemExit(2)
        state[f"capture_source_{source_number}_raced"] = (
            "mutated" if mutation else "replaced"
        )
        save_state(state)
        raise SystemExit(1)
    preopen_replacement = os.environ.get("FAKE_ADB_PREOPEN_REPLACE_CAPTURE_SOURCE")
    if preopen_replacement:
        if preopen_replacement not in {"1", "2", "3"}:
            raise SystemExit(2)
        for remote_path, content in zip(remote_paths, contents, strict=True):
            host_path = remote_host_path(remote_path)
            host_path.parent.mkdir(parents=True, exist_ok=True)
            host_path.write_bytes(content)
            host_path.chmod(0o600)
        target_remote = remote_paths[int(preopen_replacement) - 1]
        target_host = remote_host_path(target_remote)
        attacker_host = target_host.with_name(target_host.name + ".attacker")
        attacker_host.write_bytes(contents[int(preopen_replacement) - 1])
        attacker_host.chmod(0o600)
        wrapper_directory = REMOTE_APP_DATA_ROOT / ".capture-race-bin"
        wrapper_directory.mkdir(mode=0o700)
        wrapper = wrapper_directory / "stat"
        wrapper.write_text(
            """#!/bin/sh
set -eu
output=$("$REAL_CAPTURE_STAT" "$@")
status=$?
printf '%s\\n' "$output"
[ "$status" -eq 0 ] || exit "$status"
candidate=
for argument do candidate=$argument; done
case "$candidate" in
  "$CAPTURE_SOURCE_ONE"|"$CAPTURE_SOURCE_TWO"|"$CAPTURE_SOURCE_THREE") ;;
  *) exit 0 ;;
esac
grep -Fqx -- "$candidate" "$CAPTURE_RACE_SEEN" 2>/dev/null ||
  printf '%s\\n' "$candidate" >> "$CAPTURE_RACE_SEEN"
if grep -Fqx -- "$CAPTURE_SOURCE_ONE" "$CAPTURE_RACE_SEEN" &&
   grep -Fqx -- "$CAPTURE_SOURCE_TWO" "$CAPTURE_RACE_SEEN" &&
   grep -Fqx -- "$CAPTURE_SOURCE_THREE" "$CAPTURE_RACE_SEEN" &&
   [ ! -e "$CAPTURE_RACE_DONE" ]; then
  : > "$CAPTURE_RACE_DONE"
  mv -- "$CAPTURE_RACE_TARGET" "$CAPTURE_RACE_TARGET.original"
  ln -s -- "$CAPTURE_RACE_ATTACKER" "$CAPTURE_RACE_TARGET"
fi
""",
            encoding="utf-8",
        )
        wrapper.chmod(0o700)
        race_environment = os.environ.copy()
        race_environment.update(
            {
                "PATH": str(wrapper_directory) + os.pathsep + race_environment["PATH"],
                "REAL_CAPTURE_STAT": os.environ["REAL_STAT"],
                "CAPTURE_SOURCE_ONE": remote_paths[0],
                "CAPTURE_SOURCE_TWO": remote_paths[1],
                "CAPTURE_SOURCE_THREE": remote_paths[2],
                "CAPTURE_RACE_SEEN": str(REMOTE_APP_DATA_ROOT / ".capture-race-seen"),
                "CAPTURE_RACE_DONE": str(REMOTE_APP_DATA_ROOT / ".capture-race-done"),
                "CAPTURE_RACE_TARGET": target_remote,
                "CAPTURE_RACE_ATTACKER": str(attacker_host),
            }
        )
        completed = subprocess.run(
            ["sh", "-c", exec_out_run_as_tail[2], "sh", *remote_paths],
            cwd=REMOTE_APP_DATA_ROOT,
            env=race_environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        state[f"capture_source_{preopen_replacement}_raced"] = "preopen-replaced"
        save_state(state)
        if completed.returncode != 0:
            raise SystemExit(1)
        sys.stdout.buffer.write(completed.stdout)
        raise SystemExit(0)
    header = "".join(f"{len(content)}\n" for content in contents).encode()
    sys.stdout.buffer.write(header + b"".join(contents))
    raise SystemExit(0)
if exec_out_run_as_tail is not None and exec_out_run_as_tail[:1] == ["cat"]:
    remote_path = exec_out_run_as_tail[1]
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
    state["artifact_reads"] = state.get("artifact_reads", 0) + 1
    artifact_read = state["artifact_reads"]
    if remote_path.endswith("automation-events.jsonl"):
        state["automation_artifact_reads"] = state.get("automation_artifact_reads", 0) + 1
    save_state(state)
    content = artifact_content(remote_path, state)
    if content is None:
        raise SystemExit(1)
    destination = os.environ.get("FAKE_ADB_CREATE_CAPTURE_DESTINATION")
    create_on_read = int(os.environ.get("FAKE_ADB_CREATE_CAPTURE_ON_READ", "0"))
    if destination and artifact_read == create_on_read:
        Path(destination).write_text("raced", encoding="utf-8")
    signal_on_read = int(os.environ.get("FAKE_ADB_SIGNAL_ON_ARTIFACT_READ", "0"))
    if signal_on_read and artifact_read == signal_on_read:
        signal_name = os.environ.get("FAKE_ADB_ARTIFACT_SIGNAL", "TERM")
        os.kill(os.getppid(), getattr(signal, "SIG" + signal_name))
        raise SystemExit(143)
    if os.environ.get("FAKE_ADB_ARTIFACT_CHANGES") == "1":
        if artifact_read % 2 == 0:
            content += b'{"changed":true}\n'
    sys.stdout.buffer.write(content)
    raise SystemExit(0)

raise SystemExit(9)
PY
chmod 700 "$BIN_DIR/mdev"

cat > "$BIN_DIR/adb" <<'PY'
#!/usr/bin/env python3
import os
import sys

with open(os.environ["FAKE_ADB_LOG"], "ab") as handle:
    handle.write(b"RAW_ADB\0")
    for value in sys.argv[1:]:
        handle.write(value.encode() + b"\0")
    handle.write(b"\0")
raise SystemExit(97)
PY
chmod 700 "$BIN_DIR/adb"

export PATH="$BIN_DIR:$PATH"
export FAKE_ADB_LOG="$ADB_LOG"
export FAKE_TIMEOUT_LOG="$TIMEOUT_LOG"
export FAKE_LN_LOG="$LN_LOG"
export FAKE_ADB_STATE="$FAKE_STATE"
export REAL_TIMEOUT REAL_LN REAL_RMDIR REAL_RM REAL_STAT
export FAKE_MDEV_OWNER="$MDEV_OWNER"
export FAKE_REMOTE_APP_DATA_ROOT="$REMOTE_APP_DATA_ROOT"
export FAKE_RMDIR_BOUNDARY_FILE="$RMDIR_BOUNDARY_FILE"
export VOICE_STEP_ADB_TIMEOUT_SECONDS=10
export VOICE_STEP_WAIT_TIMEOUT_SECONDS=2
export VOICE_STEP_MAX_WAIT_ATTEMPTS=2
export VOICE_STEP_POLL_SECONDS=0

reset_fake() {
  : > "$ADB_LOG"
  : > "$TIMEOUT_LOG"
  : > "$LN_LOG"
  rm -rf -- "$REMOTE_APP_DATA_ROOT"
  mkdir -p "$REMOTE_APP_DATA_ROOT/files/voice-real-room"
  chmod 700 "$REMOTE_APP_DATA_ROOT" "$REMOTE_APP_DATA_ROOT/files" \
    "$REMOTE_APP_DATA_ROOT/files/voice-real-room"
  rm -f -- "$RMDIR_BOUNDARY_FILE"
  python3 - "$FAKE_STATE" "$CURRENT_UID" <<'PY'
import json
import sys

payload = {
    "serial": "DEVICE_SECRET_123",
    "android_user_id": 0,
    "package_uid": int(sys.argv[2]),
    "package_stopped": False,
    "conversation_id": "CONVERSATION_SECRET_123",
    "automation_state": "idle",
    "run_hash": "sha256:" + "a" * 64,
    "comparison_hash": "sha256:" + "b" * 64,
    "transport": "livekit_experimental",
    "fixture_token": "fixture-1",
    "trace_id": "trace-old",
    "call_active": False,
    "call_active_recorded": False,
    "call_stopped_recorded": False,
    "run_finalized_recorded": False,
    "event_count": 17,
    "remote_directory": None,
    "owner_hash": None,
    "fixture_parent_identity": "",
    "fixture_directory_identity": "",
    "fixture_ownership_nonce": "0123456789abcdef0123456789abcdef",
    "remote_files": {},
}
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump(payload, handle, separators=(",", ":"))
PY
  chmod 600 "$FAKE_STATE"
  unset FAKE_ADB_TWO_DEVICES FAKE_ADB_EMULATOR FAKE_ADB_NO_RUN_AS FAKE_TIMEOUT_EXIT
  unset FAKE_TIMEOUT_EXIT_MATCH FAKE_ADB_MALFORMED_ANDROID_USER
  unset FAKE_ADB_MALFORMED_STOPPED_ROW FAKE_ADB_SHARED_UID
  unset FAKE_ADB_MALFORMED_QUIESCENCE FAKE_ADB_PACKAGE_PROCESS
  unset FAKE_ADB_MALFORMED_QUIESCENCE_AFTER_FORCE_STOP
  unset FAKE_ADB_ISOLATED_PROCESS FAKE_ADB_UNSTABLE_QUIESCENCE
  unset FAKE_ADB_FAIL_FORCE_STOP FAKE_ADB_FAIL_CLEANUP_BROKER
  unset FAKE_ADB_MALFORMED_BROADCAST FAKE_ADB_SIGNAL_ON_TRACE
  unset FAKE_ADB_CREATE_DESTINATION_ON_TRACE FAKE_ADB_STAGE_REJECT
  unset FAKE_ADB_TRIGGER_REJECT FAKE_ADB_SERVICE_STAYS_ACTIVE
  unset FAKE_ADB_ARTIFACT_CHANGES FAKE_ADB_MISSING_ARTIFACT FAKE_ADB_BAD_SANITIZED
  unset FAKE_ADB_CHECKPOINT_FAILURE
  unset FAKE_ADB_EMPTY_ARTIFACT FAKE_ADB_INCOMPLETE_ARTIFACT
  unset FAKE_ADB_CREATE_CAPTURE_DESTINATION FAKE_ADB_CREATE_CAPTURE_ON_READ
  unset FAKE_ADB_SIGNAL_ON_ARTIFACT_READ FAKE_ADB_ARTIFACT_SIGNAL
  unset FAKE_ADB_FAIL_END FAKE_ADB_FAIL_REMOVE
  unset FAKE_ADB_FAIL_STATUS FAKE_ADB_AMBIGUOUS_SERVICE
  unset FAKE_ADB_CREATE_DESTINATION_ON_STATUS
  unset FAKE_ADB_BLOCK FAKE_TIMEOUT_ENFORCE FAKE_LN_RACE_DESTINATION
  unset FAKE_LN_SIGNAL_DESTINATION FAKE_LN_SIGNAL_TIMING
  unset FAKE_ADB_PREEXISTING_REMOTE_DIR FAKE_ADB_REMOTE_DESTINATION_TYPE
  unset FAKE_ADB_VALIDATED_FALSE FAKE_ADB_SUBSECOND_METADATA_CHANGE
  unset FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT
  unset FAKE_ADB_PRIVATE_NOISE FAKE_ADB_DEVICE_LOST FAKE_ADB_RETAIN_FIXTURE_DIR
  unset FAKE_ADB_DEVICE_ENUMERATION_STATE FAKE_ADB_SIGNAL_DURING_FORCE_STOP
  unset FAKE_ADB_STATUS_EVENT_COUNT_DRIFT FAKE_ADB_STATUS_NETWORK_DRIFT
  unset FAKE_ADB_SUBSTITUTE_STAGE_BEFORE_STREAM
  unset FAKE_ADB_STATUS_INVALID_RUN_HASH
  unset FAKE_ADB_SUBSTITUTE_RUN_DIRECTORY_BEFORE_CREATE_ROLLBACK
  unset FAKE_ADB_BLOCK_MATCH FAKE_ADB_BLOCK_READY FAKE_ADB_BLOCK_RELEASE
  unset FAKE_ADB_DURABLE_STOP_VISIBLE_AFTER FAKE_ADB_MALFORMED_DURABLE_ENDING
  unset FAKE_ADB_POST_CLEANUP_ARTIFACT_CHANGE
  unset FAKE_ADB_FAIL_RESTORATION FAKE_ADB_MALFORMED_RESTORATION
  unset FAKE_ADB_BROADCAST_TRAILING_JUNK
  unset FAKE_ADB_ACTOR_PID FAKE_ADB_ACTOR_TRIGGER FAKE_ADB_ACTOR_RESULT
  unset FAKE_RMDIR_ACTOR_ENTERED FAKE_RMDIR_FAIL
  unset FAKE_ADB_FORCE_STOP_EXECUTE_THEN_TIMEOUT FAKE_ADB_FORCE_STOP_STOPPED_FALSE
  unset FAKE_ADB_FAIL_REACHABILITY_PROBE FAKE_ADB_MALFORMED_DEVICE_ENUMERATION
  unset FAKE_TIMEOUT_EXECUTE_THEN_TIMEOUT_MATCH
  unset FAKE_ADB_CAPTURE_CORRUPTION FAKE_ADB_MUTATE_CAPTURE_SOURCE_AFTER_READ
  unset FAKE_ADB_REPLACE_CAPTURE_SOURCE_AFTER_READ FAKE_ADB_CALL_STOP_FAILED
  unset FAKE_ADB_REJECT_FINALIZE FAKE_ADB_FAIL_FINALIZE
  unset FAKE_ADB_FINALIZE_NONZERO_DATA FAKE_ADB_FINALIZE_SUCCESS_DATA
  unset FAKE_ADB_RAW_BROADCAST_FILE FAKE_ADB_RAW_BROADCAST_ACTION
  unset FAKE_ADB_POST_FINALIZE_STATUS
  unset FAKE_ADB_DEVICE_LOST_ON_FORCE_STOP FAKE_ADB_CAPTURE_CRLF
  unset FAKE_ADB_DEVICE_LOST_AFTER_FINALIZE FAKE_ADB_ROUTE_LOST_AFTER_FINALIZE
  unset FAKE_ADB_MALFORMED_AFTER_FINALIZE
  unset FAKE_ADB_PREOPEN_REPLACE_CAPTURE_SOURCE
  unset FAKE_ADB_EXIT_MATCH FAKE_ADB_EXIT_STATUS
  unset FAKE_ADB_START_DOES_NOT_ACTIVATE FAKE_ADB_START_RETAINS_TRACE
  unset FAKE_ADB_CALL_ACTIVE_VISIBLE_AFTER
  unset FAKE_ADB_CHMOD_MATCH FAKE_ADB_CHMOD_PATH
  unset FAKE_ASSERT_CLOSED_DIAGNOSTIC_PARENT FAKE_RM_SIGNAL_MATCH
  unset VOICE_STEP_DIAGNOSTIC_TMPFILE_MARKER VOICE_STEP_DIAGNOSTIC_LINK_MARKER
  unset VOICE_STEP_DIAGNOSTIC_UNLINK_MARKER VOICE_STEP_DIAGNOSTIC_DESTINATION
  unset VOICE_STEP_DIAGNOSTIC_PARENT VOICE_STEP_DIAGNOSTIC_PINNED_PARENT
  unset VOICE_STEP_DIAGNOSTIC_REPLACEMENT_PARENT VOICE_STEP_DIAGNOSTIC_PARENT_MARKER
  unset VOICE_STEP_TEST_STATE_PARENT VOICE_STEP_TEST_DIAGNOSTIC_PARENT
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

make_diagnostic_tmpfile_failure_site() {
  local site="$1" marker="$2"
  mkdir "$site"
  chmod 700 "$site"
  cat > "$site/sitecustomize.py" <<'PY'
import os

marker = os.environ["VOICE_STEP_DIAGNOSTIC_TMPFILE_MARKER"]
real_open = os.open


def controlled_open(path, flags, *args, **kwargs):
    if flags & os.O_TMPFILE:
        with open(marker, "w", encoding="ascii") as handle:
            handle.write("tmpfile-attempted\n")
        raise OSError
    return real_open(path, flags, *args, **kwargs)


os.open = controlled_open
PY
}

make_diagnostic_link_race_site() {
  local site="$1" marker="$2" unlink_marker="$3"
  mkdir "$site"
  chmod 700 "$site"
  cat > "$site/sitecustomize.py" <<'PY'
import os

marker = os.environ["VOICE_STEP_DIAGNOSTIC_LINK_MARKER"]
unlink_marker = os.environ["VOICE_STEP_DIAGNOSTIC_UNLINK_MARKER"]
real_link = os.link
real_open = os.open
real_unlink = os.unlink


def controlled_link(source, target, *args, **kwargs):
    if os.fsdecode(source).startswith("/proc/self/fd/"):
        descriptor = real_open(
            target,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC,
            0o600,
            dir_fd=kwargs["dst_dir_fd"],
        )
        os.write(descriptor, b"raced")
        os.close(descriptor)
        with open(marker, "w", encoding="ascii") as handle:
            handle.write("link-raced\n")
    return real_link(source, target, *args, **kwargs)


def controlled_unlink(path, *args, **kwargs):
    destination = os.environ["VOICE_STEP_DIAGNOSTIC_DESTINATION"]
    if os.fsdecode(path) in {destination, os.path.basename(destination)}:
        with open(unlink_marker, "w", encoding="ascii") as handle:
            handle.write("unlink-attempted\n")
    return real_unlink(path, *args, **kwargs)


os.link = controlled_link
os.unlink = controlled_unlink
PY
}

make_diagnostic_parent_race_site() {
  local site="$1"
  mkdir "$site"
  chmod 700 "$site"
  cat > "$site/sitecustomize.py" <<'PY'
import os

parent = os.environ["VOICE_STEP_DIAGNOSTIC_PARENT"]
pinned_parent = os.environ["VOICE_STEP_DIAGNOSTIC_PINNED_PARENT"]
replacement_parent = os.environ["VOICE_STEP_DIAGNOSTIC_REPLACEMENT_PARENT"]
marker = os.environ["VOICE_STEP_DIAGNOSTIC_PARENT_MARKER"]
real_open = os.open
replaced = False


def controlled_open(path, flags, *args, **kwargs):
    global replaced
    if (
        not replaced
        and os.fsdecode(path) == parent
        and flags & os.O_DIRECTORY
    ):
        replaced = True
        os.rename(parent, pinned_parent)
        os.symlink(replacement_parent, parent, target_is_directory=True)
        with open(marker, "w", encoding="ascii") as handle:
            handle.write("parent-replaced\n")
    return real_open(path, flags, *args, **kwargs)


os.open = controlled_open
PY
}

make_destination_parent_identity_alias_site() {
  local site="$1"
  mkdir "$site"
  chmod 700 "$site"
  cat > "$site/sitecustomize.py" <<'PY'
import os

state_parent = os.environ["VOICE_STEP_TEST_STATE_PARENT"]
diagnostic_parent = os.environ["VOICE_STEP_TEST_DIAGNOSTIC_PARENT"]
real_lstat = os.lstat


def aliased_lstat(path, *args, **kwargs):
    if os.fsdecode(path) == state_parent:
        return real_lstat(diagnostic_parent)
    return real_lstat(path, *args, **kwargs)


os.lstat = aliased_lstat
PY
}

write_raw_broadcast_fixture() {
  local destination="$1"
  local fixture_kind="$2"
  python3 - "$destination" "$fixture_kind" <<'PY'
import sys
from pathlib import Path

destination = Path(sys.argv[1])
fixture_kind = sys.argv[2]
run_hash = b"sha256:" + b"a" * 64
comparison_hash = b"sha256:" + b"b" * 64
status_data = b"\n".join([
    b"status=ok",
    b"action=status",
    b"run_state=finalized",
    b"run_hash=" + run_hash,
    b"comparison_hash=" + comparison_hash,
    b"requested_transport=livekit_experimental",
    b"event_count=17",
    b"network=wifi",
    b"validated=true",
])
records = {
    "canonical-status": b'Broadcast completed: result=0, data="' + status_data + b'"\n',
    "nul-success": b'Broadcast completed: result=0, data="status=ok\naction=final\0ize"\n',
    "nul-rejection": b'Broadcast completed: result=1, data="status=rejected\nreason=call_not_\0stopped"\n',
    "nul-error": b'Broadcast completed: result=1, data="status=error\nerror=runtime_\0failure"\n',
    "nul-status": b'Broadcast completed: result=0, data="' + status_data.replace(
        b"run_state=finalized", b"run_state=final\0ized", 1
    ) + b'"\n',
    "trailing-lf-success": b'Broadcast completed: result=0, data="status=ok\naction=finalize\n"\n',
    "cr-success": b'Broadcast completed: result=0, data="status=ok\naction=finalize\r"\n',
}
destination.write_bytes(records[fixture_kind])
PY
  chmod 600 -- "$destination"
}

activate_fake_run() {
  python3 - "$FAKE_STATE" "$REMOTE_APP_DATA_ROOT" "$MDEV_OWNER" <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    state = json.load(handle)
state["automation_state"] = "active"
state["call_active"] = True
state["call_active_recorded"] = True
state["call_stopped_recorded"] = False
state["run_finalized_recorded"] = False
state["package_stopped"] = False
state["trace_id"] = "trace-new"
state["remote_directory"] = "files/voice-real-room/" + "a" * 64
state["owner_hash"] = "sha256:" + hashlib.sha256(
    "\0".join([
        "sha256:" + hashlib.sha256(sys.argv[3].encode()).hexdigest(),
        "me.rerere.rikkahub.debug",
        state["conversation_id"],
        state["run_hash"],
        state["comparison_hash"],
    ]).encode()
).hexdigest()
directory = Path(sys.argv[2]) / state["remote_directory"]
directory.mkdir(mode=0o700)
marker = directory / ".voice-step-owner"
marker.write_text(
    state["owner_hash"] + "\n" + state["fixture_ownership_nonce"] + "\n",
    encoding="utf-8",
)
marker.chmod(0o600)
fixture = directory / "request-fixture.pcm"
fixture.write_bytes(b"fixture")
fixture.chmod(0o600)
def identity(candidate):
    metadata = candidate.stat()
    return ":".join([
        str(metadata.st_dev), str(metadata.st_ino), format(metadata.st_mode, "o"),
        str(metadata.st_uid), str(metadata.st_gid),
    ])
state["fixture_parent_identity"] = identity(directory.parent)
state["fixture_directory_identity"] = identity(directory)
temporary = path + ".active"
with open(temporary, "w", encoding="utf-8") as handle:
    json.dump(state, handle, separators=(",", ":"))
os.replace(temporary, path)
PY
}

record_fake_call_stop() {
  python3 - "$FAKE_STATE" <<'PY'
import json
import os
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    state = json.load(handle)
state["call_stopped_recorded"] = True
temporary = path + ".failed-stop"
with open(temporary, "w", encoding="utf-8") as handle:
    json.dump(state, handle, separators=(",", ":"))
os.replace(temporary, path)
PY
}

finalize_fake_run() {
  local call_active="${1:-false}"
  python3 - "$FAKE_STATE" "$call_active" "$REMOTE_APP_DATA_ROOT" <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    state = json.load(handle)
state["automation_state"] = "finalized"
state["call_active"] = sys.argv[2] == "true"
state["call_active_recorded"] = True
state["call_stopped_recorded"] = True
state["run_finalized_recorded"] = True
state["package_stopped"] = False
state["trace_id"] = "trace-new"
state["remote_directory"] = "files/voice-real-room/" + "a" * 64
state["owner_hash"] = "sha256:" + hashlib.sha256(
    "\0".join([
        state["serial"],
        "me.rerere.rikkahub.debug",
        state["conversation_id"],
        state["run_hash"],
        state["comparison_hash"],
    ]).encode()
).hexdigest()
directory = Path(sys.argv[3]) / state["remote_directory"]
directory.mkdir(mode=0o700)
marker = directory / ".voice-step-owner"
marker.write_text(
    state["owner_hash"] + "\n" + state["fixture_ownership_nonce"] + "\n",
    encoding="utf-8",
)
marker.chmod(0o600)
fixture = directory / "request-fixture.pcm"
fixture.write_bytes(b"fixture")
fixture.chmod(0o600)
def identity(candidate):
    metadata = candidate.stat()
    return ":".join([
        str(metadata.st_dev), str(metadata.st_ino), format(metadata.st_mode, "o"),
        str(metadata.st_uid), str(metadata.st_gid),
    ])
state["fixture_parent_identity"] = identity(directory.parent)
state["fixture_directory_identity"] = identity(directory)
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
  python3 - "$destination" "$package" "$FAKE_STATE" "$MDEV_OWNER" <<'PY'
import hashlib
import json
import os
import sys

with open(sys.argv[3], encoding="utf-8") as handle:
    fake = json.load(handle)
uid = fake["package_uid"]
gid = os.getgid()
payload = {
    "schemaVersion": 3,
    "mdevOwnerHash": "sha256:" + hashlib.sha256(sys.argv[4].encode()).hexdigest(),
    "package": sys.argv[2],
    "androidUserId": fake["android_user_id"],
    "packageUid": uid,
    "conversationId": "CONVERSATION_SECRET_123",
    "runHash": "sha256:" + "a" * 64,
    "comparisonHash": "sha256:" + "b" * 64,
    "fixtureToken": "fixture-1",
    "fixtureParentIdentity": fake["fixture_parent_identity"] or f"1:1:40700:{uid}:{gid}",
    "fixtureDirectoryIdentity": fake["fixture_directory_identity"] or f"1:2:40700:{uid}:{gid}",
    "fixtureOwnershipNonce": "0123456789abcdef0123456789abcdef",
    "traceId": "trace-new",
    "transport": "livekit_experimental",
}
descriptor = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, separators=(",", ":"))
    handle.write("\n")
PY
}

write_finalization() {
  local destination="$1"
  local outcome="${2:-complete}"
  local reason="${3:-complete}"
  local call_stopped="${4:-true}"
  local automation_finalized="${5:-true}"
  local forced_fallback_used="${6:-false}"
  python3 - "$destination" "$outcome" "$reason" "$call_stopped" \
    "$automation_finalized" "$forced_fallback_used" <<'PY'
import json
import os
import sys

path, outcome, reason, call_stopped, automation_finalized, forced_fallback = sys.argv[1:]
payload = {
    "schemaVersion": 1,
    "outcome": outcome,
    "reason": reason,
    "callStopped": call_stopped == "true",
    "automationFinalized": automation_finalized == "true",
    "forcedFallbackUsed": forced_fallback == "true",
}
descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, sort_keys=True, separators=(",", ":"))
PY
}

assert_finalization_record() {
  local path="$1"
  local outcome="$2"
  local reason="$3"
  local call_stopped="$4"
  local automation_finalized="$5"
  local forced_fallback_used="$6"
  if ! python3 - "$path" "$outcome" "$reason" "$call_stopped" \
    "$automation_finalized" "$forced_fallback_used" <<'PY'
import json
import os
import stat
import sys

path, outcome, reason, call_stopped, automation_finalized, forced_fallback = sys.argv[1:]
expected = {
    "schemaVersion": 1,
    "outcome": outcome,
    "reason": reason,
    "callStopped": call_stopped == "true",
    "automationFinalized": automation_finalized == "true",
    "forcedFallbackUsed": forced_fallback == "true",
}
metadata = os.lstat(path)
assert stat.S_ISREG(metadata.st_mode) and not stat.S_ISLNK(metadata.st_mode)
assert stat.S_IMODE(metadata.st_mode) == 0o600 and metadata.st_nlink == 1
actual = open(path, "rb").read()
canonical = json.dumps(
    expected, sort_keys=True, separators=(",", ":"), ensure_ascii=False
).encode()
assert actual == canonical
PY
  then
    fail "finalization-record test: exact canonical record mismatch"
  fi
}

run_helper() {
  local argument
  local has_owner=0
  local -a invocation=("$@")
  LAST_OPERATION="${1:-unknown}"
  for argument in "$@"; do
    [[ "$argument" == --mdev-owner ]] && has_owner=1
  done
  if [[ "${RUN_HELPER_SKIP_OWNER:-0}" != 1 && "$has_owner" -eq 0 && "${1:-}" =~ ^(preflight|start|inject|interrupt|status|finalize|capture|end)$ ]]; then
    invocation=("$1" --mdev-owner "$MDEV_OWNER" "${@:2}")
  fi
  LAST_PRIVATE_PATHS=("$TMP_DIR" "$FAKE_STATE" "$STDOUT_FILE" "$STDERR_FILE" "$HELPER_TEMP_ROOT")
  for argument in "${invocation[@]}"; do
    [[ "$argument" == /* ]] && LAST_PRIVATE_PATHS+=("$argument")
  done
  : > "$STDOUT_FILE"
  : > "$STDERR_FILE"
  set +e
  TMPDIR="$HELPER_TEMP_ROOT" "$HELPER" "${invocation[@]}" >"$STDOUT_FILE" 2>"$STDERR_FILE"
  RUN_STATUS=$?
  set -e
}

assert_private_output_absent() {
  local combined
  combined="$(<"$STDOUT_FILE")$(<"$STDERR_FILE")"
  local marker
  for marker in \
    DEVICE_SECRET_123 OWNER_SECRET_123 OTHER_OWNER_SECRET_456 \
    CONVERSATION_SECRET_123 PRIVATE_TRACE RAW_SESSION_SECRET \
    fixture-1 trace-new trace-old \
    fixture-secret PROMPT_SECRET TRANSCRIPT_SECRET ANSWER_SECRET \
    ADB_STDOUT_SECRET ADB_STDERR_SECRET; do
    [[ "$combined" != *"$marker"* ]] || fail "private-output test: helper disclosed a private value"
  done
  local private_path
  for private_path in "$@" "${LAST_PRIVATE_PATHS[@]}"; do
    [[ -z "$private_path" || "$combined" != *"$private_path"* ]] ||
      fail "private-output test: helper disclosed a private path"
  done
}

assert_diagnostic_record() {
  local path="$1" stage="$2" category="$3" child="$4" cleanup="$5"
  python3 - "$path" "$stage" "$category" "$child" "$cleanup" <<'PY'
import os, stat, sys
path, stage, category, child, cleanup = sys.argv[1:]
raw = open(path, "rb").read()
assert raw == (
    "version=1\noperation=start\n"
    f"stage={stage}\noutcome=failure\nerror_category={category}\n"
    f"child_exit_status={child}\ncleanup={cleanup}\n"
).encode()
metadata = os.lstat(path)
assert stat.S_ISREG(metadata.st_mode)
assert stat.S_IMODE(metadata.st_mode) == 0o600
assert metadata.st_nlink == 1
for secret in (b"DEVICE_SECRET_123", b"OWNER_SECRET_123", b"CONVERSATION_SECRET_123",
               b"fixture-1", b"trace-new", b"sha256:", b"ADB_STDOUT_SECRET",
               b"ADB_STDERR_SECRET"):
    assert secret not in raw
PY
}

assert_stage_transition_signal_boundary() {
  local managed_status="$TMP_DIR/stage-transition-managed-status"
  local observed="$TMP_DIR/stage-transition-observed"
  printf '1' > "$managed_status"
  (
    source "$DIAGNOSTICS"
    DIAGNOSTIC_STAGE='fixture-arm'
    DIAGNOSTIC_CHILD_EXIT_STATUS='1'
    DIAGNOSTIC_MANAGED_STATUS_FILE="$managed_status"
    trap 'printf "%s:%s\n" "$DIAGNOSTIC_STAGE" "$(<"$DIAGNOSTIC_MANAGED_STATUS_FILE")" > "$observed"; exit 0' TERM
    stage_transition_debug_signal() {
      if [[ "$BASH_COMMAND" == ': > "$DIAGNOSTIC_MANAGED_STATUS_FILE"' ]]; then
        trap - DEBUG
        kill -TERM "$BASHPID"
      fi
    }
    set -T
    trap stage_transition_debug_signal DEBUG
    diagnostic_set_stage trace-read
    exit 1
  ) || fail "tracing-stage-transition-signal test: boundary injection failed"
  [[ "$(<"$observed")" == fixture-arm:1 ]] ||
    fail "tracing-stage-transition-signal test: stale status attached to a new stage"
}

assert_traced_start_failure() {
  local fixture="$1" state="$2" diagnostic="$3" stage="$4" category="$5" child="$6" cleanup="$7"
  shift 7
  local run_hash="sha256:$(printf 'a%.0s' {1..64})"
  local -a extra=()
  while (( $# > 0 )); do
    case "$1" in
      --run-hash)
        (( $# >= 2 )) || fail "tracing-failure test: missing run-hash value"
        run_hash="$2"
        shift 2
        ;;
      *)
        extra+=("$1")
        shift
        ;;
    esac
  done
  rm -f -- "$state" "$diagnostic"
  run_helper start --state "$state" --diagnostic-record "$diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$run_hash" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture" \
    "${extra[@]}"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "tracing-failure test: $stage failure succeeded"
  if [[ -e "$state" ]]; then
    [[ "$(<"$state")" == raced ]] ||
      fail "tracing-failure test: $stage published state"
  fi
  assert_diagnostic_record "$diagnostic" "$stage" "$category" "$child" "$cleanup" ||
    fail "tracing-failure test: $stage record contract mismatch"
  [[ "$(tail -n 1 "$STDERR_FILE")" == "voice-step.diagnostic=stage:$stage,category:$category" ]] ||
    fail "tracing-failure test: $stage diagnostic summary mismatch"
  assert_private_output_absent
}

assert_exact_output() {
  local expected="$1"
  if [[ "$RUN_STATUS" -ne 0 ]]; then
    if [[ "$(<"$STDERR_FILE")" =~ ^voice-step.error=[A-Za-z[:space:]-]+$ ]]; then
      fail "success-output test: $LAST_OPERATION failed after $TEST_COUNT assertions ($(tr '\n' ' ' < "$STDERR_FILE"))"
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

assert_exact_checkpoint_failure() {
  [[ "$RUN_STATUS" -ne 0 ]] || fail "checkpoint failure test: command succeeded"
  [[ ! -s "$STDOUT_FILE" ]] || fail "checkpoint failure test: command wrote stdout"
  [[ "$(<"$STDERR_FILE")" == 'voice-step.error=checkpoint evidence not proven' ]] ||
    fail "checkpoint failure test: diagnostic was not exactly one sanitized line"
  [[ "$(wc -l < "$STDERR_FILE")" == 1 ]] ||
    fail "checkpoint failure test: diagnostic was emitted more than once"
  assert_private_output_absent
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
  [[ "$(<"$STDERR_FILE")" == *"$expected"* ]] ||
    fail "rejection test: expected '$expected', got '$(<"$STDERR_FILE")'"
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

exact_argument_count() {
  local needle="$1"
  python3 - "$ADB_LOG" "$needle" <<'PY'
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
needle = sys.argv[2].encode()
print(sum(needle in command for command in commands))
PY
}

exact_command_count() {
  python3 - "$ADB_LOG" "$@" <<'PY'
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
prefix = [b"android", b"adb", b"--device", b"phone", b"--owner", b"OWNER_SECRET_123", b"--"]
commands = [
    [b"-s", b"DEVICE_SECRET_123", *command[len(prefix):]]
    if command[:len(prefix)] == prefix else command
    for command in commands
]
expected = [value.encode() for value in sys.argv[2:]]
print(sum(command == expected for command in commands))
PY
}

assert_bound_action_extras() {
  local action="$1"
  python3 - "$ADB_LOG" "$action" <<'PY' || fail "bound-action test: action or exact binding extras mismatch"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
action = sys.argv[2].encode()
matches = [command for command in commands if action in command]
assert len(matches) == 1
command = matches[0]
expected = {
    b"conversationId": b"CONVERSATION_SECRET_123",
    b"transport": b"livekit_experimental",
    b"run_hash": b"sha256:" + b"a" * 64,
    b"comparison_hash": b"sha256:" + b"b" * 64,
}

for key, value in expected.items():
    index = command.index(key)
    assert command[index - 1] == b"--es"
    assert command[index + 1] == value
PY
}

assert_service_action_pinned() {
  local action="$1"
  local expected_user="${2:-0}"
  python3 - "$ADB_LOG" "$action" "$expected_user" <<'PY' || fail "pinned-user test: bound service action was not pinned"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
action = sys.argv[2].encode()
expected_user = sys.argv[3].encode()
matches = [command for command in commands if action in command]
assert len(matches) == 1
shell = matches[0].index(b"shell")
assert matches[0][shell:shell + 5] == [b"shell", b"am", b"start-foreground-service", b"--user", expected_user]
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

wait_for_path() {
  local path="$1"
  local attempt
  for attempt in {1..300}; do
    [[ -e "$path" ]] && return 0
    sleep 0.01
  done
  return 1
}

assert_no_adb_mutations() {
  python3 - "$ADB_LOG" <<'PY' || fail "validation-order test: ADB mutation occurred after failed validation"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
mutation_tokens = {
    b"start-foreground-service",
    b"force-stop",
    b"mkdir",
    b"rm",
    b"voice-step-create-owned-directory",
    b"voice-step-stage-owned-fixture",
    b"voice-step-remove-owned-directory",
    b"voice-step-cleanup-broker",
}
mutation_actions = {
    b"me.rerere.rikkahub.voiceagent.automation.PREPARE",
    b"me.rerere.rikkahub.voiceagent.automation.MARK",
    b"me.rerere.rikkahub.voiceagent.automation.FINALIZE",
    b"me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND",
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

run_managed_owner_contract_tests() {
  local fixture="$TMP_DIR/owner-contract.pcm"
  local state="$TMP_DIR/owner-contract-state.json"
  local finalization="$TMP_DIR/owner-contract-finalization.json"
  local hash_a="sha256:$(printf 'a%.0s' {1..64})"
  local hash_b="sha256:$(printf 'b%.0s' {1..64})"
  local -a invocation=()
  local operation

  reset_fake
  make_fixture "$fixture"
  activate_fake_run
  write_valid_state "$state"
  write_finalization "$finalization"

  for operation in preflight start inject interrupt status finalize capture end; do
    case "$operation" in
      preflight) invocation=(preflight --package me.rerere.rikkahub.debug) ;;
      start) invocation=(start --state "$TMP_DIR/owner-missing-start.json" --package me.rerere.rikkahub.debug --conversation-id conversation-owner --run-hash "$hash_a" --comparison-hash "$hash_b" --fixture "$fixture") ;;
      inject) invocation=(inject --state "$state" --fixture "$fixture" --role request) ;;
      interrupt) invocation=(interrupt --state "$state" --fixture "$fixture") ;;
      status) invocation=(status --state "$state" --expect single_result_announced) ;;
      finalize) invocation=(finalize --state "$state" --finalization-output "$TMP_DIR/owner-missing-finalization.json") ;;
      capture) invocation=(capture --state "$state" --finalization "$finalization" --automation-output "$TMP_DIR/owner-missing-automation.jsonl" --private-voice-output "$TMP_DIR/owner-missing-private.ndjson" --sanitized-voice-output "$TMP_DIR/owner-missing-sanitized.ndjson") ;;
      end) invocation=(end --state "$state" --finalization "$finalization" --cleanup-output "$TMP_DIR/owner-missing-cleanup.json") ;;
    esac
    : >"$MDEV_LOG"
    RUN_HELPER_SKIP_OWNER=1 run_helper "${invocation[@]}"
    [[ "$RUN_STATUS" -ne 0 && ! -s "$MDEV_LOG" && ! -s "$STDOUT_FILE" ]] \
      || fail "managed-owner test: $operation accepted a missing owner or accessed the device"
    assert_private_output_absent
    pass
  done

  for operation in inject interrupt status finalize capture end; do
    case "$operation" in
      inject) invocation=(inject --mdev-owner "$OTHER_MDEV_OWNER" --state "$state" --fixture "$fixture" --role request) ;;
      interrupt) invocation=(interrupt --mdev-owner "$OTHER_MDEV_OWNER" --state "$state" --fixture "$fixture") ;;
      status) invocation=(status --mdev-owner "$OTHER_MDEV_OWNER" --state "$state" --expect single_result_announced) ;;
      finalize) invocation=(finalize --mdev-owner "$OTHER_MDEV_OWNER" --state "$state" --finalization-output "$TMP_DIR/wrong-owner-finalization.json") ;;
      capture) invocation=(capture --mdev-owner "$OTHER_MDEV_OWNER" --state "$state" --finalization "$finalization" --automation-output "$TMP_DIR/wrong-owner-automation.jsonl" --private-voice-output "$TMP_DIR/wrong-owner-private.ndjson" --sanitized-voice-output "$TMP_DIR/wrong-owner-sanitized.ndjson") ;;
      end) invocation=(end --mdev-owner "$OTHER_MDEV_OWNER" --state "$state" --finalization "$finalization" --cleanup-output "$TMP_DIR/wrong-owner-cleanup.json") ;;
    esac
    : >"$MDEV_LOG"
    run_helper "${invocation[@]}"
    [[ "$RUN_STATUS" -ne 0 && ! -s "$MDEV_LOG" && ! -s "$STDOUT_FILE" ]] \
      || fail "managed-owner test: $operation reused copied state under another owner"
    assert_private_output_absent
    pass
  done

  cp -- "$state" "$TMP_DIR/copied-owner-state.json"
  chmod 600 "$TMP_DIR/copied-owner-state.json"
  : >"$MDEV_LOG"
  run_helper status --mdev-owner "$OTHER_MDEV_OWNER" \
    --state "$TMP_DIR/copied-owner-state.json" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 && ! -s "$MDEV_LOG" ]] \
    || fail 'managed-owner test: copied state was reusable under another owner'
  assert_private_output_absent
  pass

  for operation in preflight start; do
    : >"$MDEV_LOG"
    if [[ "$operation" == preflight ]]; then
      run_helper preflight --mdev-owner $'owner\tinvalid' --package me.rerere.rikkahub.debug
    else
      run_helper start --mdev-owner $'owner\ninvalid' \
        --state "$TMP_DIR/invalid-owner-start.json" --package me.rerere.rikkahub.debug \
        --conversation-id conversation-owner --run-hash "$hash_a" \
        --comparison-hash "$hash_b" --fixture "$fixture"
    fi
    [[ "$RUN_STATUS" -ne 0 && ! -s "$MDEV_LOG" && ! -s "$STDOUT_FILE" ]] \
      || fail "managed-owner test: $operation accepted an invalid owner"
    assert_private_output_absent
    pass
  done
}

run_owner_lock_key_test() {
  local ready="$TMP_DIR/owner-lock-ready"
  local release="$TMP_DIR/owner-lock-release"
  local first_stdout="$TMP_DIR/owner-lock-first.stdout"
  local first_stderr="$TMP_DIR/owner-lock-first.stderr"
  local first_pid first_status same_status other_status
  local first_hash="sha256:$(printf '1%.0s' {1..64})"
  local other_hash="sha256:$(printf '2%.0s' {1..64})"

  bash -s -- "$LIBRARY" "$first_hash" "$ready" "$release" <<'BASH' \
      >"$first_stdout" 2>"$first_stderr" &
set -euo pipefail
source "$1"
MDEV_OWNER_HASH="$2"
PACKAGE=me.rerere.rikkahub.debug
SERIAL=LEGACY_SERIAL_MUST_NOT_KEY_LOCK
ERROR_REPORTED=0
HOST_LOCK_FD=''
HOST_LOCK_ROOT_FD=''
acquire_host_operation_lock
: >"$3"
while [[ ! -e "$4" ]]; do sleep 0.01; done
BASH
  first_pid=$!
  wait_for_path "$ready" || {
    kill -TERM "$first_pid" 2>/dev/null || true
    wait "$first_pid" 2>/dev/null || true
    fail 'owner-lock test: first owner did not acquire its lock'
  }

  set +e
  bash -s -- "$LIBRARY" "$other_hash" <<'BASH' >/dev/null 2>&1
set -euo pipefail
source "$1"
MDEV_OWNER_HASH="$2"
PACKAGE=me.rerere.rikkahub.debug
SERIAL=LEGACY_SERIAL_MUST_NOT_KEY_LOCK
ERROR_REPORTED=0
HOST_LOCK_FD=''
HOST_LOCK_ROOT_FD=''
acquire_host_operation_lock
BASH
  other_status=$?
  bash -s -- "$LIBRARY" "$first_hash" <<'BASH' >/dev/null 2>&1
set -euo pipefail
source "$1"
MDEV_OWNER_HASH="$2"
PACKAGE=me.rerere.rikkahub.debug
SERIAL=LEGACY_SERIAL_MUST_NOT_KEY_LOCK
ERROR_REPORTED=0
HOST_LOCK_FD=''
HOST_LOCK_ROOT_FD=''
acquire_host_operation_lock
BASH
  same_status=$?
  set -e

  : >"$release"
  set +e
  wait "$first_pid"
  first_status=$?
  set -e
  [[ "$first_status" -eq 0 && "$other_status" -eq 0 && "$same_status" -ne 0 ]] ||
    fail 'owner-lock test: lock key was not exact owner hash plus package'
  [[ ! -s "$first_stdout" && ! -s "$first_stderr" ]] ||
    fail 'owner-lock test: lock owner emitted output'
  pass
}

run_general_validation_tests() {
  printf '%s\n' "$TMP_DIR/private-path-probe" > "$STDOUT_FILE"
  if (assert_private_output_absent "$TMP_DIR/private-path-probe") 2>/dev/null; then
    fail "private-output test: actual invocation path checker did not reject a leak"
  fi
  : > "$STDOUT_FILE"
  : > "$STDERR_FILE"
  pass

  assert_rejected '' 'usage: voice-agent-real-room-step.sh OPERATION [options]'
  assert_rejected 'unknown' 'invalid operation'
  local operation
  for operation in preflight start inject interrupt status finalize capture end; do
    assert_operation_exists "$operation"
    assert_rejected "$operation --unknown-option value" 'unknown option'
  done

  assert_rejected 'inject --role invalid' 'invalid fixture role'
  assert_rejected 'preflight --mdev-owner OWNER_SECRET_123 --mdev-owner SECOND --package me.rerere.rikkahub.debug' 'repeated option'
  assert_rejected 'start --state one --state two' 'repeated option'
  assert_rejected 'inject --state one --state two' 'repeated option'
  assert_rejected 'interrupt --fixture one --fixture two' 'repeated option'
  assert_rejected 'status --state one --state two' 'repeated option'
  assert_rejected 'finalize --state one --state two' 'repeated option'
  assert_rejected 'capture --automation-output one --automation-output two' 'repeated option'
  assert_rejected 'end --cleanup-output one --cleanup-output two' 'repeated option'

  reset_fake
  local fixture="$TMP_DIR/fixture-validation.pcm"
  local second_fixture="$TMP_DIR/fixture-validation-second.pcm"
  make_fixture "$fixture"
  make_second_fixture "$second_fixture"
  if ! bash -s -- "$LIBRARY" "$fixture" "$second_fixture" "$TMP_DIR" <<'BASH'
set -euo pipefail
source "$1"
LOCAL_TEMP_DIR=''
declare -a OWNED_TEMP_FILES=()
TMPDIR="$4"
first_snapshot=''
first_size=''
first_hash=''
second_snapshot=''
second_size=''
second_hash=''
snapshot_fixture "$2" first_snapshot first_size first_hash
snapshot_fixture "$3" second_snapshot second_size second_hash
[[ "$first_snapshot" != "$second_snapshot" ]]
[[ "$first_size" == 8 && "$second_size" == 8 && "$first_hash" != "$second_hash" ]]
cmp -s -- "$first_snapshot" "$2"
cmp -s -- "$second_snapshot" "$3"
cleanup_local_temps
BASH
  then
    fail "fixture-snapshot test: a later snapshot overwrote earlier immutable results"
  fi
  pass
  run_helper start --state relative-state --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "absolute-output test: relative start state succeeded"
  assert_no_adb_mutations
  pass

  local state="$TMP_DIR/validation-state.json"
  write_valid_state "$state"
  local second_state="$TMP_DIR/validation-state-second.json"
  python3 - "$second_state" "$MDEV_OWNER" <<'PY'
import hashlib
import json
import os
import sys

payload = {
    "schemaVersion": 3,
    "mdevOwnerHash": "sha256:" + hashlib.sha256(sys.argv[2].encode()).hexdigest(),
    "package": "me.rerere.rikkahub.debug",
    "androidUserId": 0,
    "packageUid": 10123,
    "conversationId": "SECOND_CONVERSATION",
    "runHash": "sha256:" + "c" * 64,
    "comparisonHash": "sha256:" + "d" * 64,
    "fixtureToken": "fixture-2",
    "fixtureParentIdentity": "123:456:40700:10123:10123",
    "fixtureDirectoryIdentity": "123:790:40700:10123:10123",
    "fixtureOwnershipNonce": "fedcba9876543210fedcba9876543210",
    "traceId": "trace-second",
    "transport": "livekit_experimental",
}
descriptor = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, separators=(",", ":"))
    handle.write("\n")
PY
  if ! bash -s -- "$LIBRARY" "$state" "$second_state" "$CURRENT_UID" <<'BASH'
set -euo pipefail
TRANSPORT_EXPECTED=livekit_experimental
PACKAGE_EXPECTED=me.rerere.rikkahub.debug
source "$1"
first="$(decode_state "$2")"
second="$(decode_state "$3")"
[[ "$first" == *$'0\n'"$4"$'\nCONVERSATION_SECRET_123\n'* ]]
[[ "$second" == *$'0\n10123\nSECOND_CONVERSATION\n'* ]]
[[ "$first" != "$second" && "$first" == *$'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'* ]]
[[ "$first" == *":40700:$4:"*$'\n'*$'0123456789abcdef0123456789abcdef\n'* ]]
BASH
  then
    fail "state-v3-snapshot test: exact owner hash and identity receipt did not decode immutably"
  fi
  pass
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
  run_helper start --state "$TMP_DIR/directory-state.json" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$invalid"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-type test: directory fixture succeeded"
  assert_no_adb_mutations
  pass
  rmdir "$invalid"

  mkfifo "$invalid"
  run_helper start --state "$TMP_DIR/fifo-state.json" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$invalid"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-type test: FIFO fixture succeeded"
  assert_no_adb_mutations
  pass
  rm "$invalid"

  ln -s "$fixture" "$invalid"
  run_helper start --state "$TMP_DIR/symlink-state.json" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$invalid"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-type test: symlink fixture succeeded"
  assert_no_adb_mutations
  pass
  rm "$invalid"

  chmod 644 "$fixture"
  run_helper start --state "$TMP_DIR/mode-state.json" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-mode test: permissive fixture succeeded"
  assert_no_adb_mutations
  pass
  chmod 600 "$fixture"

  : > "$fixture"
  run_helper start --state "$TMP_DIR/empty-state.json" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-content test: zero-byte PCM succeeded"
  assert_no_adb_mutations
  pass

  local malformed="$TMP_DIR/malformed-state.json"
  printf '{not-json}\n' > "$malformed"
  chmod 600 "$malformed"
  run_helper status --state "$malformed" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-schema test: malformed state succeeded"
  assert_no_adb_mutations
  pass

  local wrong_package="$TMP_DIR/wrong-package-state.json"
  write_valid_state "$wrong_package" me.rerere.rikkahub.release
  run_helper status --state "$wrong_package" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-package test: wrong package succeeded"
  assert_no_adb_mutations
  pass

  local state_link="$TMP_DIR/state-link.json"
  ln -s "$state" "$state_link"
  run_helper status --state "$state_link" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-type test: symlink state succeeded"
  assert_no_adb_mutations
  pass


  local state_directory="$TMP_DIR/state-directory"
  mkdir "$state_directory"
  run_helper status --state "$state_directory" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-type test: directory state succeeded"
  assert_no_adb_mutations
  pass
  rmdir "$state_directory"

  local state_fifo="$TMP_DIR/state-fifo"
  mkfifo "$state_fifo"
  run_helper status --state "$state_fifo" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-type test: FIFO state succeeded"
  assert_no_adb_mutations
  pass
  rm "$state_fifo"

  chmod 644 "$state"
  run_helper status --state "$state" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "state-mode test: permissive state succeeded"
  assert_no_adb_mutations
  pass

  reset_fake
  export FAKE_ADB_DEVICE_LOST=1
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "managed-phone test: offline owner-scoped phone succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  export FAKE_ADB_EMULATOR=1
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "physical-device test: emulator properties succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  export FAKE_ADB_NO_RUN_AS=1
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "run-as test: missing run-as succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  export FAKE_TIMEOUT_EXIT=124
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "timeout test: timed-out ADB succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  reset_fake
  VOICE_STEP_ADB_TIMEOUT_SECONDS=0 run_helper preflight \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "timeout-validation test: zero timeout succeeded"
  [[ ! -s "$ADB_LOG" ]] || fail "timeout-validation test: ADB ran before timeout validation"
  pass

  reset_fake
  export FAKE_TIMEOUT_ENFORCE=1
  export FAKE_ADB_BLOCK=1
  local timeout_started=$SECONDS
  VOICE_STEP_ADB_TIMEOUT_SECONDS=1 run_helper preflight \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  local timeout_elapsed=$((SECONDS - timeout_started))
  [[ "$RUN_STATUS" -ne 0 && "$timeout_elapsed" -lt 4 ]] ||
    fail "timeout-enforcement test: blocking ADB was not terminated by the configured deadline"
  python3 - "$TIMEOUT_LOG" <<'PY' || fail "timeout-shape test: bounded ADB argv prefix changed"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
bounded = [command for command in commands if b"--signal=TERM" in command]
assert bounded
assert bounded[0][:11] == [
    b"--signal=TERM", b"--kill-after=2s", b"1s", b"mdev",
    b"android", b"adb", b"--device", b"phone", b"--owner",
    b"OWNER_SECRET_123", b"--",
]
PY
  assert_private_output_absent
  pass

  [[ -r "$LIBRARY" ]] || fail "shared-library test: real-room security library is absent"
  pass
}

run_preflight_tests() {
  reset_fake
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -eq 0 ]] ||
    fail "broadcast-framing test: literal multiline resultData was not consumed as one receiver record"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=preflight\nvoice-step.device=ready\nvoice-step.package=ready\nvoice-step.automation=ready\nvoice-step.protected_path=ready'
  [[ "$(exact_command_count -s DEVICE_SECRET_123 get-state)" == "1" ]] ||
    fail "preflight-command test: exact owner-scoped get-state was not required once"
  [[ "$(command_count broadcast)" == "1" && "$(command_count .STATUS)" == "1" ]] ||
    fail "preflight-read-only test: STATUS was not the sole broadcast"
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell cmd activity get-current-user)" == "1" ]] ||
    fail "preflight-user test: exact Android user readback was not required once"
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell cmd package list packages --user 0 -U --show-stopped me.rerere.rikkahub.debug)" == "1" ]] ||
    fail "preflight-stopped-state test: exact package stopped-state row was not required once"
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell cmd package list packages --user 0 --uid "$CURRENT_UID")" == "1" ]] ||
    fail "preflight-uid test: exact unique-UID readback was not required once"
  [[ "$(exact_command_count -s DEVICE_SECRET_123 exec-out ps -A -n -o UID,PID,PPID,STAT,NAME)" == "1" &&
     "$(exact_command_count -s DEVICE_SECRET_123 shell cmd activity get-isolated-pids "$CURRENT_UID")" == "1" ]] ||
    fail "preflight-process test: exact package-process capability readbacks were absent"
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell run-as me.rerere.rikkahub.debug --user 0 id)" == "1" ]] ||
    fail "preflight-run-as test: package access was not pinned to the resolved Android user"
  [[ "$(command_count start-foreground-service)" == "0" ]] || fail "preflight-read-only test: service was started"
  [[ "$(command_count mkdir)" == "0" ]] || fail "preflight-read-only test: remote directory was created"
  [[ "$(command_count rm)" == "0" ]] || fail "preflight-read-only test: remote file was removed"
  if ! python3 - "$MDEV_LOG" "$MDEV_OWNER" <<'PY'
import sys

raw = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in raw.split(b"\0\0") if chunk]
prefix = [
    b"android", b"adb", b"--device", b"phone", b"--owner",
    sys.argv[2].encode(), b"--",
]
assert commands and all(command[:len(prefix)] == prefix for command in commands)
assert all(b"RAW_ADB" not in command for command in commands)
PY
  then
    fail "managed-transport test: preflight used raw or non-owner-scoped Android access"
  fi
  pass

  reset_fake
  python3 - "$FAKE_STATE" <<'PY'
import json, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["automation_state"] = "active"
json.dump(state, open(path, "w", encoding="utf-8"), separators=(",", ":"))
PY
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] || fail "preflight-idle test: active automation succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass

  local malformed_mode
  for malformed_mode in android-user stopped-row shared-uid ps-header isolated; do
    reset_fake
    case "$malformed_mode" in
      android-user) export FAKE_ADB_MALFORMED_ANDROID_USER=1 ;;
      stopped-row) export FAKE_ADB_MALFORMED_STOPPED_ROW=1 ;;
      shared-uid) export FAKE_ADB_SHARED_UID=1 ;;
      ps-header) export FAKE_ADB_MALFORMED_QUIESCENCE=ps-header ;;
      isolated) export FAKE_ADB_MALFORMED_QUIESCENCE=isolated ;;
    esac
    run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
    [[ "$RUN_STATUS" -ne 0 ]] || fail "preflight-capability test: malformed $malformed_mode readback succeeded"
    assert_no_adb_mutations
    assert_private_output_absent
    pass
  done

  reset_fake
  export FAKE_ADB_BROADCAST_TRAILING_JUNK=1
  run_helper preflight --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] ||
    fail "broadcast-framing test: trailing junk after the literal multiline resultData succeeded"
  assert_no_adb_mutations
  assert_private_output_absent
  pass
}

run_start_tests() {
  reset_fake
  export FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT=1
  local fixture="$TMP_DIR/start-fixture.pcm"
  local state="$TMP_DIR/start-state.json"
  make_fixture "$fixture"
  run_helper start --state "$state" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  [[ -f "$state" && ! -L "$state" && "$(stat -c '%a' "$state")" == "600" ]] ||
    fail "start-publication test: state was not a mode-0600 regular file"
  python3 - "$state" "$FAKE_STATE" "$MDEV_OWNER" <<'PY' || fail "start-state test: private state contract mismatch"
import hashlib, json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    fake = json.load(handle)
assert list(state) == [
    "schemaVersion", "mdevOwnerHash", "package", "androidUserId", "packageUid",
    "conversationId", "runHash", "comparisonHash", "fixtureToken",
    "fixtureParentIdentity", "fixtureDirectoryIdentity", "fixtureOwnershipNonce",
    "traceId", "transport",
]
assert state == {
    "schemaVersion": 3,
    "mdevOwnerHash": "sha256:" + hashlib.sha256(sys.argv[3].encode()).hexdigest(),
    "package": "me.rerere.rikkahub.debug",
    "androidUserId": 0,
    "packageUid": fake["package_uid"],
    "conversationId": "CONVERSATION_SECRET_123",
    "runHash": "sha256:" + "a" * 64,
    "comparisonHash": "sha256:" + "b" * 64,
    "fixtureToken": "fixture-1",
    "fixtureParentIdentity": fake["fixture_parent_identity"],
    "fixtureDirectoryIdentity": fake["fixture_directory_identity"],
    "fixtureOwnershipNonce": "0123456789abcdef0123456789abcdef",
    "traceId": "trace-new",
    "transport": "livekit_experimental",
}
raw = open(sys.argv[1], "rb").read()
assert b"OWNER_SECRET_123" not in raw and b"DEVICE_SECRET_123" not in raw
PY
  command_sequence_present voice-step-create-owned-directory voice-step-stage-owned-fixture PREPARE ARM_CAPTURE_FIXTURE start-foreground-service ||
    fail "start-order test: one start mutation sequence was not preserved"
  [[ "$(command_count PREPARE)" == "1" ]] || fail "start-retry test: PREPARE was not sent exactly once"
  [[ "$(command_count ARM_CAPTURE_FIXTURE)" == "1" ]] || fail "start-retry test: ARM was not sent exactly once"
  [[ "$(command_count start-foreground-service)" == "1" ]] || fail "start-retry test: START was not sent exactly once"
  assert_bound_action_extras 'me.rerere.rikkahub.voiceagent.action.START'
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.START'
  python3 - "$FAKE_STATE" <<'PY' || fail "start-ownership test: fixture directory was not newly owned and staged"
import json, sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
directory = "files/voice-real-room/" + "a" * 64
fixture = directory + "/request-66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72.pcm"
assert state["remote_directory"] == directory
assert state["owner_hash"].startswith("sha256:") and len(state["owner_hash"]) == 71
assert state["remote_files"][fixture]["type"] == "regular"
assert state["fixture_parent_identity"].split(":")[2] == "40700"
assert state["fixture_directory_identity"].split(":")[2] == "40700"
assert state["fixture_parent_identity"].split(":")[3] == str(state["package_uid"])
assert state["fixture_directory_identity"].split(":")[3] == str(state["package_uid"])
assert state["fixture_ownership_nonce"] == "0123456789abcdef0123456789abcdef"
PY
  python3 - "$ADB_LOG" <<'PY' || fail "start-integrity test: ARM omitted immutable fixture metadata"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
arm = [command for command in commands if any(b"ARM_CAPTURE_FIXTURE" in value for value in command)]
assert len(arm) == 1
assert b"expected_size" in arm[0] and b"8" in arm[0]
assert b"expected_sha256" in arm[0]
assert b"sha256:66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72" in arm[0]
PY
  python3 - "$ADB_LOG" <<'PY' || fail "start-script-transport test: app-private scripts were not one managed shell argument"
import shlex
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
expected_shell_markers = {
    "voice-step-protected-root": 1,
    "voice-step-trace-probe": 2,
    "voice-step-create-owned-directory": 1,
    "voice-step-stage-owned-fixture": 1,
}
scripts_by_marker = {}
for marker, expected_count in expected_shell_markers.items():
    matches = [
        command for command in commands
        if any(marker.encode() in value for value in command)
    ]
    assert len(matches) == expected_count
    scripts_by_marker[marker] = []
    for match in matches:
        tail = match[7:]
        assert len(tail) == 2 and tail[0] == b"shell"
        decoded = shlex.split(tail[1].decode(), posix=True)
        assert decoded[0:5] == [
            "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh",
        ]
        assert decoded[5] == "-c" and marker in decoded[6] and decoded[7] == "sh"
        scripts_by_marker[marker].append(decoded[6].encode())

create_script = scripts_by_marker["voice-step-create-owned-directory"][0]
assert b"/proc/self/fd/" not in create_script
for descriptor in (3, 4, 5):
    assert f"/proc/$$/fd/{descriptor}".encode() in create_script

stage_script = scripts_by_marker["voice-step-stage-owned-fixture"][0]
assert b"/proc/self/fd/" not in stage_script
assert b"/proc/$$/fd/3" in stage_script
assert b"$'\\n'" not in stage_script
assert b'newline=$(printf "\\nx") || exit 1' in stage_script
assert b'newline=${newline%x}' in stage_script
assert b'"$owner$newline"[0-9a-f]' in stage_script
PY
  pass

  reset_fake
  rm -f -- "$state"
  export FAKE_ADB_PREEXISTING_REMOTE_DIR=1
  run_helper start --state "$state" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$state" ]] ||
    fail "start-ownership test: a preexisting remote run directory was accepted"
  [[ "$(command_count PREPARE)" == "0" && "$(command_count ARM_CAPTURE_FIXTURE)" == "0" &&
     "$(command_count start-foreground-service)" == "0" && "$(command_count voice-step-remove-owned-directory)" == "0" ]] ||
    fail "start-ownership test: refusal mutated the run or removed an unowned directory"
  assert_private_output_absent
  pass

  reset_fake
  rm -f -- "$state"
  export FAKE_ADB_SUBSTITUTE_RUN_DIRECTORY_BEFORE_CREATE_ROLLBACK=1
  run_helper start --state "$state" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$state" ]] ||
    fail "start-create-rollback-race test: substituted directory reached a successful start"
  python3 - "$FAKE_STATE" <<'PY' || fail "start-create-rollback-race test: substitute was recursively deleted"
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["substitute_sentinel"] == "untouched", state["missing_cleanup_markers"]
assert state["moved_remote_directory"] == "files/voice-real-room/" + "a" * 64 + ".moved"
PY
  [[ "$(command_count PREPARE)" == "0" && "$(command_count ARM_CAPTURE_FIXTURE)" == "0" &&
     "$(command_count start-foreground-service)" == "0" ]] ||
    fail "start-create-rollback-race test: failed creation continued into run mutations"
  assert_private_output_absent
  pass

  reset_fake
  rm -f -- "$state"
  export FAKE_ADB_PRIVATE_NOISE=1
  run_helper start --state "$state" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  pass

  reset_fake
  local raced="$TMP_DIR/raced-state.json"
  export FAKE_ADB_CREATE_DESTINATION_ON_TRACE="$raced"
  run_helper start --state "$raced" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "start-race test: late destination race succeeded"
  [[ "$(<"$raced")" == "raced" ]] || fail "start-race test: existing destination was overwritten"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE)" == "0" ]] ||
    fail "start-race cleanup test: automation cleanup was not bound"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END)" == "0" ]] ||
    fail "start-race cleanup test: call cleanup was not bound"
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.END_BOUND'
  [[ "$(command_count force-stop)" == "1" &&
     "$(command_count voice-step-cleanup-broker)" == "1" &&
     "$(command_count voice-step-remove-owned-directory)" == "0" &&
     "$(command_count --include-stopped-packages)" == "1" ]] ||
    fail "start-race cleanup test: rollback bypassed quiescent broker restoration"
  assert_private_output_absent
  pass

  reset_fake
  local malformed_rollback="$TMP_DIR/malformed-quiescence-raced-state.json"
  export FAKE_ADB_CREATE_DESTINATION_ON_TRACE="$malformed_rollback"
  export FAKE_ADB_MALFORMED_QUIESCENCE_AFTER_FORCE_STOP=ps-header
  run_helper start --state "$malformed_rollback" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "start-malformed-quiescence test: failed start succeeded"
  python3 - "$FAKE_STATE" <<'PY' || fail "start-malformed-quiescence test: rollback bypassed restoration"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["package_stopped"] is False
assert state["restoration_count"] == 1
assert not state.get("cleanup_broker_completed", False)
PY
  pass

  reset_fake
  local cleanup_signaled="$TMP_DIR/cleanup-signaled-raced-state.json"
  export FAKE_ADB_CREATE_DESTINATION_ON_TRACE="$cleanup_signaled"
  export FAKE_ADB_SIGNAL_DURING_FORCE_STOP=SIGTERM
  run_helper start --state "$cleanup_signaled" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "start-cleanup-signal test: failed start succeeded"
  python3 - "$FAKE_STATE" <<'PY' || fail "start-cleanup-signal test: signal bypassed restoration"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["force_stop_observed"] is True
assert state["package_stopped"] is False
assert state["restoration_count"] == 1
PY
  pass

  reset_fake
  local signaled="$TMP_DIR/signaled-state.json"
  export FAKE_ADB_SIGNAL_ON_TRACE=1
  run_helper start --state "$signaled" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "start-signal test: interrupted start succeeded"
  [[ ! -e "$signaled" ]] || fail "start-signal test: interrupted state was published"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" ]] ||
    fail "start-signal cleanup test: bound rollback did not run exactly once"
  assert_private_output_absent
  pass


  reset_fake
  local before_link="$TMP_DIR/signal-before-link-state.json"
  export FAKE_LN_SIGNAL_DESTINATION="$before_link"
  export FAKE_LN_SIGNAL_TIMING=before
  run_helper start --state "$before_link" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$before_link" ]] ||
    fail "start-commit test: a pre-link signal published state"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" ]] ||
    fail "start-commit test: pre-link signal skipped bound rollback"
  pass

  reset_fake
  local after_link="$TMP_DIR/signal-after-link-state.json"
  export FAKE_LN_SIGNAL_DESTINATION="$after_link"
  export FAKE_LN_SIGNAL_TIMING=after
  run_helper start --state "$after_link" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && -f "$after_link" && ! -L "$after_link" ]] ||
    fail "start-commit test: post-link signal did not retain committed state"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "0" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "0" ]] ||
    fail "start-commit test: post-link signal rolled back committed resources"
  python3 - "$FAKE_STATE" <<'PY' || fail "start-commit test: committed live state was not retained"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["automation_state"] == "active"
assert state["call_active"] is True
PY
  assert_private_output_absent
  pass

  reset_fake
  local pinned_state="$TMP_DIR/pinned-user-start-state.json"
  python3 - "$FAKE_STATE" <<'PY'
import json, os, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["android_user_id"] = 10
temporary = path + ".user"
json.dump(state, open(temporary, "w", encoding="utf-8"), separators=(",", ":"))
os.replace(temporary, path)
PY
  run_helper start --state "$pinned_state" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.START' 10

  reset_fake
  local pinned_race="$TMP_DIR/pinned-user-raced-state.json"
  python3 - "$FAKE_STATE" <<'PY'
import json, os, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["android_user_id"] = 10
temporary = path + ".user"
json.dump(state, open(temporary, "w", encoding="utf-8"), separators=(",", ":"))
os.replace(temporary, path)
PY
  export FAKE_ADB_CREATE_DESTINATION_ON_TRACE="$pinned_race"
  run_helper start --state "$pinned_race" --mdev-owner OWNER_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "pinned-user rollback test: raced start succeeded"
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.END_BOUND' 10
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell am broadcast --user 10 --include-stopped-packages -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver -a me.rerere.rikkahub.voiceagent.automation.STATUS)" == 1 ]] ||
    fail "pinned-user rollback test: package restoration changed Android user"
  pass
}

run_tracing_tests() {
  local fixture="$TMP_DIR/tracing-fixture.pcm"
  local state="$TMP_DIR/tracing-state.json"
  local diagnostic="$TMP_DIR/tracing-diagnostic.txt"
  local hash_a="sha256:$(printf 'a%.0s' {1..64})"
  local hash_b="sha256:$(printf 'b%.0s' {1..64})"
  make_fixture "$fixture"

  reset_fake
  run_helper start --state "$state" --diagnostic-record "$diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  [[ ! -e "$diagnostic" ]] ||
    fail "tracing-success test: successful start published a diagnostic record"

  reset_fake
  printf 'preserve' > "$diagnostic"
  chmod 600 "$diagnostic"
  local existing_inode existing_bytes
  existing_inode="$(stat -c '%d:%i' "$diagnostic")"
  existing_bytes="$(<"$diagnostic")"
  run_helper start --state "$state" --diagnostic-record "$diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && "$(stat -c '%d:%i' "$diagnostic")" == "$existing_inode" &&
     "$(<"$diagnostic")" == "$existing_bytes" ]] ||
    fail "tracing-existing-destination test: destination changed"
  assert_private_output_absent
  pass

  reset_fake
  local insecure_parent="$TMP_DIR/insecure-diagnostic-parent"
  local insecure_diagnostic="$insecure_parent/diagnostic.txt"
  mkdir "$insecure_parent"
  chmod 755 "$insecure_parent"
  run_helper start --state "$state" --diagnostic-record "$insecure_diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && ! -s "$MDEV_LOG" ]] ||
    fail "tracing-insecure-parent test: validation reached managed transport"
  assert_private_output_absent
  pass

  reset_fake
  local alias="$TMP_DIR/tracing-alias.json"
  run_helper start --state "$alias" --diagnostic-record "$alias" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$alias" && ! -s "$MDEV_LOG" ]] ||
    fail "tracing-alias test: validation created output or reached managed transport"
  assert_private_output_absent
  pass

  reset_fake
  local state_alias_parent="$TMP_DIR/tracing-state-alias-parent"
  local diagnostic_alias_parent="$TMP_DIR/tracing-diagnostic-alias-parent"
  local aliased_state="$state_alias_parent/shared-output"
  local aliased_diagnostic="$diagnostic_alias_parent/shared-output"
  local destination_alias_site="$TMP_DIR/tracing-destination-alias-site"
  mkdir "$state_alias_parent" "$diagnostic_alias_parent"
  chmod 700 "$state_alias_parent" "$diagnostic_alias_parent"
  make_destination_parent_identity_alias_site "$destination_alias_site"
  PYTHONPATH="$destination_alias_site" \
    VOICE_STEP_TEST_STATE_PARENT="$state_alias_parent" \
    VOICE_STEP_TEST_DIAGNOSTIC_PARENT="$diagnostic_alias_parent" \
    run_helper start --state "$aliased_state" --diagnostic-record "$aliased_diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$aliased_state" &&
     ! -e "$aliased_diagnostic" && ! -s "$MDEV_LOG" ]] ||
    fail "tracing-namespace-alias test: aliased destinations reached managed transport or publication"
  assert_private_output_absent "$state_alias_parent" "$diagnostic_alias_parent"
  pass

  assert_stage_transition_signal_boundary
  pass

  reset_fake
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    option-validation invalid-run-hash none complete --run-hash invalid
  pass

  reset_fake
  export VOICE_STEP_ADB_TIMEOUT_SECONDS=0
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    runtime-validation invalid-timeout-configuration none complete
  unset VOICE_STEP_ADB_TIMEOUT_SECONDS
  pass

  reset_fake
  chmod 644 "$fixture"
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    fixture-snapshot invalid-fixture none complete
  chmod 600 "$fixture"
  pass

  local exit_match stage category cleanup
  while IFS=':' read -r exit_match stage category cleanup; do
    reset_fake
    export FAKE_ADB_EXIT_MATCH="$exit_match"
    export FAKE_ADB_EXIT_STATUS=73
    assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
      "$stage" "$category" 73 "$cleanup"
    pass
  done <<'EOF'
get-state:device-readiness:device-not-ready:complete
get-current-user:package-identity:android-user-readback-failed:complete
dumpsys package:package-contract:package-readback-failed:complete
.STATUS:status-read:unexpected-status-response:complete
voice-step-trace-probe:trace-read:trace-readback-failed:complete
voice-step-create-owned-directory:fixture-directory:fixture-staging-failed:complete
voice-step-stage-owned-fixture:fixture-stage:fixture-staging-failed:complete
.PREPARE:automation-prepare:adb-command-failed:complete
ARM_CAPTURE_FIXTURE:fixture-arm:adb-command-failed:complete
start-foreground-service:service-start:call-start-failed:failed
EOF

  reset_fake
  export FAKE_ADB_EXIT_MATCH=action.START
  export FAKE_ADB_EXIT_STATUS=73
  export FAKE_ADB_FAIL_END=1
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    service-start call-start-failed 73 failed
  pass

  reset_fake
  export FAKE_ADB_EXIT_MATCH=ARM_CAPTURE_FIXTURE
  export FAKE_ADB_EXIT_STATUS=73
  export FAKE_ADB_FAIL_CLEANUP_BROKER=1
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    fixture-arm adb-command-failed 73 failed
  if compgen -G "$HELPER_TEMP_ROOT"'/voice-real-room-step.*/.voice-step-diagnostic.*' >/dev/null; then
    fail "tracing-cleanup-precedence test: diagnostic temporary residue remained"
  fi
  assert_private_output_absent
  pass

  reset_fake
  local failure_publication_parent="$TMP_DIR/tracing-failure-publication-parent"
  local failure_publication_diagnostic="$failure_publication_parent/diagnostic.txt"
  local failure_publication_site="$TMP_DIR/tracing-failure-publication-site"
  local failure_publication_marker="$TMP_DIR/tracing-failure-publication-marker"
  mkdir "$failure_publication_parent"
  chmod 700 "$failure_publication_parent"
  make_diagnostic_tmpfile_failure_site "$failure_publication_site" "$failure_publication_marker"
  export FAKE_ADB_EXIT_MATCH=ARM_CAPTURE_FIXTURE
  export FAKE_ADB_EXIT_STATUS=73
  PYTHONPATH="$failure_publication_site" \
    VOICE_STEP_DIAGNOSTIC_TMPFILE_MARKER="$failure_publication_marker" \
    run_helper start --state "$state" --diagnostic-record "$failure_publication_diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ -f "$failure_publication_marker" &&
     "$(<"$failure_publication_marker")" == tmpfile-attempted ]] ||
    fail "tracing-failure-publication test: anonymous publication was not attempted"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$failure_publication_diagnostic" ]] ||
    fail "tracing-failure-publication test: failed publication changed the destination"
  [[ "$(tail -n 1 "$STDERR_FILE")" == \
     'voice-step.diagnostic=stage:fixture-arm,category:adb-command-failed' ]] ||
    fail "tracing-failure-publication test: original failure summary was replaced"
  [[ "$(grep -Fxc \
    'voice-step.diagnostic=stage:fixture-arm,category:adb-command-failed' \
    "$STDERR_FILE")" == 1 ]] ||
    fail "tracing-failure-publication test: original failure emitted multiple summaries"
  [[ "$(grep -Ec '^voice-step.error=' "$STDERR_FILE")" == 1 &&
     "$(grep -Fxc 'voice-step.error=diagnostic publication failed' "$STDERR_FILE")" == 0 &&
     "$(grep -Fxc \
       'voice-step.diagnostic=stage:fixture-arm,category:diagnostic-publication-failed' \
       "$STDERR_FILE")" == 0 ]] ||
    fail "tracing-failure-publication test: publication failure changed sanitized terminal output ($(tr '\n' ' ' < "$STDERR_FILE"))"
  [[ -z "$(find "$failure_publication_parent" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
    fail "tracing-failure-publication test: named diagnostic residue remained"
  assert_private_output_absent "$failure_publication_parent"
  pass

  reset_fake
  export FAKE_ADB_START_DOES_NOT_ACTIVATE=1
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    call-activation call-activation-timed-out 1 complete
  pass

  reset_fake
  export FAKE_ADB_START_RETAINS_TRACE=1
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    trace-activation trace-activation-timed-out none complete
  pass

  reset_fake
  export FAKE_LN_RACE_DESTINATION="$state"
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    state-publication state-publication-failed none complete
  pass

  local lock_fixture="$TMP_DIR/tracing-lock-fixture.pcm"
  local lock_state="$TMP_DIR/tracing-lock-state.json"
  local lock_diagnostic="$TMP_DIR/tracing-lock-diagnostic.txt"
  local lock_ready="$TMP_DIR/tracing-lock-ready"
  local lock_release="$TMP_DIR/tracing-lock-release"
  local lock_stdout="$TMP_DIR/tracing-lock-owner.stdout"
  local lock_stderr="$TMP_DIR/tracing-lock-owner.stderr"
  local lock_tmp="$TMP_DIR/tracing-lock-tmp"
  local lock_pid lock_status
  reset_fake
  make_second_fixture "$lock_fixture"
  mkdir "$lock_tmp"
  chmod 700 "$lock_tmp"
  export FAKE_ADB_BLOCK_MATCH=voice-step-stage-owned-fixture
  export FAKE_ADB_BLOCK_READY="$lock_ready"
  export FAKE_ADB_BLOCK_RELEASE="$lock_release"
  TMPDIR="$lock_tmp" "$HELPER" start --state "$lock_state" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$lock_fixture" \
    >"$lock_stdout" 2>"$lock_stderr" &
  lock_pid=$!
  if ! wait_for_path "$lock_ready"; then
    kill -TERM "$lock_pid" 2>/dev/null || true
    wait "$lock_pid" 2>/dev/null || true
    fail "tracing-host-lock test: lock owner did not enter controlled boundary"
  fi
  assert_traced_start_failure "$fixture" "$state" "$diagnostic" \
    host-lock host-operation-already-active none complete
  : > "$lock_release"
  set +e
  wait "$lock_pid"
  lock_status=$?
  set -e
  [[ "$lock_status" -eq 0 ]] || fail "tracing-host-lock test: lock owner did not complete"
  unset FAKE_ADB_BLOCK_MATCH FAKE_ADB_BLOCK_READY FAKE_ADB_BLOCK_RELEASE
  pass

  reset_fake
  local link_race_parent="$TMP_DIR/tracing-link-race-parent"
  local link_race_diagnostic="$link_race_parent/diagnostic.txt"
  local link_race_site="$TMP_DIR/tracing-link-race-site"
  local link_race_marker="$TMP_DIR/tracing-link-race-marker"
  local link_race_unlink_marker="$TMP_DIR/tracing-link-race-unlink-marker"
  mkdir "$link_race_parent"
  chmod 700 "$link_race_parent"
  make_diagnostic_link_race_site "$link_race_site" "$link_race_marker" \
    "$link_race_unlink_marker"
  export FAKE_ADB_EXIT_MATCH=ARM_CAPTURE_FIXTURE
  export FAKE_ADB_EXIT_STATUS=73
  PYTHONPATH="$link_race_site" \
    VOICE_STEP_DIAGNOSTIC_LINK_MARKER="$link_race_marker" \
    VOICE_STEP_DIAGNOSTIC_UNLINK_MARKER="$link_race_unlink_marker" \
    VOICE_STEP_DIAGNOSTIC_DESTINATION="$link_race_diagnostic" \
    run_helper start --state "$state" --diagnostic-record "$link_race_diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ -f "$link_race_marker" && "$(<"$link_race_marker")" == link-raced ]] ||
    fail "tracing-link-race test: deterministic destination race did not run"
  [[ "$RUN_STATUS" -ne 0 && "$(<"$link_race_diagnostic")" == raced ]] ||
    fail "tracing-link-race test: raced destination changed"
  [[ ! -e "$link_race_unlink_marker" ]] ||
    fail "tracing-link-race test: publisher attempted to unlink the destination"
  [[ "$(grep -Ec '^voice-step.error=' "$STDERR_FILE")" == 1 &&
     "$(grep -Fxc \
       'voice-step.diagnostic=stage:fixture-arm,category:adb-command-failed' \
       "$STDERR_FILE")" == 1 &&
     "$(tail -n 1 "$STDERR_FILE")" == \
       'voice-step.diagnostic=stage:fixture-arm,category:adb-command-failed' ]] ||
    fail "tracing-link-race test: original failure summaries changed"
  assert_private_output_absent "$link_race_parent"
  pass

  reset_fake
  rm -f -- "$state"
  local parent_race_parent="$TMP_DIR/tracing-parent-race-parent"
  local parent_race_pinned="$TMP_DIR/tracing-parent-race-pinned"
  local parent_race_replacement="$TMP_DIR/tracing-parent-race-replacement"
  local parent_race_diagnostic="$parent_race_parent/diagnostic.txt"
  local parent_race_site="$TMP_DIR/tracing-parent-race-site"
  local parent_race_marker="$TMP_DIR/tracing-parent-race-marker"
  mkdir "$parent_race_parent" "$parent_race_replacement"
  chmod 700 "$parent_race_parent" "$parent_race_replacement"
  printf 'pinned-preserved\n' > "$parent_race_parent/sentinel"
  printf 'replacement-preserved\n' > "$parent_race_replacement/sentinel"
  chmod 600 "$parent_race_parent/sentinel" "$parent_race_replacement/sentinel"
  make_diagnostic_parent_race_site "$parent_race_site"
  export FAKE_ADB_EXIT_MATCH=ARM_CAPTURE_FIXTURE
  export FAKE_ADB_EXIT_STATUS=73
  PYTHONPATH="$parent_race_site" \
    VOICE_STEP_DIAGNOSTIC_PARENT="$parent_race_parent" \
    VOICE_STEP_DIAGNOSTIC_PINNED_PARENT="$parent_race_pinned" \
    VOICE_STEP_DIAGNOSTIC_REPLACEMENT_PARENT="$parent_race_replacement" \
    VOICE_STEP_DIAGNOSTIC_PARENT_MARKER="$parent_race_marker" \
    run_helper start --state "$state" --diagnostic-record "$parent_race_diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ -f "$parent_race_marker" && "$(<"$parent_race_marker")" == parent-replaced ]] ||
    fail "tracing-parent-race test: deterministic parent replacement did not run"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$parent_race_pinned/diagnostic.txt" &&
     ! -e "$parent_race_replacement/diagnostic.txt" &&
     "$(<"$parent_race_pinned/sentinel")" == pinned-preserved &&
     "$(<"$parent_race_replacement/sentinel")" == replacement-preserved ]] ||
    fail "tracing-parent-race test: publication followed or deleted through replacement"
  [[ "$(grep -Ec '^voice-step.error=' "$STDERR_FILE")" == 1 &&
     "$(grep -Fxc \
       'voice-step.diagnostic=stage:fixture-arm,category:adb-command-failed' \
       "$STDERR_FILE")" == 1 ]] ||
    fail "tracing-parent-race test: original failure summaries changed"
  assert_private_output_absent "$parent_race_parent" "$parent_race_pinned" \
    "$parent_race_replacement"
  pass

  reset_fake
  rm -f -- "$state"
  rm -f -- "$diagnostic"
  local success_fault_site="$TMP_DIR/tracing-success-fault-site"
  local success_fault_marker="$TMP_DIR/tracing-success-fault-marker"
  make_diagnostic_tmpfile_failure_site "$success_fault_site" "$success_fault_marker"
  PYTHONPATH="$success_fault_site" \
    VOICE_STEP_DIAGNOSTIC_TMPFILE_MARKER="$success_fault_marker" \
    run_helper start --state "$state" --diagnostic-record "$diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  [[ ! -e "$success_fault_marker" && ! -e "$diagnostic" ]] ||
    fail "tracing-success-fault test: successful start invoked the publisher"

  reset_fake
  rm -f -- "$state" "$diagnostic"
  export FAKE_ASSERT_CLOSED_DIAGNOSTIC_PARENT="$TMP_DIR"
  run_helper start --state "$state" --diagnostic-record "$diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
  [[ ! -e "$diagnostic" ]] ||
    fail "tracing-closed-parent test: successful start published a diagnostic record"

  reset_fake
  rm -f -- "$state" "$diagnostic"
  export FAKE_ADB_CALL_ACTIVE_VISIBLE_AFTER=2
  export FAKE_RM_SIGNAL_MATCH=diagnostic-managed-status
  run_helper start --state "$state" --diagnostic-record "$diagnostic" \
    --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
    --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
    --comparison-hash "$hash_b" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] ||
    fail "tracing-final-signal test: deferred signal exited successfully"
  local final_signal_child
  final_signal_child="$(sed -n 's/^child_exit_status=//p' "$diagnostic")"
  [[ "$final_signal_child" == none ]] ||
    fail "tracing-final-signal test: stale child status $final_signal_child survived a successful stage transition"
  assert_diagnostic_record "$diagnostic" complete interrupted none complete ||
    fail "tracing-final-signal test: interrupted failure record mismatch"
  [[ "$(grep -Fxc 'voice-step.error=interrupted' "$STDERR_FILE")" == 1 &&
     "$(grep -Fxc \
       'voice-step.diagnostic=stage:complete,category:interrupted' "$STDERR_FILE")" == 1 &&
     "$(tail -n 1 "$STDERR_FILE")" == \
       'voice-step.diagnostic=stage:complete,category:interrupted' ]] ||
    fail "tracing-final-signal test: interrupted summaries mismatch"
  [[ -f "$state" ]] ||
    fail "tracing-final-signal test: committed state was rolled back"
  python3 - "$FAKE_STATE" <<'PY' || fail "tracing-final-signal test: committed device state was rolled back"
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
assert state["automation_state"] == "active"
assert state["call_active"] is True
PY
  assert_private_output_absent
  pass

  python3 - "$ROOT_DIR/scripts/voice-agent-real-room-diagnostic-publisher.py" \
    "$ROOT_DIR/scripts/voice-agent-real-room-diagnostics.sh" "$HELPER" <<'PY' || fail "tracing-source-invariants test: failure-only source contract changed"
import sys
publisher, diagnostics, step = (
    open(path, encoding="utf-8").read() for path in sys.argv[1:]
)
assert "O_TMPFILE" in publisher
assert 'f"/proc/self/fd/{unnamed_fd}"' in publisher
assert "follow_symlinks=True" in publisher
assert "os._exit(0)" in publisher
assert publisher.index("os.link(") < publisher.index("os._exit(0)")
for forbidden in (
    ".voice-step-diagnostic.",
    "os.unlink(",
    "diagnostic_publish success",
    "diagnostic_remove_owned_destination",
    "diagnostic_take_published_identity",
    "DIAGNOSTIC_PARENT_FD",
    "DIAGNOSTIC_IDENTITY_READ_FD",
    "DIAGNOSTIC_IDENTITY_WRITE_FD",
    "DIAGNOSTIC_PUBLISHED_IDENTITY",
    "diagnostic-publication-failed",
):
    assert forbidden not in diagnostics + step + publisher
PY
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
import shlex
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
role = sys.argv[2]
expected_path = (
    "files/voice-real-room/" + "a" * 64 + "/" + role +
    "-66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72.pcm"
).encode()
stream = [command for command in commands if any(b"voice-step-stage-owned-fixture" in value for value in command)]
stage = [command for command in commands if any(b"STAGE_CAPTURE_FIXTURE" in value for value in command)]
trigger = [command for command in commands if any(b"TRIGGER_CAPTURE_FIXTURE" in value for value in command)]
assert len(stream) == len(stage) == len(trigger) == 1
tail = stream[0][7:]
assert len(tail) == 2 and tail[0] == b"shell"
decoded = shlex.split(tail[1].decode(), posix=True)
assert decoded[0:6] == [
    "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh", "-c",
]
assert "voice-step-stage-owned-fixture" in decoded[6]
assert decoded[7] == "sh" and decoded[9].encode() == expected_path
assert b"exec-in" not in stream[0]
stream_script = next(value for value in stream[0] if b"voice-step-stage-owned-fixture" in value)
assert b"voice-step-descriptor-owned-stage" in stream_script
assert b"/proc/self/fd/" not in stream_script
assert b"/proc/$$/fd/3" in stream_script
assert b"mktemp" not in stream_script and b'cat > "$temporary"' not in stream_script
assert expected_path in stage[0] and expected_path in trigger[0]
assert b"fixture-1" in stage[0] and b"fixture-1" in trigger[0]
assert b"chunk_bytes" in stage[0] and b"3200" in stage[0]
assert b"chunk_delay_ms" in stage[0] and b"100" in stage[0]
assert b"expected_size" in stage[0] and b"8" in stage[0]
assert b"expected_sha256" in stage[0]
assert b"sha256:66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72" in stage[0]
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

  local hostile_type
  for hostile_type in symlink fifo; do
    reset_fake
    activate_fake_run
    rm -f -- "$state"
    write_valid_state "$state"
    make_fixture "$fixture"
    export FAKE_ADB_REMOTE_DESTINATION_TYPE="$hostile_type"
    run_helper inject --state "$state" --fixture "$fixture" --role request
    [[ "$RUN_STATUS" -ne 0 ]] ||
      fail "inject-remote-type test: hostile $hostile_type destination succeeded"
    [[ "$(command_count voice-step-stage-owned-fixture)" == "1" &&
       "$(command_count STAGE_CAPTURE_FIXTURE)" == "0" &&
       "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "0" ]] ||
      fail "inject-remote-type test: hostile destination reached receiver mutations"
    assert_private_output_absent
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  make_fixture "$fixture"
  export FAKE_ADB_SUBSTITUTE_STAGE_BEFORE_STREAM=1
  run_helper inject --state "$state" --fixture "$fixture" --role request
  [[ "$RUN_STATUS" -ne 0 ]] ||
    fail "inject-prestream-substitution test: substituted destination accepted fixture bytes"
  [[ "$(command_count voice-step-stage-owned-fixture)" == "1" &&
     "$(command_count STAGE_CAPTURE_FIXTURE)" == "0" &&
     "$(command_count TRIGGER_CAPTURE_FIXTURE)" == "0" ]] ||
    fail "inject-prestream-substitution test: substitution reached receiver mutations"
  assert_private_output_absent
  pass
}

run_fixture_bounds_tests() {
  local state="$TMP_DIR/fixture-bounds-state.json"
  local fixture="$TMP_DIR/fixture-bounds.pcm"
  local growth_site="$TMP_DIR/fixture-growth-site"
  local snapshot_path
  local snapshot_status
  local size
  for size in 0 1 16777216 16777217; do
    reset_fake
    activate_fake_run
    write_valid_state "$state"
    : > "$fixture"
    chmod 600 "$fixture"
    truncate -s "$size" "$fixture"
    run_helper inject --state "$state" --fixture "$fixture" --role request
    if [[ "$size" == 1 || "$size" == 16777216 ]]; then
      [[ "$RUN_STATUS" -eq 0 ]] || fail "fixture-bounds test: accepted size $size was rejected"
    else
      [[ "$RUN_STATUS" -ne 0 ]] || fail "fixture-bounds test: invalid size $size was accepted"
      [[ ! -s "$ADB_LOG" ]] || fail "fixture-bounds test: invalid size $size reached fake device"
    fi
    rm -f -- "$state" "$fixture"
    pass
  done

  make_fixture "$fixture"
  mkdir "$growth_site"
  printf '%s\n' \
    'import os' \
    'fixture_path = os.environ["VOICE_STEP_GROW_FIXTURE"]' \
    'real_read = os.read' \
    'grew = False' \
    'def growing_read(descriptor, size):' \
    '    global grew' \
    '    block = real_read(descriptor, size)' \
    '    if block and not grew:' \
    '        grew = True' \
    '        with open(fixture_path, "ab") as handle:' \
    '            handle.write(b"x")' \
    '    return block' \
    'os.read = growing_read' \
    > "$growth_site/sitecustomize.py"
  set +e
  PYTHONPATH="$growth_site" VOICE_STEP_GROW_FIXTURE="$fixture" TMPDIR="$TMP_DIR" \
    bash -s -- "$LIBRARY" "$fixture" <<'BASH'
set -euo pipefail
source "$1"
LOCAL_TEMP_DIR=''
declare -a OWNED_TEMP_FILES=()
snapshot=''
size=''
hash=''
snapshot_fixture "$2" snapshot size hash
BASH
  snapshot_status=$?
  set -e
  [[ "$snapshot_status" -ne 0 ]] ||
    fail "fixture-growth test: post-stat fixture growth was accepted"
  snapshot_path="$(find "$TMP_DIR" -path '*/voice-real-room-step.*/fixture.*.pcm' -type f -print -quit)"
  [[ -n "$snapshot_path" && "$(stat -c %s "$snapshot_path")" == 8 ]] ||
    fail "fixture-growth test: over-limit byte entered private snapshot"
  [[ "$(stat -c %s "$fixture")" == 9 ]] ||
    fail "fixture-growth test: instrumented reader did not grow fixture"
  rm -f -- "$fixture"
  pass
}

run_host_lock_test() {
  local state="$TMP_DIR/host-lock-state.json"
  local first_fixture="$TMP_DIR/host-lock-first.pcm"
  local second_fixture="$TMP_DIR/host-lock-second.pcm"
  local first_log="$TMP_DIR/host-lock-first.argv"
  local second_log="$TMP_DIR/host-lock-second.argv"
  local first_stdout="$TMP_DIR/host-lock-first.stdout"
  local first_stderr="$TMP_DIR/host-lock-first.stderr"
  local second_stdout="$TMP_DIR/host-lock-second.stdout"
  local second_stderr="$TMP_DIR/host-lock-second.stderr"
  local ready="$TMP_DIR/host-lock-entered"
  local release="$TMP_DIR/host-lock-release"
  local first_tmp="$TMP_DIR/host-lock-first-tmp"
  local second_tmp="$TMP_DIR/host-lock-second-tmp"
  local first_override="$TMP_DIR/host-lock-first-override"
  local second_override="$TMP_DIR/host-lock-second-override"
  local first_pid first_status second_status

  reset_fake
  activate_fake_run
  write_valid_state "$state"
  make_fixture "$first_fixture"
  make_second_fixture "$second_fixture"
  : > "$first_log"
  : > "$second_log"
  mkdir "$first_tmp" "$second_tmp" "$first_override" "$second_override"
  chmod 700 "$first_tmp" "$second_tmp"
  chmod 755 "$first_override" "$second_override"
  export FAKE_ADB_BLOCK_MATCH=voice-step-stage-owned-fixture
  export FAKE_ADB_BLOCK_READY="$ready"
  export FAKE_ADB_BLOCK_RELEASE="$release"

  FAKE_ADB_LOG="$first_log" TMPDIR="$first_tmp" VOICE_STEP_LOCK_ROOT="$first_override" "$HELPER" inject \
    --mdev-owner "$MDEV_OWNER" --state "$state" --fixture "$first_fixture" --role request \
    >"$first_stdout" 2>"$first_stderr" &
  first_pid=$!
  if ! wait_for_path "$ready"; then
    kill -TERM "$first_pid" 2>/dev/null || true
    wait "$first_pid" 2>/dev/null || true
    fail "host-lock test: first helper did not enter the controlled ADB boundary"
  fi

  set +e
  FAKE_ADB_LOG="$second_log" "$REAL_TIMEOUT" 2s env TMPDIR="$second_tmp" \
    VOICE_STEP_LOCK_ROOT="$second_override" \
    "$HELPER" inject --mdev-owner "$MDEV_OWNER" --state "$state" --fixture "$second_fixture" --role follow_up \
    >"$second_stdout" 2>"$second_stderr"
  second_status=$?
  set -e
  [[ "$second_status" -ne 0 && "$second_status" -ne 124 ]] ||
    fail "host-lock test: contending helper did not fail promptly"
  [[ ! -s "$second_log" ]] ||
    fail "host-lock test: contending helper reached mdev while the owner/package lock was held"
  [[ "$(stat -c %a "$first_override")" == 755 &&
     "$(stat -c %a "$second_override")" == 755 ]] ||
    fail "host-lock test: caller-selected lock roots were mutated"

  : > "$release"
  set +e
  wait "$first_pid"
  first_status=$?
  set -e
  [[ "$first_status" -eq 0 ]] || fail "host-lock test: lock owner did not complete after release"
  [[ "$(<"$first_stdout")" == $'voice-step.status=ok\nvoice-step.operation=inject\nvoice-step.fixture=accepted' ]] ||
    fail "host-lock test: lock owner success output changed"
  [[ ! -s "$first_stderr" && ! -s "$second_stdout" ]] ||
    fail "host-lock test: concurrent execution leaked uncontrolled output"
  unset FAKE_ADB_BLOCK_MATCH FAKE_ADB_BLOCK_READY FAKE_ADB_BLOCK_RELEASE
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
  run_helper status --state "$state" --expect single_result_announced
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=status\nvoice-step.expectation=single_result_announced\nvoice-step.expectation_met=true'
  [[ "$(command_count .STATUS)" == "1" && "$(command_count 'dumpsys')" == "1" &&
     "$(command_count voice-step-artifact-presence)" == "1" ]] ||
    fail "status-scope test: status retried or used an unrelated query"
  [[ "$(command_count PREPARE)" == "0" && "$(command_count start-foreground-service)" == "0" ]] ||
    fail "status-read-only test: status mutated the run"
  pass

  reset_fake
  rm -f -- "$state"
  activate_fake_run
  write_valid_state "$state"
  export FAKE_ADB_CHECKPOINT_FAILURE=single_delivery_order
  run_helper status --state "$state" --expect single_result_announced
  local predicate_failure_contract=0
  local temp_cleanup_contract=0
  if [[ "$RUN_STATUS" -ne 0 && ! -s "$STDOUT_FILE" &&
        "$(<"$STDERR_FILE")" == 'voice-step.error=checkpoint single_delivery_order not proven' &&
        "$(wc -l < "$STDERR_FILE")" == 1 ]]; then
    predicate_failure_contract=1
  fi
  if [[ -z "$(find "$HELPER_TEMP_ROOT" -mindepth 1 -print -quit)" ]]; then
    temp_cleanup_contract=1
  fi
  (( predicate_failure_contract == 1 && temp_cleanup_contract == 1 )) ||
    fail "status predicate-routing/cleanup test: boundary=$predicate_failure_contract cleanup=$temp_cleanup_contract"
  assert_private_output_absent
  pass

  reset_fake
  local malformed_state="$TMP_DIR/status-malformed-state.json"
  printf '{not-json}\n' > "$malformed_state"
  chmod 600 "$malformed_state"
  run_helper status --state "$malformed_state" --expect single_result_announced
  assert_exact_checkpoint_failure
  [[ ! -s "$ADB_LOG" ]] || fail "status malformed-state test: device access occurred"
  pass

  reset_fake
  rm -f -- "$state"
  activate_fake_run
  write_valid_state "$state"
  export VOICE_STEP_ADB_TIMEOUT_SECONDS=invalid
  run_helper status --state "$state" --expect single_result_announced
  export VOICE_STEP_ADB_TIMEOUT_SECONDS=10
  assert_exact_checkpoint_failure
  [[ ! -s "$ADB_LOG" ]] || fail "status runtime test: device access occurred"
  pass

  reset_fake
  rm -f -- "$state"
  activate_fake_run
  write_valid_state "$state"
  local lock_key
  local lock_directory
  local status_lock_fd
  lock_key="$(python3 - <<'PY'
import hashlib
owner_hash = "sha256:" + hashlib.sha256(b"OWNER_SECRET_123").hexdigest()
print(hashlib.sha256((owner_hash + "\0me.rerere.rikkahub.debug").encode()).hexdigest())
PY
)"
  lock_directory="/tmp/rikkahub-voice-real-room-locks-${EUID}/$lock_key.lock"
  mkdir -p -m 700 -- "$lock_directory"
  exec {status_lock_fd}<"$lock_directory"
  flock -n "$status_lock_fd"
  run_helper status --state "$state" --expect single_result_announced
  flock -u "$status_lock_fd"
  exec {status_lock_fd}<&-
  assert_exact_checkpoint_failure
  [[ ! -s "$ADB_LOG" ]] || fail "status lock test: device access occurred"
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_BAD_SANITIZED=1
  run_helper status --state "$state" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-canonical test: raw session identifier succeeded"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_MISSING_ARTIFACT='voice-experience-private.ndjson'
  run_helper status --state "$state" --expect single_result_announced
  assert_exact_checkpoint_failure
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
  run_helper status --state "$state" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-binding test: mismatched run hash succeeded"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_VALIDATED_FALSE=1
  run_helper status --state "$state" --expect single_result_announced
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-validation test: validated=false succeeded"
  [[ ! -s "$STDOUT_FILE" ]] || fail "status-validation test: failed validation wrote stdout"
  assert_private_output_absent
  pass
}

run_checkpoint_tests() {
  python3 - "$ROOT_DIR" <<'PY' || fail "checkpoint predicate test: named checkpoint contract failed"
import importlib.util
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
module_path = root / "scripts" / "voice-agent-real-room-contract.py"
spec = importlib.util.spec_from_file_location("voice_agent_real_room_contract", module_path)
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


def digest(character):
    return "sha256:" + character * 64


def identity(number):
    return {
        "userTurnId": f"user-{number}",
        "requestHash": digest(str(number)),
        "toolCallId": f"tool-{number}",
        "argumentHash": digest(chr(96 + number)),
        "jobId": f"job-{number}",
        "ownerHash": digest("d"),
        "conversationHash": digest("e"),
        "voiceSessionHash": digest("f"),
        "roomHash": digest("0"),
        "traceHash": digest("9"),
    }


event_number = 0


def voice(kind, **fields):
    global event_number
    event_number += 1
    return {"kind": kind, "eventId": f"event-{event_number}", **fields}


def accepted(job):
    return voice("job_accepted", **job)


def state(kind, job, **fields):
    return voice(kind, **job, **fields)


def delivery(kind, job, **fields):
    return voice(kind, toolCallId=job["toolCallId"], jobId=job["jobId"], **fields)


def automation(monotonic_ms, name, epoch=None, rms=None, wall_clock_ms=None):
    row = {
        "monotonicMs": monotonic_ms,
        "wallClockMs": (
            1_800_000_000_000 + monotonic_ms
            if wall_clock_ms is None
            else wall_clock_ms
        ),
        "name": name,
        "playbackEpoch": epoch,
    }
    if rms is not None:
        row["rmsActive"] = rms
    return row


def canonical_automation(specifications):
    rows = []
    for monotonic_ms, name, epoch, rms in specifications:
        row = dict.fromkeys(contract.AUTOMATION_KEYS)
        row.update({
            "schemaVersion": 1,
            "monotonicMs": monotonic_ms,
            "wallClockMs": 1_800_000_000_000 + monotonic_ms,
            "runHash": digest("a"),
            "comparisonHash": digest("b"),
            "requestedTransport": "livekit_experimental",
            "name": name,
            "playbackEpoch": epoch,
        })
        if name == "playback_written":
            row["byteCount"] = 3200
            row["rmsActive"] = rms
            row["audioWindowMicros"] = 10_000
        rows.append(row)
    content = "".join(
        json.dumps(row, separators=(",", ":")) + "\n"
        for row in rows
    ).encode()
    return contract.parse_automation_bytes(
        content,
        digest("a"),
        digest("b"),
        "livekit_experimental",
    )


def announced_sequence(job, assistant="assistant-1", result=digest("8")):
    return [
        accepted(job),
        state("job_succeeded", job, resultHash=result),
        delivery("delivery_eligible", job),
        delivery("speech_started", job),
        delivery("delivery_started", job),
        voice(
            "transcript",
            turnId=assistant,
            role="assistant",
            interrupted=False,
            groundedJobId=job["jobId"],
            groundedResultHash=result,
        ),
        delivery("delivery_announced", job, assistantTurnId=assistant),
    ]


def expect_pass(name, automation_rows, voice_rows, quiet_ns=2_000_000_000):
    contract.evaluate_checkpoint(contract.Expectation(name), automation_rows, voice_rows, quiet_ns)


def expect_failure(name, automation_rows, voice_rows, boundary, quiet_ns=2_000_000_000):
    try:
        expect_pass(name, automation_rows, voice_rows, quiet_ns)
    except contract.ContractError as error:
        assert error.boundary == boundary, (name, error.boundary, boundary)
    else:
        raise AssertionError(f"{name} accepted its decisive mutation")


expected_values = [
    "single_result_announced",
    "single_follow_up_grounded",
    "parallel_first_pending",
    "parallel_later_completed_first",
    "parallel_both_announced",
    "interruption_delivery_active",
    "interruption_observed",
    "interruption_recovered",
    "isolation_first_active",
    "isolation_two_distinct",
    "isolation_terminal_healthy",
]
assert [value.value for value in contract.Expectation] == expected_values
try:
    contract.Expectation("accepted_count>=1")
except ValueError:
    pass
else:
    raise AssertionError("arbitrary expectation expression was accepted")

valid_job = {
    "version": 1,
    "voiceSessionHash": digest("f"),
    "eventId": "event-parser",
    "kind": "job_accepted",
    "observedAt": "2026-08-04T12:00:00Z",
    "eventHash": digest("1"),
    **identity(1),
    "promptCharacterCount": 1,
}
try:
    contract.parse_voice_bytes(
        (json.dumps(valid_job, sort_keys=True, separators=(",", ":")) + "\n").encode()
    )
except contract.ContractError:
    pass
else:
    raise AssertionError("voice evidence without its trusted session binding was accepted")

binding = {
    "version": 1,
    "voiceSessionHash": digest("f"),
    "eventId": "event-binding",
    "kind": "session_binding",
    "observedAt": "2026-08-04T11:59:59Z",
    "eventHash": digest("2"),
    "ownerHash": digest("d"),
    "conversationHash": digest("e"),
    "roomHash": digest("0"),
    "traceHash": digest("9"),
}
malformed_job = dict(valid_job)
malformed_job["toolCallId"] = "invalid tool identity"
try:
    contract.parse_voice_bytes(
        (
            json.dumps(binding, sort_keys=True, separators=(",", ":"))
            + "\n"
            + json.dumps(malformed_job, sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode()
    )
except contract.ContractError:
    pass
else:
    raise AssertionError("malformed nested job identity was accepted")

malformed_automation = dict.fromkeys(contract.AUTOMATION_KEYS)
malformed_automation.update({
    "schemaVersion": 1,
    "monotonicMs": 1,
    "wallClockMs": 1_800_000_000_001,
    "runHash": digest("a"),
    "comparisonHash": digest("b"),
    "requestedTransport": "livekit_experimental",
    "name": "playback_active",
    "playbackEpoch": 0,
})
try:
    contract.parse_automation_bytes(
        (json.dumps(malformed_automation, separators=(",", ":")) + "\n").encode()
    )
except contract.ContractError:
    pass
else:
    raise AssertionError("non-positive playback epoch was accepted")

first = identity(1)
second = identity(2)
single = announced_sequence(first)
expect_pass("single_result_announced", [], single)
mutated = [dict(row) for row in single]
mutated[1]["jobId"] = "job-crossed"
expect_failure("single_result_announced", [], mutated, "single_succeeded_identity")
mutated = single + [delivery("delivery_announced", second, assistantTurnId="assistant-crossed")]
expect_failure("single_result_announced", [], mutated, "single_announcement")

follow_up = announced_sequence(first)
follow_up.extend([
    voice("transcript", turnId="follow-up-1", role="user", interrupted=False),
    voice(
        "follow_up_correlation",
        followUpTurnId="follow-up-1",
        assistantTurnId="assistant-1",
        resultHash=digest("8"),
    ),
])
expect_pass("single_follow_up_grounded", [], follow_up)
mutated = [dict(row) for row in follow_up]
mutated[-1]["resultHash"] = digest("7")
expect_failure("single_follow_up_grounded", [], mutated, "follow_up_correlation")

pending = [accepted(first), state("job_running", first)]
expect_pass("parallel_first_pending", [], pending)
mutated = pending + [state("job_failed", first)]
expect_failure("parallel_first_pending", [], mutated, "parallel_first_nonterminal")

later_first = [
    accepted(first),
    accepted(second),
    state("job_running", first),
    state("job_succeeded", second, resultHash=digest("7")),
    delivery("delivery_announced", second, assistantTurnId="assistant-2"),
]
expect_pass("parallel_later_completed_first", [], later_first)
mutated = [dict(row) for row in later_first]
mutated[-1]["toolCallId"] = first["toolCallId"]
expect_failure("parallel_later_completed_first", [], mutated, "parallel_delivery_identity")
mutated = [later_first[0], later_first[3], later_first[1], later_first[2], later_first[4]]
expect_failure("parallel_later_completed_first", [], mutated, "parallel_second_order")
mutated = later_first + [delivery("delivery_announced", first, assistantTurnId="assistant-first")]
expect_failure("parallel_later_completed_first", [], mutated, "parallel_first_pending")

both = later_first + [
    state("job_succeeded", first, resultHash=digest("8")),
    delivery("delivery_announced", first, assistantTurnId="assistant-1"),
]
expect_pass("parallel_both_announced", [], both)
mutated = [both[0], both[1], both[2], both[5], both[6], both[3], both[4]]
expect_failure("parallel_both_announced", [], mutated, "parallel_completion_order")
mutated = [both[0], both[3], both[1], both[2], both[4], both[5], both[6]]
expect_failure("parallel_both_announced", [], mutated, "parallel_acceptance_order")

active_voice = [
    accepted(first),
    state("job_succeeded", first, resultHash=digest("8")),
    delivery("delivery_started", first, observedAt="2027-01-15T08:00:00.010Z"),
]
active_automation = [automation(10, "playback_active", 1)]
expect_pass("interruption_delivery_active", active_automation, active_voice)
delivery_one_ns_after_active = [dict(row) for row in active_voice]
delivery_one_ns_after_active[-1]["observedAt"] = "2027-01-15T08:00:00.010000001Z"
expect_failure(
    "interruption_delivery_active",
    active_automation,
    delivery_one_ns_after_active,
    "interruption_active_epoch",
)
expect_failure(
    "interruption_delivery_active",
    [automation(9, "playback_active", 1)],
    active_voice,
    "interruption_active_epoch",
)
for terminal_name in ("playback_stopped", "playback_drained"):
    expect_failure(
        "interruption_delivery_active",
        active_automation + [automation(20, terminal_name, 1)],
        active_voice,
        "interruption_active_epoch",
    )
expect_failure(
    "interruption_delivery_active",
    active_automation + [automation(20, "playback_active", 2)],
    active_voice,
    "interruption_active_epoch",
)
mutated = [dict(row) for row in active_voice]
mutated[-1]["jobId"] = second["jobId"]
expect_failure("interruption_delivery_active", active_automation, mutated, "interruption_delivery_identity")
mutated = active_voice + [delivery("delivery_announced", second, assistantTurnId="assistant-crossed")]
expect_failure("interruption_delivery_active", active_automation, mutated, "interruption_no_announcement")

observed_automation = active_automation + [
    automation(20, "interrupt_started"),
    automation(30, "playback_stopped", 1),
]
expect_pass("interruption_observed", observed_automation, active_voice)
stale_observed_automation = [
    automation(1, "playback_active", 99),
    *observed_automation,
]
expect_pass("interruption_observed", stale_observed_automation, active_voice)
for terminal_name in ("playback_stopped", "playback_drained"):
    prematurely_terminated = active_automation + [
        automation(15, terminal_name, 1),
        automation(20, "interrupt_started"),
        automation(30, "playback_stopped", 1),
    ]
    expect_failure(
        "interruption_observed",
        prematurely_terminated,
        active_voice,
        "interruption_active_epoch",
    )
competing_observed = active_automation + [
    automation(15, "playback_active", 2),
    automation(20, "interrupt_started"),
    automation(30, "playback_stopped", 1),
]
expect_failure(
    "interruption_observed",
    competing_observed,
    active_voice,
    "interruption_active_epoch",
)
mutated_automation = active_automation + [
    automation(20, "interrupt_started"),
    automation(30, "playback_stopped", 2),
]
expect_failure("interruption_observed", mutated_automation, active_voice, "interruption_stopped_epoch")

recovered_voice = active_voice + [
    voice(
        "transcript",
        turnId="assistant-recovered",
        role="assistant",
        interrupted=False,
        groundedJobId=first["jobId"],
        groundedResultHash=digest("8"),
    ),
    delivery("delivery_announced", first, assistantTurnId="assistant-recovered"),
]
recovered_automation = canonical_automation([
    (10, "playback_active", 1, None),
    (20, "playback_written", 1, True),
    (30, "interrupt_started", None, None),
    (40, "playback_stopped", 1, None),
    (100, "playback_written", 1, False),
    (500, "playback_written", 1, False),
    (2200, "playback_active", 2, None),
    (2300, "playback_drained", 2, None),
])
expect_pass("interruption_recovered", recovered_automation, recovered_voice)
recovered_with_stale_active = canonical_automation([
    (1, "playback_active", 99, None),
    (10, "playback_active", 1, None),
    (20, "playback_written", 1, True),
    (30, "interrupt_started", None, None),
    (40, "playback_stopped", 1, None),
    (100, "playback_written", 1, False),
    (500, "playback_written", 1, False),
    (2200, "playback_active", 2, None),
    (2300, "playback_drained", 2, None),
])
expect_pass("interruption_recovered", recovered_with_stale_active, recovered_voice)
quiet_before_interruption = canonical_automation([
    (10, "playback_active", 1, None),
    (20, "playback_written", 1, True),
    (100, "playback_written", 1, False),
    (500, "playback_written", 1, False),
    (700, "interrupt_started", None, None),
    (800, "playback_stopped", 1, None),
    (2700, "playback_active", 2, None),
    (2800, "playback_drained", 2, None),
])
expect_failure(
    "interruption_recovered",
    quiet_before_interruption,
    recovered_voice,
    "recovery_continuous_quiet",
)
reset_automation = canonical_automation([
    (10, "playback_active", 1, None),
    (20, "playback_written", 1, True),
    (30, "interrupt_started", None, None),
    (40, "playback_stopped", 1, None),
    (100, "playback_written", 1, False),
    (500, "playback_written", 1, False),
    (700, "playback_written", 1, True),
    (701, "playback_written", 1, False),
    (2200, "playback_active", 2, None),
    (2300, "playback_drained", 2, None),
])
assert contract.first_quiet_after_last_reset(reset_automation[:-2]) == 701
assert contract.first_quiet_after_last_reset(reset_automation[:-3]) is None
expect_failure("interruption_recovered", reset_automation, recovered_voice, "recovery_continuous_quiet")
recovered_after_reset = canonical_automation([
    (10, "playback_active", 1, None),
    (20, "playback_written", 1, True),
    (30, "interrupt_started", None, None),
    (40, "playback_stopped", 1, None),
    (100, "playback_written", 1, False),
    (500, "playback_written", 1, False),
    (700, "playback_written", 1, True),
    (701, "playback_written", 1, False),
    (2701, "playback_active", 2, None),
    (2800, "playback_drained", 2, None),
])
expect_pass("interruption_recovered", recovered_after_reset, recovered_voice)
mutated_voice = recovered_voice + [
    delivery("delivery_announced", first, assistantTurnId="assistant-other")
]
expect_failure("interruption_recovered", recovered_automation, mutated_voice, "recovery_announcement")
mutated_voice = recovered_voice + [
    delivery("delivery_announced", second, assistantTurnId="assistant-crossed")
]
expect_failure("interruption_recovered", recovered_automation, mutated_voice, "recovery_announcement")

isolation_active = [accepted(first), state("still_working", first)]
expect_pass("isolation_first_active", [], isolation_active)
expect_failure(
    "isolation_first_active",
    [],
    isolation_active + [state("job_canceled", first)],
    "isolation_target_nonterminal",
)

isolated = [accepted(first), accepted(second)]
expect_pass("isolation_two_distinct", [], isolated)
same_request = dict(second)
same_request["requestHash"] = first["requestHash"]
expect_failure(
    "isolation_two_distinct",
    [],
    [accepted(first), accepted(same_request)],
    "isolation_disjoint_identity",
)

terminal_healthy = [
    accepted(first),
    accepted(second),
    state("job_failed", first),
    state("job_succeeded", second, resultHash=digest("7")),
    voice(
        "transcript",
        turnId="assistant-healthy",
        role="assistant",
        interrupted=False,
        groundedJobId=second["jobId"],
        groundedResultHash=digest("7"),
    ),
    delivery("delivery_announced", second, assistantTurnId="assistant-healthy"),
]
expect_pass("isolation_terminal_healthy", [], terminal_healthy)
mutated = terminal_healthy + [delivery("delivery_started", first)]
expect_failure("isolation_terminal_healthy", [], mutated, "isolation_target_no_delivery")
mutated = [terminal_healthy[2], *terminal_healthy[:2], *terminal_healthy[3:]]
expect_failure("isolation_terminal_healthy", [], mutated, "isolation_target_order")
mutated = [
    terminal_healthy[0],
    terminal_healthy[1],
    terminal_healthy[2],
    terminal_healthy[4],
    terminal_healthy[5],
    terminal_healthy[3],
]
expect_failure("isolation_terminal_healthy", [], mutated, "isolation_healthy_order")
PY
  pass

  local state="$TMP_DIR/checkpoint-state.json"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  run_helper status --state "$state"
  [[ "$RUN_STATUS" -ne 0 && ! -s "$ADB_LOG" ]] ||
    fail "checkpoint parser test: missing --expect reached device access"
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  run_helper status --state "$state" --expect 'accepted_count>=1'
  [[ "$RUN_STATUS" -ne 0 && ! -s "$ADB_LOG" ]] ||
    fail "checkpoint parser test: unknown --expect reached device access"
  pass
}

run_finalize_tests() {
  local state="$TMP_DIR/finalize-state.json"
  local finalization="$TMP_DIR/finalization.json"
  python3 - "$ROOT_DIR" <<'PY' || fail "finalization-contract test: exact schema matrix failed"
import importlib.util
import sys
from pathlib import Path

module_path = Path(sys.argv[1]) / "scripts" / "voice-agent-real-room-contract.py"
spec = importlib.util.spec_from_file_location("voice_agent_real_room_contract", module_path)
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)

valid = [
    ({"schemaVersion": 1, "outcome": "complete", "reason": "complete", "callStopped": True,
      "automationFinalized": True, "forcedFallbackUsed": False}, "complete"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "bound_call_rejected",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": False}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "call_stop_failed",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": False}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "call_stop_timeout",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": False}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "persistence_drain_failed",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": False}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "automation_finalize_rejected",
      "callStopped": True, "automationFinalized": False, "forcedFallbackUsed": False}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "automation_finalize_failed",
      "callStopped": True, "automationFinalized": False, "forcedFallbackUsed": False}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "product_failure", "reason": "forced_fallback_used",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": True}, "product_failure"),
    ({"schemaVersion": 1, "outcome": "infrastructure_interruption", "reason": "device_unavailable",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": False}, "infrastructure_interruption"),
    ({"schemaVersion": 1, "outcome": "infrastructure_interruption", "reason": "adb_route_unavailable",
      "callStopped": False, "automationFinalized": False, "forcedFallbackUsed": False}, "infrastructure_interruption"),
]
for value, outcome in valid:
    assert contract.validate_finalization(value)["outcome"] == outcome
    assert contract.canonical_json_bytes(value) == __import__("json").dumps(
        value, sort_keys=True, separators=(",", ":")
    ).encode()
    for boolean in ("callStopped", "automationFinalized"):
        mutation = {**value, boolean: not value[boolean]}
        try:
            contract.validate_finalization(mutation)
        except contract.ContractError:
            pass
        else:
            raise AssertionError(
                f"reason/terminal tuple mutation accepted: {mutation}"
            )

invalid = [
    {**valid[0][0], "unknown": True},
    {**valid[0][0], "forcedFallbackUsed": True},
    {**valid[0][0], "callStopped": False},
    {**valid[1][0], "automationFinalized": True},
    {**valid[7][0], "forcedFallbackUsed": False},
    {**valid[8][0], "reason": "bound_call_rejected"},
]
for value in invalid:
    try:
        contract.validate_finalization(value)
    except contract.ContractError:
        pass
    else:
        raise AssertionError(f"contradictory finalization accepted: {value}")

complete = valid[0][0]
cleanup = {
    "schemaVersion": 2,
    "outcome": "complete",
    "callStopped": True,
    "automationFinalized": True,
    "fixturesRemoved": True,
    "finalizationHash": contract.sha256_bytes(contract.canonical_json_bytes(complete)),
}
assert contract.validate_cleanup(cleanup, complete) == cleanup
for mutation in (
    {**cleanup, "outcome": "product_failure"},
    {**cleanup, "outcome": ["complete"]},
    {**cleanup, "callStopped": False},
    {**cleanup, "automationFinalized": False},
    {**cleanup, "finalizationHash": "sha256:" + "0" * 64},
):
    try:
        contract.validate_cleanup(mutation, complete)
    except contract.ContractError:
        pass
    else:
        raise AssertionError(f"cleanup promoted or unlinked finalization: {mutation}")

standalone_cleanup = [
    {**cleanup, "finalizationHash": "sha256:" + "1" * 64},
    {**cleanup, "outcome": "product_failure", "callStopped": False,
     "automationFinalized": False, "fixturesRemoved": False,
     "finalizationHash": "sha256:" + "2" * 64},
    {**cleanup, "outcome": "product_failure", "callStopped": True,
     "automationFinalized": False, "fixturesRemoved": True,
     "finalizationHash": "sha256:" + "3" * 64},
    {**cleanup, "outcome": "infrastructure_interruption", "callStopped": False,
     "automationFinalized": False, "fixturesRemoved": False,
     "finalizationHash": "sha256:" + "4" * 64},
]
for value in standalone_cleanup:
    assert contract.validate_cleanup(value) == value
for mutation in (
    {**standalone_cleanup[0], "fixturesRemoved": False},
    {**standalone_cleanup[1], "automationFinalized": True, "callStopped": True},
    {**standalone_cleanup[3], "callStopped": True},
    {**standalone_cleanup[3], "automationFinalized": True, "callStopped": True},
):
    try:
        contract.validate_cleanup(mutation)
    except contract.ContractError:
        pass
    else:
        raise AssertionError(f"standalone cleanup invariant mutation accepted: {mutation}")
PY
  pass

  reset_fake
  activate_fake_run
  write_valid_state "$state"
  run_helper finalize --state "$state"
  [[ "$RUN_STATUS" -ne 0 && ! -s "$ADB_LOG" ]] ||
    fail "finalize-parser test: missing finalization destination reached device access"
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_DURABLE_STOP_VISIBLE_AFTER=2
  run_helper finalize --state "$state" --finalization-output "$finalization"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.outcome=complete'
  assert_finalization_record "$finalization" complete complete true true false
  command_sequence_present END_BOUND automation-events.jsonl FINALIZE_BOUND STATUS automation-events.jsonl ||
    fail "finalize-order test: bound end, durable stop, finalize, status, and durable final proof changed"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END)" == "0" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE)" == "0" &&
     "$(command_count STATUS)" == "1" ]] ||
    fail "finalize-retry test: terminal bound mutations or finalized status were retried"
  assert_bound_action_extras 'me.rerere.rikkahub.voiceagent.action.END_BOUND'
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.END_BOUND'
  python3 - "$ADB_LOG" <<'PY' || fail "finalize-binding/order test: exact binding or active-run STATUS exclusion failed"
import sys
data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
end = next(index for index, command in enumerate(commands) if b"me.rerere.rikkahub.voiceagent.action.END_BOUND" in command)
finalize = next(index for index, command in enumerate(commands) if b"me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND" in command)
statuses = [index for index, command in enumerate(commands) if any(value.endswith(b".STATUS") for value in command)]
artifact_reads = [
    index for index, command in enumerate(commands)
    if b"exec-out" in command and b"cat" in command and
    any(value.endswith(b"automation-events.jsonl") for value in command)
]
assert end < finalize
assert len([index for index in artifact_reads if end < index < finalize]) >= 2
assert statuses and all(index > finalize for index in statuses)
assert any(index > statuses[-1] for index in artifact_reads)
command = commands[finalize]
for key, value in {
    b"run_hash": b"sha256:" + b"a" * 64,
    b"comparison_hash": b"sha256:" + b"b" * 64,
    b"transport": b"livekit_experimental",
}.items():
    index = command.index(key)
    assert command[index - 1] == b"--es" and command[index + 1] == value
PY
  python3 - "$FAKE_STATE" <<'PY' || fail "finalize-durable test: exact terminal events were not observed"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["call_stopped_recorded"] is True
assert state["run_finalized_recorded"] is True
assert state["automation_artifact_reads"] >= 3
PY
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_CAPTURE_CRLF=automation
  run_helper finalize --state "$state" --finalization-output "$finalization"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
    fail "finalize-CRLF test: CR-bearing terminal evidence published a record"
  assert_private_output_absent
  pass

  local case_name outcome reason call_stopped automation_finalized fallback
  for case_name in \
    bound-rejected stop-timeout call-stop-failed persistence-drain \
    automation-failed missing-final-event forced-fallback device-loss route-loss; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization"
    write_valid_state "$state"
    case "$case_name" in
      bound-rejected)
        export FAKE_ADB_FAIL_END=1 FAKE_ADB_FAIL_FORCE_STOP=1
        outcome=product_failure reason=bound_call_rejected
        call_stopped=false automation_finalized=false fallback=false
        ;;
      stop-timeout)
        export FAKE_ADB_SERVICE_STAYS_ACTIVE=1 FAKE_ADB_FAIL_FORCE_STOP=1
        outcome=product_failure reason=call_stop_timeout
        call_stopped=false automation_finalized=false fallback=false
        ;;
      call-stop-failed)
        export FAKE_ADB_CALL_STOP_FAILED=1 FAKE_ADB_FAIL_FORCE_STOP=1
        outcome=product_failure reason=call_stop_failed
        call_stopped=false automation_finalized=false fallback=false
        ;;
      persistence-drain)
        export FAKE_ADB_MISSING_ARTIFACT=automation-events.jsonl
        export FAKE_ADB_FAIL_FORCE_STOP=1
        outcome=product_failure reason=persistence_drain_failed
        call_stopped=false automation_finalized=false fallback=false
        ;;
      automation-failed)
        export FAKE_ADB_FAIL_FINALIZE=1
        outcome=product_failure reason=automation_finalize_failed
        call_stopped=true automation_finalized=false fallback=false
        ;;
      missing-final-event)
        export FAKE_ADB_MALFORMED_DURABLE_ENDING=missing-run-finalized
        outcome=product_failure reason=automation_finalize_failed
        call_stopped=true automation_finalized=false fallback=false
        ;;
      forced-fallback)
        export FAKE_ADB_SERVICE_STAYS_ACTIVE=1
        outcome=product_failure reason=forced_fallback_used
        call_stopped=false automation_finalized=false fallback=true
        ;;
      device-loss)
        export FAKE_ADB_FAIL_END=1 FAKE_ADB_DEVICE_LOST=1
        outcome=infrastructure_interruption reason=device_unavailable
        call_stopped=false automation_finalized=false fallback=false
        ;;
      route-loss)
        export FAKE_ADB_FAIL_END=1 FAKE_ADB_FAIL_REACHABILITY_PROBE=1
        outcome=infrastructure_interruption reason=adb_route_unavailable
        call_stopped=false automation_finalized=false fallback=false
        ;;
    esac
    run_helper finalize --state "$state" --finalization-output "$finalization"
    assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\n'"voice-step.outcome=$outcome"
    assert_finalization_record "$finalization" "$outcome" "$reason" \
      "$call_stopped" "$automation_finalized" "$fallback"
    assert_private_output_absent
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_REJECT_FINALIZE=1
  run_helper finalize --state "$state" --finalization-output "$finalization"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.outcome=product_failure'
  assert_finalization_record "$finalization" product_failure automation_finalize_rejected true false false
  assert_private_output_absent
  pass

  local finalize_reply_case expected_finalize_reason
  for finalize_reply_case in \
    rejected-call-not-stopped rejected-binding-mismatch \
    error-invalid-request error-invalid-state error-runtime-failure; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization"
    write_valid_state "$state"
    case "$finalize_reply_case" in
      rejected-call-not-stopped)
        export FAKE_ADB_FINALIZE_NONZERO_DATA=$'status=rejected\nreason=call_not_stopped'
        expected_finalize_reason=automation_finalize_rejected
        ;;
      rejected-binding-mismatch)
        export FAKE_ADB_FINALIZE_NONZERO_DATA=$'status=rejected\nreason=binding_mismatch'
        expected_finalize_reason=automation_finalize_rejected
        ;;
      error-invalid-request)
        export FAKE_ADB_FINALIZE_NONZERO_DATA=$'status=error\nerror=invalid_request'
        expected_finalize_reason=automation_finalize_failed
        ;;
      error-invalid-state)
        export FAKE_ADB_FINALIZE_NONZERO_DATA=$'status=error\nerror=invalid_state'
        expected_finalize_reason=automation_finalize_failed
        ;;
      error-runtime-failure)
        export FAKE_ADB_FINALIZE_NONZERO_DATA=$'status=error\nerror=runtime_failure'
        expected_finalize_reason=automation_finalize_failed
        ;;
    esac
    run_helper finalize --state "$state" --finalization-output "$finalization"
    assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.outcome=product_failure'
    assert_finalization_record "$finalization" product_failure \
      "$expected_finalize_reason" true false false
    pass
  done

  local malformed_finalize_reply
  for malformed_finalize_reply in \
    $'status=rejected\nreason=unknown' \
    $'status=error\nerror=unknown' \
    $'status=rejected\nreason=call_not_stopped\nextra=true' \
    $'status=rejected\nreason=call_not_stopped\n' \
    $'status=error\nerror=runtime_failure\n' \
    $'status=rejected\nreason=call_not_stopped\n\n' \
    $'status=ok\naction=finalize'; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization"
    write_valid_state "$state"
    export FAKE_ADB_FINALIZE_NONZERO_DATA="$malformed_finalize_reply"
    run_helper finalize --state "$state" --finalization-output "$finalization"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
      fail "finalize-resultData test: malformed nonzero reply published a record"
    assert_private_output_absent
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_FINALIZE_SUCCESS_DATA=$'status=ok\naction=finalize\n'
  run_helper finalize --state "$state" --finalization-output "$finalization"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
    fail "finalize-resultData test: trailing-LF success reply published a record"
  assert_private_output_absent
  pass

  local raw_broadcast_fixture="$TMP_DIR/raw-broadcast-output"
  local raw_broadcast_case raw_broadcast_action
  for raw_broadcast_case in \
    canonical-status nul-success nul-rejection nul-error nul-status \
    trailing-lf-success cr-success; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization" "$raw_broadcast_fixture"
    write_valid_state "$state"
    write_raw_broadcast_fixture "$raw_broadcast_fixture" "$raw_broadcast_case"
    raw_broadcast_action=FINALIZE_BOUND
    case "$raw_broadcast_case" in
      canonical-status|nul-status) raw_broadcast_action=STATUS ;;
    esac
    export FAKE_ADB_RAW_BROADCAST_FILE="$raw_broadcast_fixture"
    export FAKE_ADB_RAW_BROADCAST_ACTION="$raw_broadcast_action"
    run_helper finalize --state "$state" --finalization-output "$finalization"
    if [[ "$raw_broadcast_case" == canonical-status ]]; then
      assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.outcome=complete'
      assert_finalization_record "$finalization" complete complete true true false
    else
      [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
        fail "finalize-raw-broadcast test: $raw_broadcast_case published a record"
    fi
    assert_private_output_absent "$raw_broadcast_fixture"
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_POST_FINALIZE_STATUS=active
  run_helper finalize --state "$state" --finalization-output "$finalization"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.outcome=product_failure'
  assert_finalization_record "$finalization" product_failure \
    automation_finalize_failed true false false
  pass

  local unclassifiable_post_finalize_status
  for unclassifiable_post_finalize_status in idle wrong-binding malformed; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization"
    write_valid_state "$state"
    export FAKE_ADB_POST_FINALIZE_STATUS="$unclassifiable_post_finalize_status"
    run_helper finalize --state "$state" --finalization-output "$finalization"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
      fail "finalize-post-accept status test: $unclassifiable_post_finalize_status published"
    assert_private_output_absent
    pass
  done

  local fallback_failure expected_reason expected_fallback
  for fallback_failure in device-loss malformed-quiescence restoration-failure; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization"
    write_valid_state "$state"
    export FAKE_ADB_FAIL_END=1
    case "$fallback_failure" in
      device-loss)
        export FAKE_ADB_DEVICE_LOST_ON_FORCE_STOP=1
        expected_reason=bound_call_rejected expected_fallback=false
        ;;
      malformed-quiescence)
        export FAKE_ADB_MALFORMED_QUIESCENCE_AFTER_FORCE_STOP=ps-header
        expected_reason=forced_fallback_used expected_fallback=true
        ;;
      restoration-failure)
        export FAKE_ADB_FAIL_RESTORATION=1
        expected_reason=forced_fallback_used expected_fallback=true
        ;;
    esac
    run_helper finalize --state "$state" --finalization-output "$finalization"
    [[ -f "$finalization" ]] ||
      fail "finalize-monotonic-product test: $fallback_failure suppressed the record"
    assert_finalization_record "$finalization" product_failure "$expected_reason" \
      false false "$expected_fallback"
    assert_private_output_absent
    pass
  done

  local post_finalize_loss expected_infrastructure_reason
  for post_finalize_loss in device route; do
    reset_fake
    activate_fake_run
    rm -f -- "$state" "$finalization"
    write_valid_state "$state"
    if [[ "$post_finalize_loss" == device ]]; then
      export FAKE_ADB_DEVICE_LOST_AFTER_FINALIZE=1
      expected_infrastructure_reason=device_unavailable
    else
      export FAKE_ADB_ROUTE_LOST_AFTER_FINALIZE=1
      expected_infrastructure_reason=adb_route_unavailable
    fi
    run_helper finalize --state "$state" --finalization-output "$finalization"
    assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.outcome=infrastructure_interruption'
    assert_finalization_record "$finalization" infrastructure_interruption \
      "$expected_infrastructure_reason" false false false
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_MALFORMED_AFTER_FINALIZE=1
  run_helper finalize --state "$state" --finalization-output "$finalization"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
    fail "finalize-post-accept classification test: malformed evidence published"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_ADB_FAIL_END=1 FAKE_ADB_MALFORMED_DEVICE_ENUMERATION=1
  run_helper finalize --state "$state" --finalization-output "$finalization"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$finalization" ]] ||
    fail "finalize-classification test: malformed independent evidence published a record"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state" "$finalization"
  write_valid_state "$state"
  export FAKE_LN_RACE_DESTINATION="$finalization"
  run_helper finalize --state "$state" --finalization-output "$finalization"
  [[ "$RUN_STATUS" -ne 0 && "$(<"$finalization")" == raced ]] ||
    fail "finalize-race test: absent-only publication overwrote a raced destination"
  assert_private_output_absent
  pass
}

run_capture_tests() {
  local state="$TMP_DIR/capture-state.json"
  local finalization="$TMP_DIR/capture-finalization.json"
  local output_dir="$TMP_DIR/capture-output"
  local automation="$output_dir/automation.jsonl"
  local private="$output_dir/private.ndjson"
  local sanitized="$output_dir/sanitized.ndjson"
  mkdir "$output_dir"

  reset_fake
  export FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT=1
  finalize_fake_run false
  write_valid_state "$state"
  write_finalization "$finalization"
  run_helper capture --state "$state" --finalization "$finalization" \
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
import shlex
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
bundles = [command for command in commands if any(b"voice-step-capture-bundle" in value for value in command)]
assert len(bundles) == 1
tail = bundles[0][7:]
assert len(tail) == 2 and tail[0] == b"exec-out"
decoded = shlex.split(tail[1].decode(), posix=True)
assert decoded[0:6] == [
    "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh", "-c",
]
assert "voice-step-capture-bundle" in decoded[6]
assert decoded[7] == "sh"
assert [path.rsplit("/", 1)[-1] for path in decoded[8:]] == [
    "automation-events.jsonl",
    "voice-experience-private.ndjson",
    "voice-experience-events.ndjson",
]
bundle_script = decoded[6].encode()
assert b"/proc/self/fd/" not in bundle_script
for descriptor in (3, 4, 5):
    assert f"/proc/$$/fd/{descriptor}".encode() in bundle_script
automation = open(sys.argv[2], "rb").read()
private = open(sys.argv[3], "rb").read()
sanitized = open(sys.argv[4], "rb").read()
assert automation.endswith(b"\n") and b'"name":"run_prepared"' in automation
assert b'"voiceSessionId":"PRIVATE_TRACE"' in private
assert b'"prompt":"PROMPT_SECRET"' in private
assert sanitized.endswith(b"\n") and b"voiceSessionId" not in sanitized
assert b"PRIVATE_TRACE" not in sanitized and b"PROMPT_SECRET" not in sanitized
PY
  then
    fail "capture-bundle test: sources were not captured by one descriptor-bound snapshot"
  fi
  assert_no_capture_temps "$output_dir"
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 && ! -s "$ADB_LOG" ]] ||
    fail "capture-parser test: missing finalization reached device access"
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  write_finalization "$finalization" complete complete false true false
  run_helper capture --state "$state" --finalization "$finalization" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 && ! -s "$ADB_LOG" ]] ||
    fail "capture-finalization test: contradictory record reached device access"
  pass

  local corruption
  for corruption in \
    private-reorder private-duplicate private-orphan \
    sanitized-reorder sanitized-duplicate sanitized-orphan \
    event-id kind timestamp private-hash forbidden-field; do
    rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    write_finalization "$finalization"
    export FAKE_ADB_CAPTURE_CORRUPTION="$corruption"
    run_helper capture --state "$state" --finalization "$finalization" \
      --automation-output "$automation" \
      --private-voice-output "$private" \
      --sanitized-voice-output "$sanitized"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
      fail "capture-correspondence test: $corruption published a destination"
    assert_no_capture_temps "$output_dir"
    assert_private_output_absent
    pass
  done

  local crlf_source
  for crlf_source in automation private sanitized; do
    rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    write_finalization "$finalization"
    export FAKE_ADB_CAPTURE_CRLF="$crlf_source"
    run_helper capture --state "$state" --finalization "$finalization" \
      --automation-output "$automation" \
      --private-voice-output "$private" \
      --sanitized-voice-output "$sanitized"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
      fail "capture-CRLF test: $crlf_source CR byte reached publication"
    assert_no_capture_temps "$output_dir"
    pass
  done

  local race_mode source_number
  for race_mode in mutate replace; do
    for source_number in 1 2 3; do
      rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
      reset_fake
      finalize_fake_run false
      write_valid_state "$state"
      write_finalization "$finalization"
      if [[ "$race_mode" == mutate ]]; then
        export FAKE_ADB_MUTATE_CAPTURE_SOURCE_AFTER_READ="$source_number"
      else
        export FAKE_ADB_REPLACE_CAPTURE_SOURCE_AFTER_READ="$source_number"
      fi
      run_helper capture --state "$state" --finalization "$finalization" \
        --automation-output "$automation" \
        --private-voice-output "$private" \
        --sanitized-voice-output "$sanitized"
      [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
        fail "capture-source-race test: $race_mode of source $source_number published a destination"
      assert_no_capture_temps "$output_dir"
      pass
    done
  done


  for source_number in 1 2 3; do
    rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    write_finalization "$finalization"
    export FAKE_ADB_PREOPEN_REPLACE_CAPTURE_SOURCE="$source_number"
    run_helper capture --state "$state" --finalization "$finalization" \
      --automation-output "$automation" \
      --private-voice-output "$private" \
      --sanitized-voice-output "$sanitized"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
      fail "capture-preopen-race test: source $source_number replacement was published"
    assert_no_capture_temps "$output_dir"
    pass
  done

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  write_finalization "$finalization"
  export FAKE_ADB_MALFORMED_DURABLE_ENDING=event-after-finalized
  run_helper capture --state "$state" --finalization "$finalization" \
    --automation-output "$automation" --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-terminal-order test: event after finalization was published"
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  run_helper capture --state "$state" --finalization "$finalization" \
    --automation-output "$automation" --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=capture\nvoice-step.artifacts=published'
  [[ "$(command_count force-stop)" == 1 &&
     "$(exact_command_count -s DEVICE_SECRET_123 exec-out ps -A -n -o UID,PID,PPID,STAT,NAME)" == 2 &&
     "$(exact_command_count -s DEVICE_SECRET_123 shell cmd activity get-isolated-pids "$CURRENT_UID")" == 2 ]] ||
    fail "capture-dirty-quiescence test: forced product capture lacked independent quiescence"
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  activate_fake_run
  record_fake_call_stop
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  export FAKE_ADB_CALL_STOP_FAILED=1
  run_helper capture --state "$state" --finalization "$finalization" \
    --automation-output "$automation" --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=capture\nvoice-step.artifacts=published'
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  activate_fake_run
  record_fake_call_stop
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  run_helper capture --state "$state" --finalization "$finalization" \
    --automation-output "$automation" --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-successful-stop-fallback test: contradictory evidence was published"
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  export FAKE_ADB_PACKAGE_PROCESS=1
  run_helper capture --state "$state" --finalization "$finalization" \
    --automation-output "$automation" --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-dirty-quiescence test: unproven quiescence published a bundle"
  pass

  local raced_destination
  for raced_destination in "$automation" "$private" "$sanitized"; do
    rm -f -- "$automation" "$private" "$sanitized" "$state" "$finalization"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    write_finalization "$finalization"
    export FAKE_LN_RACE_DESTINATION="$raced_destination"
    run_helper capture --state "$state" --finalization "$finalization" \
      --automation-output "$automation" --private-voice-output "$private" \
      --sanitized-voice-output "$sanitized"
    [[ "$RUN_STATUS" -ne 0 && "$(<"$raced_destination")" == raced ]] ||
      fail "capture-destination-race test: raced destination was overwritten"
    case "$raced_destination" in
      "$automation") [[ ! -e "$private" && ! -e "$sanitized" ]] ;;
      "$private") [[ -s "$automation" && ! -e "$sanitized" ]] ;;
      "$sanitized") [[ -s "$automation" && -s "$private" ]] ;;
    esac || fail "capture-destination-race test: publication continued past a race"
    assert_no_capture_temps "$output_dir"
    pass
  done
}

run_end_tests() {
  local state="$TMP_DIR/end-state.json"
  local finalization="$TMP_DIR/end-finalization.json"
  local cleanup_output="$TMP_DIR/end-cleanup.json"
  python3 - "$HELPER" <<'PY' || fail "exit-signal test: cleanup deferral was installed after EXIT removal"
import sys

source = open(sys.argv[1], encoding="utf-8").read()
body = source.split("on_exit() {", 1)[1].split("\n}", 1)[0]
assert body.index("trap defer_exit_cleanup_signal HUP INT TERM") < body.index("trap - EXIT")
PY
  pass

  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -s "$ADB_LOG" ]] ||
    fail "end-parser test: missing finalization reached device access"
  pass

  local infrastructure_reason terminal_prefix
  for infrastructure_reason in device_unavailable adb_route_unavailable; do
    for terminal_prefix in none stopped finalized; do
      rm -f -- "$cleanup_output" "$state" "$finalization"
      reset_fake
      case "$terminal_prefix" in
        none)
          activate_fake_run
          ;;
        stopped)
          activate_fake_run
          record_fake_call_stop
          ;;
        finalized)
          finalize_fake_run false
          ;;
      esac
      write_valid_state "$state"
      write_finalization "$finalization" infrastructure_interruption \
        "$infrastructure_reason" false false false
      run_helper end --state "$state" --finalization "$finalization" \
        --cleanup-output "$cleanup_output"
      assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=infrastructure_interruption'
      if ! python3 - "$cleanup_output" <<'PY'
import json
import sys

cleanup = json.load(open(sys.argv[1], encoding="utf-8"))
assert cleanup["outcome"] == "infrastructure_interruption"
assert cleanup["callStopped"] is False
assert cleanup["automationFinalized"] is False
assert cleanup["fixturesRemoved"] is True
PY
      then
        fail "end-infrastructure-prefix test: cleanup changed the false/false tuple"
      fi
      pass
    done
  done

  local contradictory_infrastructure_prefix
  for contradictory_infrastructure_prefix in failed-stop finalized-without-stop event-after-finalized; do
    rm -f -- "$cleanup_output" "$state" "$finalization"
    reset_fake
    case "$contradictory_infrastructure_prefix" in
      failed-stop)
        activate_fake_run
        record_fake_call_stop
        export FAKE_ADB_CALL_STOP_FAILED=1
        ;;
      finalized-without-stop)
        finalize_fake_run false
        export FAKE_ADB_MALFORMED_DURABLE_ENDING=missing-call-stopped
        ;;
      event-after-finalized)
        finalize_fake_run false
        export FAKE_ADB_MALFORMED_DURABLE_ENDING=event-after-finalized
        ;;
    esac
    write_valid_state "$state"
    write_finalization "$finalization" infrastructure_interruption \
      device_unavailable false false false
    run_helper end --state "$state" --finalization "$finalization" \
      --cleanup-output "$cleanup_output"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
      fail "end-infrastructure-prefix test: $contradictory_infrastructure_prefix published"
    assert_private_output_absent
    pass
  done

  reset_fake
  finalize_fake_run true
  rm -f -- "$state" "$finalization" "$cleanup_output"
  write_valid_state "$state"
  write_finalization "$finalization"
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=complete'
  if ! python3 - "$cleanup_output" "$finalization" <<'PY'
import hashlib
import json
import os
import stat
import sys

cleanup_path, finalization_path = sys.argv[1:]
finalization = open(finalization_path, "rb").read()
expected = {
    "schemaVersion": 2,
    "outcome": "complete",
    "callStopped": True,
    "automationFinalized": True,
    "fixturesRemoved": True,
    "finalizationHash": "sha256:" + hashlib.sha256(finalization).hexdigest(),
}
actual = open(cleanup_path, "rb").read()
assert actual == json.dumps(expected, sort_keys=True, separators=(",", ":")).encode()
metadata = os.lstat(cleanup_path)
assert stat.S_ISREG(metadata.st_mode) and stat.S_IMODE(metadata.st_mode) == 0o600
assert metadata.st_nlink == 1
PY
  then
    fail "end-record test: complete cleanup was not canonically linked"
  fi
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == 0 &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END)" == 0 &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == 0 &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE)" == 0 ]] ||
    fail "end-scope test: teardown issued a call-end or finalize mutation"
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell cmd activity force-stop --user 0 me.rerere.rikkahub.debug)" == 1 &&
     "$(exact_command_count -s DEVICE_SECRET_123 exec-out ps -A -n -o UID,PID,PPID,STAT,NAME)" == 2 &&
     "$(exact_command_count -s DEVICE_SECRET_123 shell cmd activity get-isolated-pids "$CURRENT_UID")" == 2 &&
     "$(command_count voice-step-cleanup-broker)" == 1 &&
     "$(exact_command_count -s DEVICE_SECRET_123 shell am broadcast --user 0 --include-stopped-packages -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver -a me.rerere.rikkahub.voiceagent.automation.STATUS)" == 1 ]] ||
    fail "end-command-count test: exact force-stop, stable quiescence, broker, or restoration count changed"
  python3 - "$ADB_LOG" "$FAKE_STATE" <<'PY' || fail "end-order/receipt test: teardown ordering or schema-v2 ownership receipt changed"
import json
import shlex
import sys

data = open(sys.argv[1], "rb").read()
state = json.load(open(sys.argv[2], encoding="utf-8"))
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
force_stop = next(i for i, command in enumerate(commands) if b"force-stop" in command)
processes = [i for i, command in enumerate(commands) if command[-5:] == [b"ps", b"-A", b"-n", b"-o", b"UID,PID,PPID,STAT,NAME"]]
isolated = [i for i, command in enumerate(commands) if b"get-isolated-pids" in command]
brokers = [
    (i, command) for i, command in enumerate(commands)
    if any(b"voice-step-cleanup-broker" in value for value in command)
]
restorations = [
    i for i, command in enumerate(commands)
    if b"--include-stopped-packages" in command
]
artifact_reads = [
    i for i, command in enumerate(commands)
    if b"exec-out" in command and b"cat" in command and
    any(value.endswith(b"automation-events.jsonl") for value in command)
]
assert len(processes) == len(isolated) == 2
assert len(brokers) == len(restorations) == 1
broker_index, broker = brokers[0]
assert artifact_reads[0] < force_stop < processes[0] < isolated[0] < processes[1] < isolated[1] < broker_index
assert any(broker_index < index < restorations[0] for index in artifact_reads)
tail = broker[7:]
assert len(tail) == 2 and tail[0] == b"shell"
decoded = shlex.split(tail[1].decode(), posix=True)
assert decoded[0:6] == [
    "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh", "-c",
]
assert "voice-step-cleanup-broker" in decoded[6]
assert decoded[7] == "sh" and decoded[8:] == [
    "files/voice-real-room/" + "a" * 64,
    state["fixture_parent_identity"],
    state["fixture_directory_identity"],
    "0123456789abcdef0123456789abcdef",
    str(state["package_uid"]),
]
script = next(value for value in broker if b"voice-step-cleanup-broker" in value)
assert b"/proc/self/fd/" not in script
assert b"/proc/$$/fd/4" in script
assert b"stat -Lc %h /proc/$$/fd/4" in script
assert b'rm -rf -- "$directory"' not in script
PY
  python3 - "$FAKE_STATE" <<'PY' || fail "end-state test: cleanup/restoration proof was incomplete"
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["automation_state"] == "idle"
assert state["package_stopped"] is False
assert state["process_readbacks"] == 2
assert state["isolated_readbacks"] == 2
assert state["cleanup_broker_completed"] is True
assert state["cleanup_broker_exact_shell_executed"] is True
assert state["rmdir_boundary_observed"] is True
assert state["restoration_count"] == 1
assert state["remote_directory"] is None
PY
  [[ ! -e "$REMOTE_APP_DATA_ROOT/files/voice-real-room/$(printf 'a%.0s' {1..64})" ]] ||
    fail "end-broker-execution test: exact broker shell left the real app-data directory"
  pass

  rm -f -- "$cleanup_output" "$state" "$finalization"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  if ! python3 - "$cleanup_output" "$finalization" <<'PY'
import hashlib, json, sys
finalization = open(sys.argv[2], "rb").read()
expected = {
    "automationFinalized": False,
    "callStopped": False,
    "finalizationHash": "sha256:" + hashlib.sha256(finalization).hexdigest(),
    "fixturesRemoved": True,
    "outcome": "product_failure",
    "schemaVersion": 2,
}
assert open(sys.argv[1], "rb").read() == json.dumps(
    expected, sort_keys=True, separators=(",", ":")
).encode()
PY
  then
    fail "end-dirty-linkage test: cleanup promoted forced-fallback finalization"
  fi
  pass

  rm -f -- "$cleanup_output" "$state" "$finalization"
  reset_fake
  activate_fake_run
  record_fake_call_stop
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-successful-stop-fallback test: contradictory cleanup was published"
  pass

  rm -f -- "$cleanup_output" "$state" "$finalization"
  reset_fake
  activate_fake_run
  record_fake_call_stop
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  export FAKE_ADB_CALL_STOP_FAILED=1
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  if ! python3 - "$cleanup_output" <<'PY'
import json
import sys

cleanup = json.load(open(sys.argv[1], encoding="utf-8"))
assert cleanup["outcome"] == "product_failure"
assert cleanup["callStopped"] is False
assert cleanup["automationFinalized"] is False
assert cleanup["fixturesRemoved"] is True
PY
  then
    fail "end-failed-stop-fallback test: cleanup changed the dirty terminal tuple"
  fi
  pass

  rm -f -- "$cleanup_output" "$state" "$finalization"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  write_finalization "$finalization" product_failure forced_fallback_used false false true
  export FAKE_ADB_RETAIN_FIXTURE_DIR=1
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  if ! python3 - "$cleanup_output" <<'PY'
import json, sys
cleanup = json.load(open(sys.argv[1], encoding="utf-8"))
assert cleanup["outcome"] == "product_failure"
assert cleanup["callStopped"] is False
assert cleanup["automationFinalized"] is False
assert cleanup["fixturesRemoved"] is False
PY
  then
    fail "end-retained-directory test: dirty cleanup record changed terminal proof"
  fi
  python3 - "$FAKE_STATE" <<'PY' || fail "end-retained-directory test: retained directory or restoration state changed"
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["remote_directory"] == "files/voice-real-room/" + "a" * 64
assert state["package_stopped"] is False
assert state["restoration_count"] == 1
PY
  [[ -d "$REMOTE_APP_DATA_ROOT/files/voice-real-room/$(printf 'a%.0s' {1..64})" ]] ||
    fail "end-retained-directory test: controlled final rmdir failure lost the directory"
  pass

  rm -f -- "$cleanup_output" "$state" "$finalization"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  write_finalization "$finalization"
  export FAKE_ADB_FAIL_CLEANUP_BROKER=1
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-no-downgrade test: cleanup failure rewrote complete finalization"
  pass

  rm -f -- "$cleanup_output" "$state" "$finalization"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  write_finalization "$finalization"
  export FAKE_LN_RACE_DESTINATION="$cleanup_output"
  run_helper end --state "$state" --finalization "$finalization" \
    --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "end-race test: late cleanup destination race succeeded"
  [[ "$(<"$cleanup_output")" == raced ]] || fail "end-race test: existing cleanup destination was overwritten"
  python3 - "$FAKE_STATE" <<'PY' || fail "end-race test: publication happened before package restoration"
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["package_stopped"] is False
assert state["restoration_count"] == 1
PY
  assert_private_output_absent
  pass

  local signal_name
  for signal_name in HUP INT TERM; do
    rm -f -- "$cleanup_output" "$state" "$finalization"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    write_finalization "$finalization"
    export FAKE_ADB_SIGNAL_ON_ARTIFACT_READ=4
    export FAKE_ADB_ARTIFACT_SIGNAL="$signal_name"
    run_helper end --state "$state" --finalization "$finalization" \
      --cleanup-output "$cleanup_output"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
      fail "end-signal test: $signal_name teardown published cleanup"
    python3 - "$FAKE_STATE" "$signal_name" <<'PY' || fail "end-signal test: package restoration failed"
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["package_stopped"] is False
assert state["restoration_count"] == 1
PY
    assert_private_output_absent
    pass
  done
}

SELECT_ALL=0
SELECTED_OPERATIONS=("$@")
if [[ "$#" -eq 0 ]]; then
  SELECT_ALL=1
fi
for requested in "${SELECTED_OPERATIONS[@]}"; do
  case "$requested" in
    preflight|start|inject|interrupt|status|finalize|finalization|capture|end|cleanup|fixture-bounds|checkpoints|tracing) ;;
    *) fail "test filter must name a real-room operation" ;;
  esac
done

[[ -x "$HELPER" ]] || fail "voice-agent-real-room-step.sh does not exist"

if [[ "$SELECT_ALL" -eq 1 ]]; then
  run_general_validation_tests
  run_managed_owner_contract_tests
  run_owner_lock_key_test
fi
selected preflight && run_preflight_tests
selected start && run_start_tests
selected tracing && run_tracing_tests
if selected inject; then
  run_inject_tests
  run_host_lock_test
fi
selected fixture-bounds && run_fixture_bounds_tests
selected interrupt && run_interrupt_tests
selected status && run_status_tests
selected checkpoints && run_checkpoint_tests
if selected finalize || selected finalization; then
  run_finalize_tests
fi
selected capture && run_capture_tests
if selected end || selected cleanup; then
  run_end_tests
fi

printf 'PASS: voice-agent-real-room-step (%s assertions)\n' "$TEST_COUNT"
