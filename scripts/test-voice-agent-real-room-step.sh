#!/usr/bin/env bash
set -euo pipefail

umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT_DIR/scripts/voice-agent-real-room-step.sh"
LIBRARY="$ROOT_DIR/scripts/voice-agent-real-room-lib.sh"
REAL_TIMEOUT="$(command -v timeout)"
REAL_LN="$(command -v ln)"
REAL_RMDIR="$(command -v rmdir)"
CURRENT_UID="$(id -u)"
TMP_DIR="$(mktemp -d)"
chmod 700 "$TMP_DIR"
BIN_DIR="$TMP_DIR/bin"
mkdir "$BIN_DIR"
chmod 700 "$BIN_DIR"
ADB_LOG="$TMP_DIR/adb.argv"
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

cat > "$BIN_DIR/adb" <<'PY'
#!/usr/bin/env python3
import hashlib
import json
import os
import signal
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
        state["serial"],
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
    if state.get("call_active_recorded"):
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
        rows.append(automation_event(state, 3, "call_stopped", succeeded=True))
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
if os.environ.get("FAKE_ADB_BLOCK") == "1":
    time.sleep(10)
state = load_state()
maybe_block(argv)

if argv == ["devices", "-l"]:
    if os.environ.get("FAKE_ADB_MALFORMED_DEVICE_ENUMERATION") == "1":
        print("malformed device enumeration")
        raise SystemExit(0)
    print("List of devices attached")
    if os.environ.get("FAKE_ADB_DEVICE_LOST") != "1" and not state.get("device_lost_after_force_stop"):
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
if state.get("device_lost_after_force_stop"):
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
    if os.environ.get("FAKE_ADB_DEVICE_LOST") == "1":
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
                    'stat -Lc %d:%i /proc/self/fd/4',
                    'stat -c %d:%i "$name"',
                    'rmdir -- "$name"',
                    'stat -Lc %h /proc/self/fd/4',
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
        if (
            values.get("run_hash") != state["run_hash"]
            or values.get("comparison_hash") != state["comparison_hash"]
            or values.get("transport") != state["transport"]
            or state["automation_state"] != "active"
        ):
            complete(1, "status=error\nerror=invalid_state")
            raise SystemExit(0)
        state["automation_state"] = "finalized"
        state["run_finalized_recorded"] = True
        save_state(state)
        data = "status=ok\naction=finalize_bound"
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
        state["call_active"] = True
        state["call_active_recorded"] = True
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
chmod 700 "$BIN_DIR/adb"

export PATH="$BIN_DIR:$PATH"
export FAKE_ADB_LOG="$ADB_LOG"
export FAKE_TIMEOUT_LOG="$TIMEOUT_LOG"
export FAKE_LN_LOG="$LN_LOG"
export FAKE_ADB_STATE="$FAKE_STATE"
export REAL_TIMEOUT REAL_LN REAL_RMDIR
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
  python3 - "$FAKE_STATE" "$REMOTE_APP_DATA_ROOT" <<'PY'
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
        state["serial"],
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
  python3 - "$destination" "$package" "$FAKE_STATE" <<'PY'
import json
import os
import sys

with open(sys.argv[3], encoding="utf-8") as handle:
    fake = json.load(handle)
uid = fake["package_uid"]
gid = os.getgid()
payload = {
    "schemaVersion": 2,
    "serial": "DEVICE_SECRET_123",
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

run_helper() {
  local argument
  LAST_PRIVATE_PATHS=("$TMP_DIR" "$FAKE_STATE" "$STDOUT_FILE" "$STDERR_FILE" "$HELPER_TEMP_ROOT")
  for argument in "$@"; do
    [[ "$argument" == /* ]] && LAST_PRIVATE_PATHS+=("$argument")
  done
  : > "$STDOUT_FILE"
  : > "$STDERR_FILE"
  set +e
  TMPDIR="$HELPER_TEMP_ROOT" "$HELPER" "$@" >"$STDOUT_FILE" 2>"$STDERR_FILE"
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
assert matches[0][2:7] == [b"shell", b"am", b"start-foreground-service", b"--user", expected_user]
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
  run_helper start --state relative-state --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id conversation-1 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "absolute-output test: relative start state succeeded"
  assert_no_adb_mutations
  pass

  local state="$TMP_DIR/validation-state.json"
  write_valid_state "$state"
  local second_state="$TMP_DIR/validation-state-second.json"
  python3 - "$second_state" <<'PY'
import json
import os
import sys

payload = {
    "schemaVersion": 2,
    "serial": "DEVICE_SECRET_123",
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
    fail "state-v2-snapshot test: exact identity receipt did not decode immutably"
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

  reset_fake
  export FAKE_TIMEOUT_ENFORCE=1
  export FAKE_ADB_BLOCK=1
  local timeout_started=$SECONDS
  VOICE_STEP_ADB_TIMEOUT_SECONDS=1 run_helper preflight \
    --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  local timeout_elapsed=$((SECONDS - timeout_started))
  [[ "$RUN_STATUS" -ne 0 && "$timeout_elapsed" -lt 4 ]] ||
    fail "timeout-enforcement test: blocking ADB was not terminated by the configured deadline"
  python3 - "$TIMEOUT_LOG" <<'PY' || fail "timeout-shape test: bounded ADB argv prefix changed"
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
bounded = [command for command in commands if b"--signal=TERM" in command]
assert bounded
assert bounded[0][:5] == [b"--signal=TERM", b"--kill-after=2s", b"1s", b"adb", b"devices"]
PY
  assert_private_output_absent
  pass

  [[ -r "$LIBRARY" ]] || fail "shared-library test: real-room security library is absent"
  pass
}

run_preflight_tests() {
  reset_fake
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -eq 0 ]] ||
    fail "broadcast-framing test: literal multiline resultData was not consumed as one receiver record"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=preflight\nvoice-step.device=ready\nvoice-step.package=ready\nvoice-step.automation=ready\nvoice-step.protected_path=ready'
  [[ "$(command_count devices)" == "2" ]] || fail "preflight-command test: device-ready helper was not reused after exact enumeration"
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
    run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
    [[ "$RUN_STATUS" -ne 0 ]] || fail "preflight-capability test: malformed $malformed_mode readback succeeded"
    assert_no_adb_mutations
    assert_private_output_absent
    pass
  done

  reset_fake
  export FAKE_ADB_BROADCAST_TRAILING_JUNK=1
  run_helper preflight --serial DEVICE_SECRET_123 --package me.rerere.rikkahub.debug
  [[ "$RUN_STATUS" -ne 0 ]] ||
    fail "broadcast-framing test: trailing junk after the literal multiline resultData succeeded"
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
  python3 - "$state" "$FAKE_STATE" <<'PY' || fail "start-state test: private state contract mismatch"
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    state = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    fake = json.load(handle)
assert list(state) == [
    "schemaVersion", "serial", "package", "androidUserId", "packageUid",
    "conversationId", "runHash", "comparisonHash", "fixtureToken",
    "fixtureParentIdentity", "fixtureDirectoryIdentity", "fixtureOwnershipNonce",
    "traceId", "transport",
]
assert state == {
    "schemaVersion": 2,
    "serial": "DEVICE_SECRET_123",
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
  pass

  reset_fake
  rm -f -- "$state"
  export FAKE_ADB_PREEXISTING_REMOTE_DIR=1
  run_helper start --state "$state" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$state" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$state" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
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
  run_helper start --state "$malformed_rollback" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$cleanup_signaled" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$signaled" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$before_link" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$after_link" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$pinned_state" --serial DEVICE_SECRET_123 \
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
  run_helper start --state "$pinned_race" --serial DEVICE_SECRET_123 \
    --package me.rerere.rikkahub.debug --conversation-id CONVERSATION_SECRET_123 \
    --run-hash "sha256:$(printf 'a%.0s' {1..64})" \
    --comparison-hash "sha256:$(printf 'b%.0s' {1..64})" --fixture "$fixture"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "pinned-user rollback test: raced start succeeded"
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.END_BOUND' 10
  [[ "$(exact_command_count -s DEVICE_SECRET_123 shell am broadcast --user 10 --include-stopped-packages -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver -a me.rerere.rikkahub.voiceagent.automation.STATUS)" == 1 ]] ||
    fail "pinned-user rollback test: package restoration changed Android user"
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
stream = [command for command in commands if any(b"voice-step-stage-owned-fixture" in value for value in command)]
stage = [command for command in commands if any(b"STAGE_CAPTURE_FIXTURE" in value for value in command)]
trigger = [command for command in commands if any(b"TRIGGER_CAPTURE_FIXTURE" in value for value in command)]
assert len(stream) == len(stage) == len(trigger) == 1
assert stream[0][-2] == expected_path
assert b"exec-in" not in stream[0]
stream_script = next(value for value in stream[0] if b"voice-step-stage-owned-fixture" in value)
assert b"voice-step-descriptor-owned-stage" in stream_script
assert b"/proc/self/fd/3" in stream_script
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
    --state "$state" --fixture "$first_fixture" --role request \
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
    "$HELPER" inject --state "$state" --fixture "$second_fixture" --role follow_up \
    >"$second_stdout" 2>"$second_stderr"
  second_status=$?
  set -e
  [[ "$second_status" -ne 0 && "$second_status" -ne 124 ]] ||
    fail "host-lock test: contending helper did not fail promptly"
  [[ ! -s "$second_log" ]] ||
    fail "host-lock test: contending helper reached ADB while the serial/package lock was held"
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

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_VALIDATED_FALSE=1
  run_helper status --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "status-validation test: validated=false succeeded"
  [[ ! -s "$STDOUT_FILE" ]] || fail "status-validation test: failed validation wrote stdout"
  assert_private_output_absent
  pass
}

run_finalize_tests() {
  local state="$TMP_DIR/finalize-state.json"
  reset_fake
  activate_fake_run
  write_valid_state "$state"
  export FAKE_ADB_DURABLE_STOP_VISIBLE_AFTER=2
  run_helper finalize --state "$state"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.automation=finalized'
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
  rm -f -- "$state"
  python3 - "$FAKE_STATE" <<'PY'
import json, os, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["android_user_id"] = 10
temporary = path + ".user"
json.dump(state, open(temporary, "w", encoding="utf-8"), separators=(",", ":"))
os.replace(temporary, path)
PY
  activate_fake_run
  write_valid_state "$state"
  run_helper finalize --state "$state"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=finalize\nvoice-step.automation=finalized'
  assert_service_action_pinned 'me.rerere.rikkahub.voiceagent.action.END_BOUND' 10

  local failure_mode
  for failure_mode in end-rejected stop-timeout malformed-stop; do
    reset_fake
    activate_fake_run
    rm -f -- "$state"
    write_valid_state "$state"
    case "$failure_mode" in
      end-rejected) export FAKE_ADB_FAIL_END=1 ;;
      stop-timeout) export FAKE_ADB_SERVICE_STAYS_ACTIVE=1 ;;
      malformed-stop) export FAKE_ADB_MALFORMED_DURABLE_ENDING=stopped-false ;;
    esac
    run_helper finalize --state "$state"
    [[ "$RUN_STATUS" -ne 0 ]] || fail "finalize-stop-proof test: $failure_mode succeeded"
    [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" &&
       "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "0" ]] ||
      fail "finalize-stop-proof test: $failure_mode retried END or reached FINALIZE"
    [[ "$(command_count STATUS)" == "0" ]] ||
      fail "finalize-stop-proof test: active automation STATUS was sent for $failure_mode"
    assert_private_output_absent
    pass
  done

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_MALFORMED_BROADCAST='me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND'
  run_helper finalize --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "finalize-reply test: malformed FINALIZE succeeded"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE)" == "0" ]] ||
    fail "finalize-reply test: malformed bound FINALIZE was retried or fell back"
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  python3 - "$FAKE_STATE" <<'PY'
import json, os, sys
path = sys.argv[1]
state = json.load(open(path, encoding="utf-8"))
state["run_hash"] = "sha256:" + "c" * 64
temporary = path + ".stale"
json.dump(state, open(temporary, "w", encoding="utf-8"), separators=(",", ":"))
os.replace(temporary, path)
PY
  run_helper finalize --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "finalize-stale test: stale state stopped or finalized a replacement run"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "0" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE)" == "0" ]] ||
    fail "finalize-stale test: bound end rejection reached a finalize mutation"
  python3 - "$FAKE_STATE" <<'PY' || fail "finalize-stale test: replacement run was mutated"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["automation_state"] == "active"
assert state["run_hash"] == "sha256:" + "c" * 64
assert state["call_stopped_recorded"] is False
PY
  assert_private_output_absent
  pass

  reset_fake
  activate_fake_run
  rm -f -- "$state"
  write_valid_state "$state"
  export FAKE_ADB_MALFORMED_DURABLE_ENDING=event-after-finalized
  run_helper finalize --state "$state"
  [[ "$RUN_STATUS" -ne 0 ]] ||
    fail "finalize-durable-ending test: event after run_finalized succeeded"
  [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == "1" &&
     "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == "1" ]] ||
    fail "finalize-durable-ending test: terminal mutations were retried"
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
  export FAKE_ADB_STATUS_EVENT_COUNT_DRIFT=1
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-status-event-drift test: changed event count succeeded"
  [[ ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-status-event-drift test: changed status published a destination"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_STATUS_NETWORK_DRIFT=1
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-status-network-drift test: changed network succeeded"
  [[ ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-status-network-drift test: changed status published a destination"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
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

  rm -f -- "$automation" "$private" "$sanitized" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_SUBSECOND_METADATA_CHANGE=1
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-subsecond test: nanosecond source mutation succeeded"
  [[ ! -e "$automation" && ! -e "$private" && ! -e "$sanitized" ]] ||
    fail "capture-subsecond test: changing source published a destination"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state"
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
  export FAKE_LN_RACE_DESTINATION="$automation"
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


  rm -f -- "$automation" "$private" "$sanitized" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_LN_RACE_DESTINATION="$private"
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-second-race test: second destination race succeeded"
  [[ -s "$automation" && "$(<"$private")" == raced && ! -e "$sanitized" ]] ||
    fail "capture-second-race test: sequential no-replace semantics were violated"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_LN_RACE_DESTINATION="$sanitized"
  run_helper capture --state "$state" \
    --automation-output "$automation" \
    --private-voice-output "$private" \
    --sanitized-voice-output "$sanitized"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "capture-third-race test: third destination race succeeded"
  [[ -s "$automation" && -s "$private" && "$(<"$sanitized")" == raced ]] ||
    fail "capture-third-race test: prior publications or raced destination were corrupted"
  assert_no_capture_temps "$output_dir"
  assert_private_output_absent
  pass

  rm -f -- "$automation" "$private" "$sanitized" "$state"
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
  local expected_complete='{"schemaVersion":1,"outcome":"complete","callStopped":true,"fixturesRemoved":true,"automationFinalized":true}'
  local expected_product_failure='{"schemaVersion":1,"outcome":"product_failure","callStopped":true,"fixturesRemoved":false,"automationFinalized":true}'
  local expected_infrastructure='{"schemaVersion":1,"outcome":"infrastructure_interruption","callStopped":true,"fixturesRemoved":false,"automationFinalized":true}'
  python3 - "$HELPER" <<'PY' || fail "exit-signal test: cleanup deferral was installed after EXIT removal"
import sys

source = open(sys.argv[1], encoding="utf-8").read()
body = source.split("on_exit() {", 1)[1].split("\n}", 1)[0]
assert body.index("trap defer_exit_cleanup_signal HUP INT TERM") < body.index("trap - EXIT")
PY
  pass
  reset_fake
  finalize_fake_run true
  write_valid_state "$state"
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=complete'
  [[ "$(<"$cleanup_output")" == "$expected_complete" ]] ||
    fail "end-record test: complete cleanup record mismatch"
  [[ -f "$cleanup_output" && ! -L "$cleanup_output" && "$(stat -c '%a' "$cleanup_output")" == 600 ]] ||
    fail "end-publication test: cleanup record was not a mode-0600 regular file"
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
assert broker[-5:] == [
    b"files/voice-real-room/" + b"a" * 64,
    state["fixture_parent_identity"].encode(),
    state["fixture_directory_identity"].encode(),
    b"0123456789abcdef0123456789abcdef",
    str(state["package_uid"]).encode(),
]
script = next(value for value in broker if b"voice-step-cleanup-broker" in value)
assert b"/proc/self/fd/4" in script and b'stat -Lc %h /proc/self/fd/4' in script
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

  local malformed_mode
  for malformed_mode in stopped-false event-after-finalized binding-mismatch missing-call-stopped noncanonical-keys; do
    rm -f -- "$cleanup_output" "$state"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    export FAKE_ADB_MALFORMED_DURABLE_ENDING="$malformed_mode"
    run_helper end --state "$state" --cleanup-output "$cleanup_output"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
      fail "end-durable-ending test: malformed $malformed_mode evidence published cleanup"
    [[ "$(command_count force-stop)" == 0 && "$(command_count voice-step-cleanup-broker)" == 0 ]] ||
      fail "end-durable-ending test: malformed $malformed_mode evidence reached mutation"
    assert_private_output_absent
    pass
  done

  local failure_mode
  for failure_mode in force-stop quiescence unstable-quiescence isolated broker; do
    rm -f -- "$cleanup_output" "$state"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    case "$failure_mode" in
      force-stop) export FAKE_ADB_FAIL_FORCE_STOP=1 ;;
      quiescence) export FAKE_ADB_PACKAGE_PROCESS=1 ;;
      unstable-quiescence) export FAKE_ADB_UNSTABLE_QUIESCENCE=1 ;;
      isolated) export FAKE_ADB_ISOLATED_PROCESS=1 ;;
      broker) export FAKE_ADB_FAIL_CLEANUP_BROKER=1 ;;
    esac
    run_helper end --state "$state" --cleanup-output "$cleanup_output"
    assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
    [[ "$(<"$cleanup_output")" == "$expected_product_failure" ]] ||
      fail "end-product-failure test: $failure_mode lost durable final proof"
    [[ "$(exact_argument_count me.rerere.rikkahub.voiceagent.action.END_BOUND)" == 0 &&
       "$(exact_argument_count me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND)" == 0 ]] ||
      fail "end-product-failure test: $failure_mode issued a terminal mutation"
    if [[ "$failure_mode" != force-stop ]]; then
      [[ "$(exact_command_count -s DEVICE_SECRET_123 shell am broadcast --user 0 --include-stopped-packages -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver -a me.rerere.rikkahub.voiceagent.automation.STATUS)" == 1 ]] ||
        fail "end-product-failure test: $failure_mode did not restore exactly once"
    else
      [[ "$(command_count --include-stopped-packages)" == 0 ]] ||
        fail "end-product-failure test: rejected force-stop attempted false restoration"
      python3 - "$FAKE_STATE" <<'PY' || fail "end-product-failure test: rejected force-stop changed runtime state"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["package_stopped"] is False
assert state["automation_state"] == "finalized"
assert state.get("restoration_count", 0) == 0
PY
    fi
    pass
  done

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_TIMEOUT_EXECUTE_THEN_TIMEOUT_MATCH=force-stop
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  [[ "$(<"$cleanup_output")" == "$expected_product_failure" ]] ||
    fail "end-execute-timeout test: partial force-stop delivery changed evidence"
  python3 - "$FAKE_STATE" <<'PY' || fail "end-execute-timeout test: cleanup published before exact restoration"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["force_stop_observed"] is True
assert state["package_stopped"] is False
assert state["automation_state"] == "idle"
assert state["restoration_count"] == 1
PY
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_TIMEOUT_EXECUTE_THEN_TIMEOUT_MATCH=force-stop
  export FAKE_ADB_MALFORMED_RESTORATION=wrong-state
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-execute-timeout test: ambiguous restoration published cleanup"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_FAIL_FORCE_STOP=1
  export FAKE_ADB_FAIL_REACHABILITY_PROBE=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  [[ "$(<"$cleanup_output")" == "$expected_product_failure" &&
     "$(exact_command_count devices -l)" == 0 &&
     "$(command_count --include-stopped-packages)" == 0 ]] ||
    fail "end-classification test: canonical stopped=false was not direct product failure"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_FAIL_FORCE_STOP=1
  export FAKE_ADB_DEVICE_LOST=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ -f "$cleanup_output" && "$(<"$cleanup_output")" == "$expected_infrastructure" ]] ||
    fail "end-classification test: exact device loss was not infrastructure interruption"
  [[ "$(exact_command_count devices -l)" == 1 ]] ||
    fail "end-classification test: device loss lacked one independent enumeration"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_FAIL_FORCE_STOP=1
  export FAKE_ADB_MALFORMED_DEVICE_ENUMERATION=1
  export FAKE_ADB_MALFORMED_STOPPED_ROW=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-classification test: malformed device enumeration published cleanup"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_FAIL_FORCE_STOP=1
  export FAKE_ADB_DEVICE_ENUMERATION_STATE=error
  export FAKE_ADB_MALFORMED_STOPPED_ROW=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-classification test: unknown device state published cleanup"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_FORCE_STOP_STOPPED_FALSE=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  [[ "$(<"$cleanup_output")" == "$expected_product_failure" ]] ||
    fail "end-classification test: canonical stopped=false was not product failure"
  [[ "$(command_count --include-stopped-packages)" == 0 ]] ||
    fail "end-classification test: canonical stopped=false attempted false restoration"
  python3 - "$FAKE_STATE" <<'PY' || fail "end-classification test: stopped=false changed finalized runtime"
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["package_stopped"] is False
assert state["automation_state"] == "finalized"
assert state.get("restoration_count", 0) == 0
PY
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_MALFORMED_STOPPED_ROW=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-classification test: malformed stopped-state readback published cleanup"
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_POST_CLEANUP_ARTIFACT_CHANGE=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
    fail "end-post-cleanup-artifact test: changed durable bytes published cleanup"
  [[ "$(command_count voice-step-cleanup-broker)" == 1 &&
     "$(command_count --include-stopped-packages)" == 1 ]] ||
    fail "end-post-cleanup-artifact test: cleanup or EXIT restoration did not run once"
  assert_private_output_absent
  pass

  local restoration_mode
  for restoration_mode in failure malformed; do
    rm -f -- "$cleanup_output" "$state"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    if [[ "$restoration_mode" == failure ]]; then
      export FAKE_ADB_FAIL_RESTORATION=1
    else
      export FAKE_ADB_MALFORMED_RESTORATION=wrong-state
    fi
    run_helper end --state "$state" --cleanup-output "$cleanup_output"
    [[ "$RUN_STATUS" -ne 0 && ! -e "$cleanup_output" ]] ||
      fail "end-restoration test: $restoration_mode reply published cleanup"
    [[ "$(command_count --include-stopped-packages)" -ge 1 ]] ||
      fail "end-restoration test: $restoration_mode path skipped bounded restoration"
    assert_private_output_absent
    pass
  done

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_ADB_RETAIN_FIXTURE_DIR=1
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=product_failure'
  [[ "$(<"$cleanup_output")" == "$expected_product_failure" ]] ||
    fail "end-retained-directory test: retained directory changed product evidence"
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

  local actor_trigger="$TMP_DIR/end-actor-trigger"
  local actor_result="$TMP_DIR/end-actor-result"
  local remote_directory="$REMOTE_APP_DATA_ROOT/files/voice-real-room/$(printf 'a%.0s' {1..64})"
  rm -f -- "$cleanup_output" "$state" "$actor_trigger" "$actor_result"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  (
    while [[ ! -e "$actor_trigger" ]]; do sleep 0.01; done
    printf 'entered\n' > "$actor_result"
  ) &
  ACTOR_PID=$!
  export FAKE_ADB_ACTOR_PID="$ACTOR_PID"
  export FAKE_ADB_ACTOR_TRIGGER="$actor_trigger"
  export FAKE_ADB_ACTOR_RESULT="$actor_result"
  export FAKE_RMDIR_ACTOR_ENTERED="$TMP_DIR/end-actor-entered"
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
  assert_exact_output $'voice-step.status=ok\nvoice-step.operation=end\nvoice-step.outcome=complete'
  wait "$ACTOR_PID"
  ACTOR_PID=''
  [[ -e "$actor_result" && ! -e "$remote_directory" ]] ||
    fail "end-live-actor test: actor did not resume after owned directory deletion"
  python3 - "$FAKE_STATE" <<'PY' || fail "end-live-actor test: actor crossed the stopped cleanup boundary"
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["actor_sigstop_observed"] is True
assert state["actor_sigcont_observed"] is True
assert not state.get("actor_entered_cleanup_boundary", False)
assert state["cleanup_broker_exact_shell_executed"] is True
assert state["rmdir_boundary_observed"] is True
PY
  pass

  rm -f -- "$cleanup_output" "$state"
  reset_fake
  finalize_fake_run false
  write_valid_state "$state"
  export FAKE_LN_RACE_DESTINATION="$cleanup_output"
  run_helper end --state "$state" --cleanup-output "$cleanup_output"
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
    rm -f -- "$cleanup_output" "$state"
    reset_fake
    finalize_fake_run false
    write_valid_state "$state"
    export FAKE_ADB_SIGNAL_ON_ARTIFACT_READ=4
    export FAKE_ADB_ARTIFACT_SIGNAL="$signal_name"
    run_helper end --state "$state" --cleanup-output "$cleanup_output"
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
    preflight|start|inject|interrupt|status|finalize|capture|end|fixture-bounds) ;;
    *) fail "test filter must name a real-room operation" ;;
  esac
done

[[ -x "$HELPER" ]] || fail "voice-agent-real-room-step.sh does not exist"

if [[ "$SELECT_ALL" -eq 1 ]]; then
  run_general_validation_tests
fi
selected preflight && run_preflight_tests
selected start && run_start_tests
if selected inject; then
  run_inject_tests
  run_host_lock_test
fi
selected fixture-bounds && run_fixture_bounds_tests
selected interrupt && run_interrupt_tests
selected status && run_status_tests
selected finalize && run_finalize_tests
selected capture && run_capture_tests
selected end && run_end_tests

printf 'PASS: voice-agent-real-room-step (%s assertions)\n' "$TEST_COUNT"
