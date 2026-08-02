#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/scripts/voice-agent-stage1-e2e.sh"
TMP_DIR="$(mktemp -d)"
BIN_DIR="$TMP_DIR/bin"
STATE_DIR="$TMP_DIR/state"
ADB_LOG="$TMP_DIR/adb-argv.bin"
CLOCK_LOG="$TMP_DIR/clock-argv.bin"
EXTERNAL_LOG="$TMP_DIR/external-argv.log"
LOCK_DIR="$TMP_DIR/locks"
PCM_PATH="$TMP_DIR/prompt.pcm"
INTERRUPT_PCM_PATH="$TMP_DIR/interrupt.pcm"
STARTUP_PCM_PATH="$TMP_DIR/startup.pcm"
TRANSCRIPT_FIXTURE_DIR="$TMP_DIR/transcript-fixtures"
WORKER_LOG="$TMP_DIR/worker.log"
SERIAL="RZCX71NXRPB"
PACKAGE="me.rerere.rikkahub.debug"
RUN_HASH="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
COMPARISON_HASH="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$BIN_DIR" "$STATE_DIR" "$LOCK_DIR" "$TRANSCRIPT_FIXTURE_DIR"
printf 'primary-pcm' > "$PCM_PATH"
printf 'interrupt-pcm' > "$INTERRUPT_PCM_PATH"
printf 'startup-silence' > "$STARTUP_PCM_PATH"
: > "$WORKER_LOG"
: > "$EXTERNAL_LOG"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  [[ "$haystack" == *"$needle"* ]] || fail "expected output to contain: $needle"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  [[ "$haystack" != *"$needle"* ]] || fail "expected output not to contain: $needle"
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  [[ "$actual" == "$expected" ]] || {
    printf 'Expected:\n%s\nActual:\n%s\n' "$expected" "$actual" >&2
    exit 1
  }
}

path_metadata() {
  python3 - "$1" <<'PY'
import json
import os
import stat
import sys

path = sys.argv[1]
value = os.lstat(path)
payload = {
    "device": value.st_dev,
    "inode": value.st_ino,
    "mode": value.st_mode,
    "links": value.st_nlink,
    "uid": value.st_uid,
    "gid": value.st_gid,
    "size": value.st_size,
    "mtimeNs": value.st_mtime_ns,
    "ctimeNs": value.st_ctime_ns,
    "target": os.readlink(path) if stat.S_ISLNK(value.st_mode) else None,
}
print(json.dumps(payload, sort_keys=True, separators=(",", ":")))
PY
}

cat > "$BIN_DIR/adb" <<'PY'
#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
state_dir = Path(os.environ["FAKE_ADB_STATE_DIR"])
state_dir.mkdir(parents=True, exist_ok=True)
state_file = state_dir / "state.json"
log_file = Path(os.environ["FAKE_ADB_LOG"])

with log_file.open("ab") as handle:
    for arg in args:
        handle.write(arg.encode("utf-8") + b"\0")
    handle.write(b"__END__\0")

if state_file.exists():
    state = json.loads(state_file.read_text())
else:
    state = {
        "network": "wifi",
        "run_state": "idle",
        "run_hash": "none",
        "comparison_hash": "none",
        "transport": "none",
        "lifecycle": "foreground",
        "route": "speaker",
        "event_count": 0,
        "event_time_ms": 0,
        "status_reads": 0,
        "events": [],
        "call_started": False,
        "injections": 0,
        "fixture_token": None,
        "fixture_initial_bytes": 0,
        "initial_attestation_observed_by_runner": False,
        "attestation_clock_calls": None,
        "staged": {},
        "staged_reads": {},
        "event_grep_reads": {},
        "voice_trace_id": os.environ.get("FAKE_ADB_INITIAL_VOICE_TRACE_ID", "prior-private-trace"),
        "voice_trace_pointer_exists": os.environ.get("FAKE_ADB_INITIAL_VOICE_TRACE_ABSENT") != "1",
        "current_voice_trace_id": os.environ.get("FAKE_ADB_CURRENT_VOICE_TRACE_ID", "current-private-trace"),
        "voice_events": os.environ.get("FAKE_ADB_VOICE_EVENTS", '{"kind":"sanitized"}\n'),
        "voice_events_exists": os.environ.get("FAKE_ADB_VOICE_EVENTS_EXISTS", "1") == "1",
        "voice_events_regular": os.environ.get("FAKE_ADB_VOICE_EVENTS_REGULAR", "1") == "1",
        "voice_events_symlink": os.environ.get("FAKE_ADB_VOICE_EVENTS_SYMLINK") == "1",
        "trace_pointer_reads": 0,
        "call_start_clock": None,
        "call_end_clock": None,
    }
    if os.environ.get("FAKE_ADB_INITIAL_RUN") == "foreign":
        state.update({
            "run_state": "active",
            "run_hash": "sha256:" + "c" * 64,
            "comparison_hash": "sha256:" + "d" * 64,
            "transport": "livekit_experimental",
        })

def save():
    state_file.write_text(json.dumps(state, separators=(",", ":")))

def fake_clock_value():
    counter = Path(os.environ["FAKE_CLOCK_COUNTER"])
    return int(counter.read_text()) if counter.exists() else 0

def fail_if_requested(name):
    if (
        name == "voice_events_read"
        and os.environ.get("FAKE_ADB_NOISY_PRIVATE_FAILURE") == "1"
    ):
        print("PRIVATE-REMOTE-VOICE-EVENTS-PATH", file=sys.stderr)
    if os.environ.get("FAKE_ADB_FAIL_MODE") == name:
        raise SystemExit(82)

def fail_trace_pointer_read_if_requested():
    state["trace_pointer_reads"] += 1
    if state["trace_pointer_reads"] == 1:
        advance = int(os.environ.get("FAKE_ADB_TRACE_SNAPSHOT_CLOCK_ADVANCE", "0"))
        if advance:
            counter = Path(os.environ["FAKE_CLOCK_COUNTER"])
            counter.write_text(str(fake_clock_value() + advance))
    save()
    mode = os.environ.get("FAKE_ADB_FAIL_MODE")
    if mode == "initial_trace_pointer_read" and state["trace_pointer_reads"] == 1:
        raise SystemExit(82)
    if mode == "final_trace_pointer_read" and state["trace_pointer_reads"] == 2:
        raise SystemExit(82)

def emit(
    name,
    *,
    observed_transport=None,
    route=None,
    network=None,
    lifecycle=None,
    playback_epoch=None,
    byte_count=None,
    rms_active=None,
    audio_window_micros=None,
    succeeded=None,
    capture_source=None,
    mic_bytes=None,
    fixture_bytes=None,
):
    state["event_count"] += 1
    state["event_time_ms"] += 10
    event = {
        "schemaVersion": 1,
        "monotonicMs": state["event_time_ms"],
        "wallClockMs": 1_800_000_000_000 + state["event_time_ms"],
        "runHash": state["run_hash"],
        "comparisonHash": state["comparison_hash"],
        "requestedTransport": state["transport"],
        "observedTransport": observed_transport,
        "name": name,
        "route": route,
        "network": network,
        "lifecycle": lifecycle,
        "playbackEpoch": playback_epoch,
        "byteCount": byte_count,
        "rmsActive": rms_active,
        "audioWindowMicros": audio_window_micros,
        "succeeded": succeeded,
        "correlationKind": None,
        "correlationHash": None,
        "captureSource": capture_source,
        "micBytes": mic_bytes,
        "fixtureBytes": fixture_bytes,
    }
    state["events"].append(json.dumps(event, separators=(",", ":")))

def advance_event_time(duration_ms):
    state["event_time_ms"] += duration_ms

def emit_probe_audio(active, duration_micros=5_000):
    emit(
        "playback_written",
        playback_epoch=1,
        byte_count=480,
        rms_active=active,
        audio_window_micros=duration_micros,
    )

def completed(result, data):
    if os.environ.get("FAKE_ADB_BROADCAST_MULTILINE") == "1":
        print(f'Broadcast completed: result={result}, data="{data}"')
        return
    escaped = data.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
    print(f'Broadcast completed: result={result}, data="{escaped}"')

def extras():
    result = {}
    index = 0
    while index < len(args):
        if args[index] == "--es" and index + 2 < len(args):
            result[args[index + 1]] = args[index + 2]
            index += 3
        else:
            index += 1
    return result

if args == ["devices", "-l"]:
    print("List of devices attached")
    mode = os.environ.get("FAKE_ADB_DEVICES_MODE", "single")
    if mode in {"single", "multiple"}:
        print("RZCX71NXRPB device product:r11q model:SM-S711B device:r11q transport_id:1")
    if mode == "multiple":
        print("SECOND123 device product:r11q model:SM-S711B device:r11q transport_id:2")
    sys.exit(0)

if len(args) < 3 or args[:2] != ["-s", "RZCX71NXRPB"]:
    print(f"unexpected unselected adb args: {args!r}", file=sys.stderr)
    sys.exit(90)

tail = args[2:]
if tail == ["shell", "echo", "ok"]:
    print("ok")
elif tail == ["shell", "getprop", "sys.boot_completed"]:
    print("1")
elif tail == ["shell", "getprop", "init.svc.bootanim"]:
    print("stopped")
elif tail == ["shell", "getprop", "ro.product.model"]:
    print("SM-S711B")
elif tail == ["shell", "getprop", "ro.build.version.release"]:
    print("16")
elif tail == ["shell", "getprop", "ro.kernel.qemu"]:
    print("1" if os.environ.get("FAKE_ADB_EMULATOR") == "1" else "0")
elif tail == ["shell", "getprop", "ro.hardware"]:
    print("ranchu" if os.environ.get("FAKE_ADB_EMULATOR") == "1" else "qcom")
elif tail == ["shell", "pm", "path", "me.rerere.rikkahub.debug"]:
    print("package:/data/app/test/base.apk")
elif tail == ["shell", "run-as", "me.rerere.rikkahub.debug", "id"]:
    print("uid=10123(u0_a123) gid=10123(u0_a123)")
elif tail == ["shell", "svc", "wifi"]:
    print(os.environ.get("FAKE_ADB_SVC_WIFI_USAGE_OUTPUT", "usage: svc wifi [enable|disable]"))
    if os.environ.get("FAKE_ADB_SVC_WIFI_USAGE_STATUS"):
        sys.exit(int(os.environ["FAKE_ADB_SVC_WIFI_USAGE_STATUS"]))
elif tail == ["shell", "svc", "data"]:
    print(os.environ.get("FAKE_ADB_SVC_DATA_USAGE_OUTPUT", "usage: svc data [enable|disable]"))
    if os.environ.get("FAKE_ADB_SVC_DATA_USAGE_STATUS"):
        sys.exit(int(os.environ["FAKE_ADB_SVC_DATA_USAGE_STATUS"]))
elif tail == ["shell", "svc", "data", "enable"]:
    state["cellular_enabled"] = True
    save()
elif tail == ["shell", "svc", "wifi", "disable"]:
    state["network"] = "cellular"
    if os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "reconnect_started":
        emit("reconnect_started")
        state["reconnect_started"] = True
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "wifi_disable":
        sys.exit(73)
elif tail == ["shell", "svc", "wifi", "enable"]:
    state["network"] = "wifi"
    if os.environ.get("FAKE_ADB_UNVALIDATED_AFTER_RESTORE") == "1" and state.get("run_state") == "finalized":
        state["unvalidated"] = True
    if state.get("handover_started"):
        state["recovery_ready"] = True
    if state.get("reconnect_started") and os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "reconnect_transport_restored":
        emit("reconnect_transport_restored")
        state["reconnect_transport_restored"] = True
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "wifi_restore" and state.get("handover_started"):
        sys.exit(78)
elif tail == ["shell", "dumpsys", "connectivity"]:
    active_id = "101" if state["network"] == "wifi" else "202"
    inactive_id = "202" if active_id == "101" else "101"
    inactive_transport = "CELLULAR" if state["network"] == "wifi" else "WIFI"
    active_transport = state["network"].upper()
    print(f"Active default network: {active_id}")
    print(f"  NetworkAgentInfo{{network{{{inactive_id}}} ni{{extra=network{{{active_id}}}}} nc{{[ Transports: {inactive_transport} Capabilities: VALIDATED&INTERNET ]}}}}")
    active_capabilities = "INTERNET" if state.get("unvalidated") else "VALIDATED&INTERNET"
    print(f"  NetworkAgentInfo{{network{{{active_id}}} ni{{WIFI CELLULAR VALIDATED}} nc{{[ Transports: {active_transport} Capabilities: {active_capabilities} ]}}}}")
    print("    NetworkRequestInfo{requests=[ Transports: WIFI|CELLULAR Capabilities: INTERNET ]}")
elif tail == ["shell", "dumpsys", "activity", "activities"]:
    resumed = ("me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity"
               if state.get("app_foreground") else "com.android.launcher/.Launcher")
    print(f"mResumedActivity: ActivityRecord{{test u0 {resumed} t1}}")
elif tail[:4] == ["shell", "run-as", "me.rerere.rikkahub.debug", "mkdir"]:
    # Real `adb shell` may consume the caller's stdin even when mkdir does not.
    # The runner must keep fixture bytes away from this preparatory command.
    sys.stdin.buffer.read()
elif tail[:4] == ["shell", "run-as", "me.rerere.rikkahub.debug", "test"]:
    path = tail[-1]
    state["staged_reads"][path] = state["staged_reads"].get(path, 0) + 1
    save()
    if os.environ.get("FAKE_ADB_STAGE_VISIBILITY_DELAY") == "1" and state["staged_reads"][path] == 1:
        sys.exit(1)
    if state["staged"].get(path, 0) <= 0:
        sys.exit(1)
elif tail[:5] == ["shell", "run-as", "me.rerere.rikkahub.debug", "stat", "-c"]:
    path = tail[-1]
    if path not in state["staged"]:
        sys.exit(1)
    state["staged_reads"][path] = state["staged_reads"].get(path, 0) + 1
    save()
    if os.environ.get("FAKE_ADB_STAGE_VISIBILITY_DELAY") == "1" and state["staged_reads"][path] == 1:
        print(0)
        sys.exit(0)
    print(state["staged"][path])
elif tail[:4] == ["shell", "run-as", "me.rerere.rikkahub.debug", "rm"]:
    for value in tail[5:]:
        state["staged"].pop(value, None)
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "preflight_remove" and tail[-1].endswith(".preflight"):
        sys.exit(79)
elif tail[:5] == ["exec-in", "run-as", "me.rerere.rikkahub.debug", "sh", "-c"]:
    path = tail[-1]
    state["staged"][path] = len(sys.stdin.buffer.read())
    state["staged_reads"][path] = 0
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "preflight_stage" and path.endswith(".preflight"):
        sys.exit(80)
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "stage_interrupt" and path.endswith("interrupt.pcm"):
        sys.exit(75)
elif tail[:5] == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "sh", "-c"]:
    remote_path = tail[-1]
    if remote_path == "no_backup/voice-e2e/latest-trace-id.txt":
        fail_if_requested("trace_pointer_probe")
        if not state["voice_trace_pointer_exists"]:
            print("absent")
        else:
            print("present")
    elif remote_path.endswith("/voice-experience-events.ndjson"):
        fail_if_requested("voice_events_probe")
        if not state["voice_events_exists"]:
            print("absent")
        elif state["voice_events_symlink"]:
            if '[ -L "$1" ]' in tail[5]:
                print("invalid")
            else:
                print("present")
        elif not state["voice_events_regular"]:
            print("invalid")
        else:
            print("present")
    else:
        print(f"unexpected remote probe path: {remote_path!r}", file=sys.stderr)
        sys.exit(99)
elif tail[:3] == ["shell", "am", "start"]:
    if "android.intent.category.HOME" in tail:
        if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "preaction_race":
            emit("lifecycle_observed", lifecycle="background")
        else:
            state["app_foreground"] = False
            if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") != "stale":
                emit("lifecycle_observed", lifecycle="background")
    else:
        if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "preaction_race":
            emit("lifecycle_observed", lifecycle="foreground")
        else:
            state["app_foreground"] = True
            if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") != "stale":
                emit("lifecycle_observed", lifecycle="foreground")
    save()
    print("Status: ok")
elif tail == ["shell", "input", "keyevent", "HOME"]:
    if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "preaction_race":
        emit("lifecycle_observed", lifecycle="background")
    else:
        state["app_foreground"] = False
        if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") != "stale":
            emit("lifecycle_observed", lifecycle="background")
    save()
elif tail[:4] == ["shell", "am", "start-foreground-service", "-n"]:
    action = tail[tail.index("-a") + 1]
    if action.endswith(".START"):
        values = extras()
        state["call_start_clock"] = fake_clock_value()
        state["call_started"] = True
        emit("call_start_requested", observed_transport=state["transport"])
        save()
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "start":
            sys.exit(74)
        state["voice_trace_id"] = state["current_voice_trace_id"]
        state["voice_trace_pointer_exists"] = True
        save()
        observed = os.environ.get("FAKE_ADB_OBSERVED_TRANSPORT", state["transport"])
        startup_probe = os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1"
        probe_timeline = os.environ.get("FAKE_ADB_PROBE_TIMELINE", "clean")
        independent_order_timeline = probe_timeline in {
            "call_active_before_attach",
            "call_active_before_attach_active",
        }
        if startup_probe and independent_order_timeline:
            for _ in range(4):
                emit("call_start_requested", observed_transport=state["transport"])
        attach_after_initial = startup_probe and independent_order_timeline
        if (
            startup_probe
            and probe_timeline != "missing_attach"
            and not attach_after_initial
        ):
            emit("remote_track_attached")
            if probe_timeline == "duplicate_attach":
                emit("remote_track_attached")
        if os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "call_active":
            emit("call_active", observed_transport=observed)
            if probe_timeline == "duplicate_call_active":
                emit("call_active", observed_transport=observed)
        if startup_probe and probe_timeline != "missing" and not attach_after_initial:
            if probe_timeline == "malformed":
                emit(
                    "playback_written",
                    playback_epoch=1,
                    byte_count=480,
                    audio_window_micros=5_000,
                )
            else:
                emit_probe_audio(False)
                if probe_timeline == "gap":
                    advance_event_time(300)
                    emit_probe_audio(False)
                elif probe_timeline == "active":
                    emit_probe_audio(True)
            if probe_timeline == "detached":
                emit("remote_track_detached", playback_epoch=1)
        if values.get("captureFixtureToken") == state.get("fixture_token"):
            state["injections"] = 1
            fixture_bytes = state["fixture_initial_bytes"]
            if startup_probe:
                emit("remote_audio_first_non_silent", playback_epoch=1)
                emit("playback_active", playback_epoch=1)
            emit("injection_started", byte_count=fixture_bytes)
            emit("injection_first_chunk", byte_count=fixture_bytes)
            emit("injection_completed", byte_count=fixture_bytes)
            emit("prompt_ended", byte_count=fixture_bytes)
            if os.environ.get("FAKE_ADB_SUPPRESS_CAPTURE_ATTESTATION") != "1":
                emit("capture_attested", capture_source="fixture", mic_bytes=0, fixture_bytes=fixture_bytes)
            if attach_after_initial:
                advance_event_time(300)
                emit("remote_track_attached")
                emit_probe_audio(probe_timeline == "call_active_before_attach_active")
            if not startup_probe:
                emit("remote_audio_first_non_silent", playback_epoch=1)
                emit("playback_active", playback_epoch=1)
        save()
    else:
        state["call_end_clock"] = fake_clock_value()
        state["call_started"] = False
        if state["run_state"] == "active":
            emit("call_stopped", succeeded=True)
        save()
    print("Starting service: Intent")
elif tail == ["shell", "dumpsys", "activity", "services", "me.rerere.rikkahub.debug"]:
    if state["call_started"]:
        print("me.rerere.rikkahub.voiceagent.VoiceAgentCallService")
elif tail[:3] == ["shell", "am", "broadcast"]:
    action = tail[tail.index("-a") + 1]
    values = extras()
    if action.endswith(".PREPARE"):
        state.update({
            "run_state": "active",
            "run_hash": values["run_hash"],
            "comparison_hash": values["comparison_hash"],
            "transport": values["transport"],
            "lifecycle": values["lifecycle"],
            "event_count": 0,
            "event_time_ms": 0,
            "events": [],
            "injections": 0,
        })
        emit("run_prepared")
        emit("lifecycle_requested", lifecycle=state["lifecycle"])
        if os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "stale":
            emit("lifecycle_observed", lifecycle=state["lifecycle"])
        if os.environ.get("FAKE_ADB_ROUTE_MODE") == "stale":
            emit("route_observed", route=state["route"].capitalize())
        save()
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "prepare_foreign":
            state.update({
                "run_state": "active",
                "run_hash": "sha256:" + "c" * 64,
                "comparison_hash": "sha256:" + "d" * 64,
                "transport": "livekit_experimental",
            })
            save()
            sys.exit(81)
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "prepare":
            sys.exit(76)
        completed(0, "status=ok\naction=prepare")
    elif action.endswith(".STATUS"):
        if os.environ.get("FAKE_ADB_STATUS_MALFORMED") == "1":
            print("Broadcast completed: result=0")
            sys.exit(0)
        state["status_reads"] += 1
        app_network = os.environ.get("FAKE_ADB_APP_NETWORK", state["network"])
        if (
            os.environ.get("FAKE_ADB_BACKGROUND_NETWORK_BLOCKED") == "1"
            and not state.get("app_foreground")
            and not state.get("call_started")
        ):
            app_network = "none"
        cold_start = (
            os.environ.get("FAKE_ADB_STATUS_COLD_START") == "1"
            and state["status_reads"] == 1
        )
        if cold_start:
            app_network = "none"
        save()
        if state["run_state"] == "active":
            emit("network_observed", network=app_network, succeeded=True)
            save()
        completed(0, "\n".join([
            "status=ok",
            "action=status",
            f"run_state={state['run_state']}",
            f"run_hash={state['run_hash']}",
            f"comparison_hash={state['comparison_hash']}",
            f"requested_transport={state['transport']}",
            f"event_count={state['event_count']}",
            f"network={app_network}",
            f"validated={str(not cold_start and app_network != 'none').lower()}",
        ]))
    elif action.endswith(".ROUTE"):
        route = values["route"]
        route_mode = os.environ.get("FAKE_ADB_ROUTE_MODE", "immediate")
        if route_mode == "precommand_pair":
            emit("route_requested", route=route.capitalize())
            emit("route_observed", route=route.capitalize())
        emit("route_requested", route=route.capitalize())
        accepted = os.environ.get("FAKE_ADB_FAIL_MODE") != "route_rejected"
        if accepted and route_mode == "immediate":
            emit("route_observed", route=route.capitalize())
        elif accepted and route_mode in {"delayed", "conflicting"}:
            state["route_pending"] = route_mode
            state["route_requested_value"] = route
        save()
        completed(0, f"status=ok\naction=route\nroute={route}\naccepted={str(accepted).lower()}")
    elif action.endswith(".MARK"):
        boundary = values["boundary"]
        if values.get("run_hash") != state["run_hash"]:
            completed(1, "status=error\nerror=invalid_state")
            sys.exit(0)
        if boundary == "handover_cellular_observed":
            emit(boundary, network="cellular")
        elif boundary == "handover_wifi_restored":
            emit(boundary, network="wifi")
        else:
            emit(boundary)
        if boundary == "handover_started":
            state["handover_started"] = True
        if boundary == "handover_wifi_restored":
            state["handover_wifi_restored"] = True
        save()
        completed(0, f"status=ok\naction=mark\nboundary={boundary}")
    elif action.endswith(".FINALIZE"):
        if state["run_state"] != "active":
            completed(1, "status=error\nerror=invalid_state")
        else:
            probe_timeline = os.environ.get("FAKE_ADB_PROBE_TIMELINE", "clean")
            if os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1":
                parsed_events = [json.loads(line) for line in state["events"]]
                if probe_timeline == "missing_call_active":
                    parsed_events = [
                        event for event in parsed_events if event.get("name") != "call_active"
                    ]
                elif probe_timeline in {"late_call_active", "late_attach"}:
                    initial_start = next(
                        event["monotonicMs"] for event in parsed_events
                        if event.get("name") == "injection_started"
                        and event.get("byteCount") == state["fixture_initial_bytes"]
                    )
                    prompt_start = max(
                        event["monotonicMs"] for event in parsed_events
                        if event.get("name") == "injection_started"
                    )
                    target_name = (
                        "call_active" if probe_timeline == "late_call_active"
                        else "remote_track_attached"
                    )
                    target_ms = initial_start + 1 if target_name == "call_active" else prompt_start
                    for event in parsed_events:
                        if event.get("name") == target_name:
                            event["monotonicMs"] = target_ms
                            event["wallClockMs"] = 1_800_000_000_000 + target_ms
                state["events"] = [
                    json.dumps(event, separators=(",", ":")) for event in parsed_events
                ]
            emit("run_finalized")
            state["run_state"] = "finalized"
            save()
            completed(0, "status=ok\naction=finalize")
    elif action.endswith(".ARM_CAPTURE_FIXTURE"):
        if os.environ.get("FAKE_ADB_ARM_MALFORMED") == "1":
            completed(0, "status=ok\naction=arm")
            sys.exit(0)
        state["fixture_token"] = "fixture-1"
        state["fixture_initial_bytes"] = state["staged"].get("files/" + values["initial_path"], 0)
        save()
        completed(0, "status=ok\naction=arm\ntoken=fixture-1")
    elif action.endswith(".TRIGGER_CAPTURE_FIXTURE"):
        if values.get("token") != state.get("fixture_token"):
            completed(1, "status=error\nerror=invalid_request")
            sys.exit(0)
        if (
            os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1"
            and not state.get("initial_attestation_observed_by_runner")
        ):
            completed(1, "status=error\nerror=invalid_request")
            sys.exit(0)
        if os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1":
            calls_file = Path(os.environ["FAKE_CLOCK_COUNTER"] + ".calls")
            clock_calls = int(calls_file.read_text()) if calls_file.exists() else 0
            if clock_calls - state["attestation_clock_calls"] < 6:
                completed(1, "status=error\nerror=invalid_request")
                sys.exit(0)
        staged_bytes = state["staged"].get("files/" + values.get("path", ""), 0)
        state["injections"] += 1
        startup_probe = os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1"
        probe_timeline = os.environ.get("FAKE_ADB_PROBE_TIMELINE", "clean")
        if startup_probe and probe_timeline != "missing":
            emit_probe_audio(False)
        emit("injection_started", byte_count=staged_bytes)
        if startup_probe and probe_timeline == "straddle":
            emit_probe_audio(True, 15_000)
        elif startup_probe and probe_timeline == "prompt_overlap":
            emit_probe_audio(True)
        emit("injection_first_chunk", byte_count=staged_bytes)
        emit("injection_completed", byte_count=staged_bytes)
        emit("prompt_ended", byte_count=staged_bytes)
        emit("capture_attested", capture_source="fixture", mic_bytes=0, fixture_bytes=staged_bytes)
        if startup_probe and probe_timeline not in {"missing_response", "legacy_only"}:
            emit_probe_audio(True)
        if not startup_probe:
            emit("playback_stopped", playback_epoch=1)
        save()
        completed(0, "status=ok\naction=trigger\naccepted=true")
    else:
        print(f"unexpected broadcast action: {action}", file=sys.stderr)
        sys.exit(91)
elif tail[:5] == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "grep", "-F"]:
    pattern = tail[5]
    state["event_grep_reads"][pattern] = state["event_grep_reads"].get(pattern, 0) + 1
    save()
    if (
        os.environ.get("FAKE_ADB_EMPTY_CALL_ACTIVE_SNAPSHOT_ONCE") == "1"
        and '"name":"call_active"' in pattern
        and state["event_grep_reads"][pattern] == 2
    ):
        save()
        sys.exit(0)
    if (
        os.environ.get("FAKE_ADB_FOREIGN_CALL_ACTIVE_SNAPSHOT_ONCE") == "1"
        and '"name":"call_active"' in pattern
        and state["event_grep_reads"][pattern] == 2
    ):
        matches = [json.loads(line) for line in state["events"] if pattern in line]
        for event in matches:
            event["runHash"] = "sha256:" + "c" * 64
            print(json.dumps(event, separators=(",", ":")))
        sys.exit(0)
    if '"route":' in pattern and state.get("route_pending"):
        requested = state["route_requested_value"]
        observed = requested if state["route_pending"] == "delayed" else (
            "earpiece" if requested == "speaker" else "speaker"
        )
        emit("route_observed", route=observed.capitalize())
        state.pop("route_pending", None)
        save()
    if ("playback_written" in pattern and state.get("recovery_ready") and
            not state.get("recovery_emitted") and
            os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "playback_written"):
        emit(
            "playback_written",
            playback_epoch=1,
            byte_count=3200,
            rms_active=False,
            audio_window_micros=10_000,
        )
        if state.get("handover_wifi_restored"):
            emit("handover_media_restored", playback_epoch=1)
        if state.get("reconnect_transport_restored"):
            emit("reconnect_media_restored", playback_epoch=1)
        state["recovery_emitted"] = True
        save()
    matches = [line for line in state["events"] if pattern in line]
    if os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1" and '"schemaVersion":1' in pattern:
        initial_bytes = state.get("fixture_initial_bytes")
        observed = any(
            json.loads(line).get("name") == "capture_attested"
            and json.loads(line).get("fixtureBytes") == initial_bytes
            for line in matches
        )
        if observed and not state.get("initial_attestation_observed_by_runner"):
            calls_file = Path(os.environ["FAKE_CLOCK_COUNTER"] + ".calls")
            state["initial_attestation_observed_by_runner"] = True
            state["attestation_clock_calls"] = (
                int(calls_file.read_text()) if calls_file.exists() else 0
            )
            save()
    if not matches:
        sys.exit(1)
    print("\n".join(matches))
elif tail == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "cat", "no_backup/voice-e2e/latest-trace-id.txt"]:
    fail_trace_pointer_read_if_requested()
    print(state["voice_trace_id"])
elif (tail[:4] == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "cat"] and
      tail[-1].endswith("/voice-experience-events.ndjson")):
    fail_if_requested("voice_events_read")
    if not state["voice_events_exists"]:
        raise SystemExit(1)
    print(state["voice_events"], end="")
elif tail[:4] == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "cat"]:
    print("\n".join(state["events"]))
else:
    print(f"unexpected adb args: {args!r}", file=sys.stderr)
    sys.exit(99)
PY
chmod +x "$BIN_DIR/adb"

cat > "$BIN_DIR/fake-clock" <<'PY'
#!/usr/bin/env python3
import json
import os
from pathlib import Path

log_file = Path(os.environ["FAKE_CLOCK_LOG"])
with log_file.open("ab") as handle:
    handle.write(b"clock\0__END__\0")
counter = Path(os.environ["FAKE_CLOCK_COUNTER"])
value = int(counter.read_text()) if counter.exists() else 0
calls_file = Path(str(counter) + ".calls")
calls = int(calls_file.read_text()) + 1 if calls_file.exists() else 1
calls_file.write_text(str(calls))
mode = os.environ.get("FAKE_CLOCK_MODE", "forward")
state_path = Path(os.environ["FAKE_ADB_STATE_DIR"]) / "state.json"
state = json.loads(state_path.read_text()) if state_path.exists() else {}
if mode == "probe_frozen" and state.get("initial_attestation_observed_by_runner"):
    value = value
elif mode == "probe_backward" and state.get("initial_attestation_observed_by_runner"):
    value -= 1
elif mode == "frozen":
    value = 100 if calls <= 20 else 1000
elif mode == "backward":
    value = 110 - calls * 10 if calls <= 10 else 1000
else:
    value += int(os.environ.get("FAKE_CLOCK_STEP", "30"))
counter.write_text(str(value))
print(value)
PY
chmod +x "$BIN_DIR/fake-clock"

cat > "$BIN_DIR/forbidden-external" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "${0##*/}" >> "$FAKE_EXTERNAL_LOG"
exit 97
SH
chmod +x "$BIN_DIR/forbidden-external"
for external_command in curl wget ssh scp rsync gradle lk kubectl; do
  ln -s forbidden-external "$BIN_DIR/$external_command"
done

export FAKE_REAL_MKDIR="$(command -v mkdir)"
export FAKE_REAL_MKTEMP="$(command -v mktemp)"
export FAKE_REAL_CHMOD="$(command -v chmod)"
export FAKE_REAL_LN="$(command -v ln)"
export FAKE_REAL_RM="$(command -v rm)"
export FAKE_REAL_WC="$(command -v wc)"
cat > "$BIN_DIR/private-file-command" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
command_name="${0##*/}"
if [[ "${FAKE_PRIVATE_FILE_FAIL:-}" == "$command_name" && "$*" == *PRIVATE-OUTPUT-SENTINEL* ]]; then
  printf '%s %s\n' "$command_name" "$*" >&2
  exit 88
fi
if [[ "$command_name" == "rm" && -n "${FAKE_PRIVATE_REMOVE_FAIL:-}" ]]; then
  private_path="${@: -1}"
  case "$private_path:$FAKE_PRIVATE_REMOVE_FAIL" in
    */.voice-stage1-events.*:automation|*/.voice-stage1-transcript.*:transcript)
      printf 'PRIVATE-RM-FAIL-SENTINEL %s\n' "$private_path" >&2
      exit 88
      ;;
  esac
fi
if [[ "$command_name" == "ln" && -n "${FAKE_PRIVATE_PUBLISH_RACE:-}" ]]; then
  source_path="${@: -2:1}"
  destination="${@: -1}"
  case "$source_path:$FAKE_PRIVATE_PUBLISH_RACE" in
    */.voice-stage1-events.*:automation|*/.voice-stage1-transcript.*:transcript)
      if [[ ! -e "$destination" && ! -L "$destination" ]]; then
        case "${FAKE_PRIVATE_PUBLISH_RACE_KIND:-regular}" in
          regular)
            printf 'synthetic-late-destination\n' > "$destination"
            "$FAKE_REAL_CHMOD" 640 "$destination"
            ;;
          directory)
            "$FAKE_REAL_MKDIR" -- "$destination"
            printf 'synthetic-preserved-entry\n' > "$destination/preserved-entry"
            "$FAKE_REAL_CHMOD" 750 "$destination"
            "$FAKE_REAL_CHMOD" 640 "$destination/preserved-entry"
            ;;
          directory_symlink)
            "$FAKE_REAL_LN" -s -- "$FAKE_PRIVATE_PUBLISH_RACE_SYMLINK_TARGET" "$destination"
            ;;
          *)
            exit 89
            ;;
        esac
      fi
      ;;
  esac
fi
if [[ "$command_name" == "chmod" && -n "${FAKE_PRIVATE_REOPEN_FAIL:-}" ]]; then
  private_path="${@: -1}"
  case "$private_path:$FAKE_PRIVATE_REOPEN_FAIL" in
    */.voice-stage1-events.*:automation|*/.voice-stage1-transcript.*:transcript)
      "$FAKE_REAL_CHMOD" "$@"
      "$FAKE_REAL_RM" -f -- "$private_path"
      "$FAKE_REAL_LN" -s -- "$FAKE_PRIVATE_REOPEN_FAILURE_TARGET" "$private_path"
      printf 'injected\n' > "$FAKE_PRIVATE_REOPEN_MARKER"
      exit 0
      ;;
  esac
fi
if [[ "$command_name" == "chmod" && -n "${FAKE_PRIVATE_SIGNAL_AFTER_CHMOD:-}" ]]; then
  private_path="${@: -1}"
  signal_family="${FAKE_PRIVATE_SIGNAL_AFTER_CHMOD#*:}"
  signal_name="${FAKE_PRIVATE_SIGNAL_AFTER_CHMOD%%:*}"
  case "$private_path:$signal_family" in
    */.voice-stage1-events.*:automation|*/.voice-stage1-transcript.*:transcript)
      "$FAKE_REAL_CHMOD" "$@"
      printf 'injected\n' > "$FAKE_PRIVATE_SIGNAL_MARKER"
      kill -s "$signal_name" "$PPID"
      exit 0
      ;;
  esac
fi
case "$command_name" in
  mkdir) real_command="$FAKE_REAL_MKDIR" ;;
  mktemp) real_command="$FAKE_REAL_MKTEMP" ;;
  chmod) real_command="$FAKE_REAL_CHMOD" ;;
  ln) real_command="$FAKE_REAL_LN" ;;
  rm) real_command="$FAKE_REAL_RM" ;;
  *) exit 89 ;;
esac
exec "$real_command" "$@"
SH
chmod +x "$BIN_DIR/private-file-command"
for private_command in mkdir mktemp chmod ln rm; do
  ln -s private-file-command "$BIN_DIR/$private_command"
done
cat > "$BIN_DIR/wc" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_WC_FAIL_WHEN_FINALIZED:-}" == "1" ]] &&
   python3 - "$FAKE_ADB_STATE_DIR/state.json" <<'PY'
import json
import os
import sys

try:
    state = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    raise SystemExit(1)
raise SystemExit(0 if state.get("run_state") == "finalized" else 1)
PY
then
  printf '%s\n' 'PRIVATE-FIXTURE-SIZE-PATH' >&2
  exit 88
fi
exec "$FAKE_REAL_WC" "$@"
SH
chmod +x "$BIN_DIR/wc"
export PATH="$BIN_DIR:$PATH"
export FAKE_EXTERNAL_LOG="$EXTERNAL_LOG"

reset_fake() {
  rm -rf "$STATE_DIR"
  mkdir -p "$STATE_DIR"
  : > "$ADB_LOG"
  : > "$CLOCK_LOG"
  : > "$EXTERNAL_LOG"
  rm -f "$TMP_DIR/clock-counter" "$TMP_DIR/clock-counter.calls"
  rm -f "$LOCK_DIR"/*
  unset FAKE_ADB_DEVICES_MODE FAKE_ADB_FAIL_MODE FAKE_ADB_OBSERVED_TRANSPORT
  unset FAKE_ADB_APP_NETWORK FAKE_ADB_SUPPRESS_EVENT FAKE_ADB_EMULATOR
  unset FAKE_ADB_ROUTE_MODE FAKE_ADB_LIFECYCLE_MODE FAKE_CLOCK_MODE FAKE_ADB_INITIAL_RUN
  unset FAKE_ADB_UNVALIDATED_AFTER_RESTORE FAKE_ADB_STATUS_COLD_START
  unset FAKE_ADB_BACKGROUND_NETWORK_BLOCKED
  unset FAKE_ADB_STATUS_MALFORMED FAKE_ADB_STAGE_VISIBILITY_DELAY
  unset FAKE_ADB_ARM_MALFORMED FAKE_ADB_SUPPRESS_CAPTURE_ATTESTATION
  unset FAKE_ADB_PROBE_TIMELINE FAKE_CLOCK_STEP FAKE_ADB_TRACE_SNAPSHOT_CLOCK_ADVANCE
  unset FAKE_ADB_INITIAL_VOICE_TRACE_ID FAKE_ADB_INITIAL_VOICE_TRACE_ABSENT
  unset FAKE_ADB_CURRENT_VOICE_TRACE_ID FAKE_ADB_VOICE_EVENTS
  unset FAKE_ADB_VOICE_EVENTS_EXISTS FAKE_ADB_VOICE_EVENTS_REGULAR
  unset FAKE_ADB_VOICE_EVENTS_SYMLINK FAKE_ADB_NOISY_PRIVATE_FAILURE
  unset FAKE_PRIVATE_FILE_FAIL FAKE_PRIVATE_PUBLISH_RACE
  unset FAKE_PRIVATE_PUBLISH_RACE_KIND FAKE_PRIVATE_PUBLISH_RACE_SYMLINK_TARGET
  unset FAKE_PRIVATE_REOPEN_FAIL
  unset FAKE_PRIVATE_REOPEN_FAILURE_TARGET FAKE_PRIVATE_REOPEN_MARKER
  unset FAKE_PRIVATE_REMOVE_FAIL FAKE_PRIVATE_SIGNAL_AFTER_CHMOD
  unset FAKE_PRIVATE_SIGNAL_MARKER
  unset VOICE_STAGE1_TEST_EVENT_OUTPUT
  unset FAKE_WC_FAIL_WHEN_FINALIZED
  unset VOICE_STAGE1_TEST_MAX_WAIT_ATTEMPTS
  unset VOICE_STAGE1_TRANSCRIPT_PROBE VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT
  unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH
  unset VOICE_STAGE1_WORKER_LOG_PATH VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS
}

command_lines() {
  python3 - "$ADB_LOG" <<'PY'
import sys
raw = open(sys.argv[1], "rb").read().split(b"\0")
current = []
for field in raw:
    if not field:
        continue
    value = field.decode()
    if value == "__END__":
        print("\x1f".join(current))
        current = []
    else:
        current.append(value)
PY
}

commands_matching() {
  local needle="$1"
  command_lines | awk -v needle="$needle" 'index($0, needle) { print }'
}

command_count() {
  local needle="$1"
  commands_matching "$needle" | awk 'END { print NR + 0 }'
}

command_index() {
  local needle="$1"
  command_lines | awk -v needle="$needle" '
    !found && index($0, needle) { found = NR }
    END { if (found) print found }
  '
}

last_command_index() {
  local needle="$1"
  command_lines | awk -v needle="$needle" 'index($0, needle) { found = NR } END { print found }'
}

assert_no_adb_mutations() {
  local commands
  local separator=$'\x1f'
  commands="$(command_lines)"
  assert_not_contains "$commands" "shell${separator}svc${separator}"
  assert_not_contains "$commands" "shell${separator}am${separator}"
  assert_not_contains "$commands" "exec-in${separator}"
  assert_not_contains "$commands" "run-as${separator}${PACKAGE}${separator}mkdir"
  assert_not_contains "$commands" "run-as${separator}${PACKAGE}${separator}rm"
}

assert_only_allowlisted_stage1_artifacts() {
  python3 - "$ADB_LOG" <<'PY'
import re
import sys

fields = open(sys.argv[1], "rb").read().split(b"\0")
allowed = re.compile(
    r"^no_backup/voice-e2e/(?:latest-trace-id\.txt|"
    r"[A-Za-z0-9_-]{1,128}/(?:automation-events\.jsonl|voice-experience-events\.ndjson))$"
)
seen = {
    "latest-trace-id.txt": False,
    "automation-events.jsonl": False,
    "voice-experience-events.ndjson": False,
}
for raw in fields:
    if not raw or raw == b"__END__":
        continue
    value = raw.decode("utf-8")
    if not value.startswith("no_backup/voice-e2e"):
        continue
    if allowed.fullmatch(value) is None:
        raise SystemExit("non-allowlisted Stage 1 artifact path observed")
    for name in seen:
        if value.endswith("/" + name):
            seen[name] = True
if not all(seen.values()):
    raise SystemExit("allowlisted Stage 1 artifact coverage was incomplete")
PY
}

assert_private_path_absent() {
  local path="$1"
  python3 - "$STATE_DIR/state.json" "$path" <<'PY'
import json
import sys
state = json.load(open(sys.argv[1]))
if sys.argv[2] in state.get("staged", {}):
    raise SystemExit(f"private path still staged: {sys.argv[2]}")
PY
}

count_wifi_enables_after_last_disable() {
  local separator=$'\x1f'
  command_lines | awk -v disable="svc${separator}wifi${separator}disable" \
    -v enable="svc${separator}wifi${separator}enable" '
      index($0, disable) { count = 0; seen = 1; next }
      seen && index($0, enable) { count++ }
      END { print count + 0 }
    '
}

assert_selected_serial() {
  python3 - "$ADB_LOG" "$SERIAL" <<'PY'
import sys
fields = open(sys.argv[1], "rb").read().split(b"\0")
commands, current = [], []
for raw in fields:
    if not raw:
        continue
    value = raw.decode()
    if value == "__END__":
        commands.append(current)
        current = []
    else:
        current.append(value)
for command in commands:
    if command == ["devices", "-l"]:
        continue
    if command[:2] != ["-s", sys.argv[2]]:
        raise SystemExit(f"unselected command: {command!r}")
PY
}

runner_env() {
  env \
    PATH="$BIN_DIR:$PATH" \
    FAKE_ADB_STATE_DIR="$STATE_DIR" \
    FAKE_ADB_LOG="$ADB_LOG" \
    FAKE_CLOCK_LOG="$CLOCK_LOG" \
    FAKE_CLOCK_COUNTER="$TMP_DIR/clock-counter" \
    VOICE_STAGE1_CLOCK_COMMAND="$BIN_DIR/fake-clock" \
    VOICE_STAGE1_POLL_SECONDS=0 \
    VOICE_STAGE1_ADB_TIMEOUT_SECONDS=5 \
    VOICE_STAGE1_WAIT_TIMEOUT_SECONDS=120 \
    VOICE_STAGE1_MAX_WAIT_ATTEMPTS="${VOICE_STAGE1_TEST_MAX_WAIT_ATTEMPTS:-8}" \
    VOICE_STAGE1_LOCK_DIR="$LOCK_DIR" \
    VOICE_STAGE1_SERIAL="$SERIAL" \
    VOICE_STAGE1_PACKAGE="$PACKAGE" \
    "$@"
}

write_transcript_fixture() {
  local scenario="$1"
  local output="$2"
  python3 - "$scenario" "$output" <<'PY'
import json
import os
import sys
from pathlib import Path

scenario = sys.argv[1]
output = Path(sys.argv[2])
hash_a = "sha256:" + "a" * 64
hash_b = "sha256:" + "b" * 64
hash_c = "sha256:" + "c" * 64
timestamp = "2026-07-30T12:00:00Z"


def base(kind, event_id):
    return {
        "version": 1,
        "voiceSessionId": "fixture-session",
        "eventId": event_id,
        "kind": kind,
        "observedAt": timestamp,
        "eventHash": hash_a,
    }


def job(kind, event_id):
    row = base(kind, event_id)
    row.update({
        "userTurnId": "turn-job",
        "requestHash": hash_b,
        "toolCallId": "tool-job",
        "argumentHash": hash_c,
        "jobId": "job-fixed",
    })
    if kind == "job_accepted":
        row["promptCharacterCount"] = 17
    elif kind == "job_succeeded":
        row.update({"resultHash": hash_b, "answerCharacterCount": 23})
    elif kind in {"job_failed", "job_expired", "job_canceled"}:
        row["failureReasonCharacterCount"] = 11
    return row


def transcript(event_id, role, interrupted, count):
    row = base("transcript", event_id)
    row.update({
        "turnId": "turn-transcript",
        "role": role,
        "interrupted": interrupted,
        "textCharacterCount": count,
    })
    return row


def valid_kind(kind):
    if kind.startswith("job_") or kind == "still_working":
        return job(kind, f"event-{kind}")
    if kind in {"delivery_eligible", "delivery_started", "speech_started"}:
        row = base(kind, f"event-{kind}")
        row.update({"toolCallId": "tool-delivery", "jobId": "job-delivery"})
        return row
    if kind == "delivery_blocked":
        row = base(kind, "event-delivery-blocked")
        row.update({
            "toolCallId": "tool-delivery",
            "jobId": "job-delivery",
            "userSpeaking": False,
            "agentSpeaking": True,
        })
        return row
    if kind == "delivery_announced":
        row = base(kind, "event-delivery-announced")
        row.update({
            "toolCallId": "tool-delivery",
            "jobId": "job-delivery",
            "assistantTurnId": "turn-assistant",
        })
        return row
    if kind == "follow_up_correlation":
        row = base(kind, "event-follow-up")
        row.update({
            "followUpTurnId": "turn-follow-up",
            "assistantTurnId": "turn-assistant",
            "resultHash": hash_c,
        })
        return row
    raise ValueError("unsupported fixture kind")


if scenario == "empty":
    output.write_bytes(b"")
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "malformed_json":
    output.write_text('{"sentinel":"fixture-private-sentinel"\n')
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "non_object":
    output.write_text('["fixture-private-sentinel"]\n')
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "invalid_utf8":
    output.write_bytes(b'\xff\n')
    os.chmod(output, 0o600)
    raise SystemExit(0)

if scenario == "final_user":
    rows = [transcript("event-final-user", "user", False, 17)]
elif scenario == "interrupted_user":
    rows = [transcript("event-interrupted-user", "user", True, 17)]
elif scenario == "assistant":
    row = transcript("event-assistant", "assistant", False, 23)
    row.update({"groundedJobId": "job-fixed", "groundedResultHash": hash_b})
    rows = [row]
elif scenario == "job":
    rows = [job("job_accepted", "event-job")]
elif scenario.startswith("valid_"):
    rows = [valid_kind(scenario.removeprefix("valid_"))]
elif scenario == "mixed_session":
    first = job("job_running", "event-mixed-one")
    second = job("job_running", "event-mixed-two")
    second["voiceSessionId"] = "other-session"
    rows = [first, second]
elif scenario == "duplicate_event":
    rows = [
        job("job_running", "event-duplicate"),
        job("still_working", "event-duplicate"),
    ]
elif scenario == "unknown_kind":
    rows = [base("unknown_kind", "event-unknown")]
elif scenario == "bad_type":
    row = transcript("event-bad-type", "user", False, 17)
    row["version"] = True
    rows = [row]
elif scenario == "bad_schema":
    row = transcript("event-bad-schema", "user", False, 17)
    row["sentinel"] = "fixture-private-sentinel"
    rows = [row]
elif scenario == "missing_key":
    row = job("job_running", "event-missing-key")
    del row["requestHash"]
    rows = [row]
elif scenario == "qualifying_then_invalid":
    rows = [transcript("event-first-valid", "user", False, 17)]
    output.write_text(
        json.dumps(rows[0], separators=(",", ":"))
        + '\n{"sentinel":"fixture-private-sentinel"\n'
    )
    os.chmod(output, 0o600)
    raise SystemExit(0)
elif scenario == "unterminated_complete":
    rows = [transcript("event-final-user", "user", False, 17)]
    output.write_text(json.dumps(rows[0], separators=(",", ":")))
    os.chmod(output, 0o600)
    raise SystemExit(0)
elif scenario == "null_value":
    row = job("job_running", "event-null")
    row["jobId"] = None
    rows = [row]
elif scenario == "bool_count":
    row = transcript("event-bool-count", "user", False, 17)
    row["textCharacterCount"] = True
    rows = [row]
elif scenario == "bad_boolean":
    row = transcript("event-bad-boolean", "assistant", False, 17)
    row["interrupted"] = 0
    rows = [row]
elif scenario == "negative_count":
    row = job("job_accepted", "event-negative-count")
    row["promptCharacterCount"] = -1
    rows = [row]
elif scenario == "zero_final_user":
    rows = [transcript("event-zero-final", "user", False, 0)]
elif scenario == "bad_identifier":
    row = job("job_running", "event-bad-identifier")
    row["jobId"] = "bad/job"
    rows = [row]
elif scenario == "bad_hash":
    row = job("job_running", "event-bad-hash")
    row["requestHash"] = "sha256:abcd"
    rows = [row]
elif scenario == "bad_timestamp":
    row = job("job_running", "event-bad-timestamp")
    row["observedAt"] = "2026-07-30T12:00:00+00:00"
    rows = [row]
elif scenario == "lossy_timestamp":
    row = job("job_running", "event-lossy-timestamp")
    row["observedAt"] = "2026-07-30T12:00:00.123456789Z"
    rows = [row]
elif scenario == "bad_role":
    rows = [transcript("event-bad-role", "system", False, 17)]
elif scenario == "half_grounded":
    row = transcript("event-half-grounded", "assistant", False, 17)
    row["groundedJobId"] = "job-fixed"
    rows = [row]
elif scenario == "grounded_user":
    row = transcript("event-grounded-user", "user", False, 17)
    row.update({"groundedJobId": "job-fixed", "groundedResultHash": hash_b})
    rows = [row]
else:
    raise ValueError("unsupported fixture scenario")

output.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows))
os.chmod(output, 0o600)
PY
}

ANDROID_SNAPSHOT_FAULT_DIR="$TMP_DIR/android-snapshot-fault"
mkdir -p "$ANDROID_SNAPSHOT_FAULT_DIR"
cat > "$ANDROID_SNAPSHOT_FAULT_DIR/sitecustomize.py" <<'PY'
import os

mode = os.environ.get("FAKE_ANDROID_SNAPSHOT_CHANGE")
target = os.path.abspath(os.environ.get("FAKE_ANDROID_SNAPSHOT_TARGET", ""))
marker = os.environ.get("FAKE_ANDROID_SNAPSHOT_MARKER")
real_fstat = os.fstat
target_fstat_calls = 0
injected = False


def inject_change():
    if mode == "mutation":
        with open(target, "r+b", buffering=0) as handle:
            content = handle.read()
            needle = b"event-final-user"
            replacement = b"event-xinal-user"
            offset = content.index(needle)
            handle.seek(offset)
            handle.write(replacement)
            os.fsync(handle.fileno())
    elif mode == "replacement":
        with open(target, "rb") as source:
            content = source.read()
        replacement_path = target + ".replacement"
        descriptor = os.open(
            replacement_path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        with os.fdopen(descriptor, "wb") as replacement:
            replacement.write(content)
            replacement.flush()
            os.fsync(replacement.fileno())
        os.replace(replacement_path, target)
    elif mode == "truncation":
        os.truncate(target, max(0, os.path.getsize(target) - 1))
    else:
        raise RuntimeError("unsupported Android snapshot fault")
    with open(marker, "w", encoding="utf-8") as marker_file:
        marker_file.write("injected\n")


def injected_fstat(descriptor):
    global injected, target_fstat_calls
    if mode and not injected:
        try:
            opened_path = os.path.realpath(f"/proc/self/fd/{descriptor}")
        except OSError:
            opened_path = ""
        if opened_path == target:
            target_fstat_calls += 1
            if target_fstat_calls == 2:
                injected = True
                inject_change()
    return real_fstat(descriptor)


if mode:
    os.fstat = injected_fstat
PY

write_worker_log_fixture() {
  local scenario="$1"
  local output="$2"
  python3 - "$scenario" "$output" <<'PY'
import json
import os
import sys
from pathlib import Path

scenario = sys.argv[1]
output = Path(sys.argv[2])
agent = "rikka-livekit-experimental"
job = "worker-private-job"
room = "worker-private-room"
other_job = "worker-private-other-job"
other_room = "worker-private-other-room"


def request(selected_job=job, selected_room=room, selected_agent=agent):
    return {
        "message": "received job request",
        "agent_name": selected_agent,
        "job_id": selected_job,
        "room": selected_room,
    }


def ready(selected_job=job, selected_room=room):
    return {
        "message": "voice_session_ready",
        "job_id": selected_job,
        "room": selected_room,
    }


def marker(selected_job=job, selected_room=room):
    return {
        "message": "voice_user_transcript_final",
        "job_id": selected_job,
        "room": selected_room,
    }


if scenario == "malformed_json":
    output.write_text('{"message":"worker-private-sentinel"\n')
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "valid_then_malformed":
    rows = [request(), ready(), marker()]
    output.write_text(
        "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)
        + '{"message":"worker-private-sentinel"\n'
    )
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "unterminated_complete":
    rows = [request(), ready()]
    output.write_text(
        "\n".join(json.dumps(row, separators=(",", ":")) for row in rows)
    )
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "duplicate_key":
    output.write_text(
        '{"message":"received job request","message":"worker-private-sentinel",'
        '"agent_name":"rikka-livekit-experimental","job_id":"worker-private-job",'
        '"room":"worker-private-room"}\n'
    )
    os.chmod(output, 0o600)
    raise SystemExit(0)
if scenario == "unstructured":
    rows = [["worker-private-sentinel"]]
elif scenario == "marker_present":
    rows = [request(), ready(), marker()]
elif scenario == "marker_absent":
    rows = [request(), ready()]
elif scenario == "marker_without_ready":
    rows = [request(), marker()]
elif scenario == "registration_only":
    rows = [{"message": "registered worker", "agent_name": agent}]
elif scenario == "missing_ready":
    rows = [request()]
elif scenario == "legacy_synthetic_ready":
    rows = [
        request(),
        {
            "message": "published ready topic",
            "agent_name": agent,
            "job_id": job,
            "room": room,
            "topic": "voice.ready.v1",
        },
    ]
elif scenario == "two_jobs":
    rows = [request(), ready(), request(other_job, other_room), ready(other_job, other_room)]
elif scenario == "mismatched_ready":
    rows = [request(), ready(other_job, other_room)]
elif scenario == "mismatched_marker":
    rows = [request(), ready(), marker(other_job, other_room)]
elif scenario == "marker_before_ready":
    rows = [request(), marker(), ready()]
elif scenario == "ready_before_request":
    rows = [ready(), request()]
elif scenario == "missing_attribution":
    row = request()
    del row["room"]
    rows = [row, ready()]
elif scenario == "truncated":
    output.write_text('{"message":"received job request"')
    os.chmod(output, 0o600)
    raise SystemExit(0)
else:
    raise ValueError("unsupported worker fixture scenario")

output.write_text(
    "\n" + "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)
)
os.chmod(output, 0o600)
PY
}

prepare_worker_log_fixture() {
  local scenario="$1"
  local output="$2"
  rm -f "$output" "$output.target"
  case "$scenario" in
    missing)
      ;;
    symlink)
      write_worker_log_fixture marker_present "$output.target"
      ln -s "$output.target" "$output"
      ;;
    unreadable)
      write_worker_log_fixture marker_present "$output"
      chmod 000 "$output"
      ;;
    wrong_mode)
      write_worker_log_fixture marker_present "$output"
      chmod 640 "$output"
      ;;
    *)
      write_worker_log_fixture "$scenario" "$output"
      ;;
  esac
}

run_transcript_classifier() {
  local fixture="$1"
  local worker_log="${2:-$WORKER_LOG}"
  local capture_status="${3:-unavailable}"
  VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$fixture" \
  VOICE_STAGE1_WORKER_LOG_PATH="$worker_log" \
  VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS="$capture_status" \
  bash "$RUNNER" --classify-transcript
}

transcript_file_record() {
  local classification="$1"
  local covered="$2"
  local rows="$3"
  local qualifying="$4"
  printf 'stage1.final_user_transcript_file={"version":1,"classification":"%s","collectionCovered":%s,"rowCount":%s,"qualifyingUserTranscriptCount":%s}' \
    "$classification" "$covered" "$rows" "$qualifying"
}

worker_log_record() {
  local classification="$1"
  local activity_count="$2"
  local marker_count="$3"
  printf 'stage1.worker_transcript_log={"version":1,"classification":"%s","sessionActivityCount":%s,"markerCount":%s}' \
    "$classification" "$activity_count" "$marker_count"
}

combined_transcript_record() {
  local classification="$1"
  local boundary="$2"
  printf 'stage1.final_user_transcript={"version":1,"classification":"%s","firstMissingBoundary":"%s"}' \
    "$classification" "$boundary"
}

assert_classifier_privacy() {
  local combined_output="$1"
  local transcript_fixture="$2"
  local worker_fixture="${3:-$WORKER_LOG}"
  assert_not_contains "$combined_output" "fixture-session"
  assert_not_contains "$combined_output" "other-session"
  assert_not_contains "$combined_output" "event-final-user"
  assert_not_contains "$combined_output" "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  assert_not_contains "$combined_output" "2026-07-30T12:00:00Z"
  assert_not_contains "$combined_output" "fixture-private-sentinel"
  assert_not_contains "$combined_output" "worker-private-job"
  assert_not_contains "$combined_output" "worker-private-room"
  assert_not_contains "$combined_output" "worker-private-sentinel"
  assert_not_contains "$combined_output" "received job request"
  assert_not_contains "$combined_output" "voice_session_ready"
  assert_not_contains "$combined_output" "voice_user_transcript_final"
  assert_not_contains "$combined_output" "$transcript_fixture"
  assert_not_contains "$combined_output" "$worker_fixture"
}

assert_transcript_file_result() {
  local scenario="$1"
  local classification="$2"
  local covered="$3"
  local rows="$4"
  local qualifying="$5"
  local fixture="$TRANSCRIPT_FIXTURE_DIR/$scenario.ndjson"
  local stdout_file="$TMP_DIR/classifier-stdout"
  local stderr_file="$TMP_DIR/classifier-stderr"
  local status
  rm -f "$fixture" "$fixture.target" "$stdout_file" "$stderr_file"
  reset_fake
  case "$scenario" in
    missing)
      ;;
    unreadable)
      write_transcript_fixture final_user "$fixture"
      chmod 000 "$fixture"
      ;;
    symlink)
      write_transcript_fixture final_user "$fixture.target"
      ln -s "$fixture.target" "$fixture"
      ;;
    wrong_mode)
      write_transcript_fixture final_user "$fixture"
      chmod 644 "$fixture"
      ;;
    *)
      write_transcript_fixture "$scenario" "$fixture"
      ;;
  esac
  if [[ -f "$fixture" && ! -L "$fixture" &&
        "$scenario" != "unreadable" && "$scenario" != "wrong_mode" ]]; then
    [[ "$(stat -c %a "$fixture")" == "600" ]] ||
      fail "Android fixture $scenario was not explicitly mode 0600"
  fi
  set +e
  run_transcript_classifier "$fixture" >"$stdout_file" 2>"$stderr_file"
  status=$?
  set -e
  local expected_status=3
  local combined_classification=unknown
  local combined_boundary=evidence-collection
  if [[ "$classification" == "present" ]]; then
    expected_status=0
    combined_classification=present
    combined_boundary=ask-hermes
  fi
  [[ "$status" -eq "$expected_status" ]] ||
    fail "classifier returned status $status for file scenario $scenario, expected $expected_status"
  assert_equals "$(cat <<EOF
$(transcript_file_record "$classification" "$covered" "$rows" "$qualifying")
$(worker_log_record unknown 0 0)
$(combined_transcript_record "$combined_classification" "$combined_boundary")
EOF
)" "$(cat "$stdout_file")"
  [[ ! -s "$stderr_file" ]] || fail "classifier emitted a non-fixed parser diagnostic for $scenario"
  [[ ! -s "$ADB_LOG" ]] || fail "classification mode reached ADB for $scenario"
  [[ ! -s "$EXTERNAL_LOG" ]] || fail "classification mode reached an external command for $scenario"
  assert_classifier_privacy "$(cat "$stdout_file" "$stderr_file")" "$fixture" "$WORKER_LOG"
}

assert_android_snapshot_change_result() {
  local change="$1"
  local fixture="$TRANSCRIPT_FIXTURE_DIR/snapshot-$change.ndjson"
  local marker="$TMP_DIR/android-snapshot-$change.injected"
  local stdout_file="$TMP_DIR/classifier-stdout"
  local stderr_file="$TMP_DIR/classifier-stderr"
  local status
  rm -f "$fixture" "$fixture.replacement" "$marker" "$stdout_file" "$stderr_file"
  reset_fake
  write_transcript_fixture final_user "$fixture"
  [[ "$(stat -c %a "$fixture")" == "600" ]] ||
    fail "Android snapshot $change fixture was not mode 0600"
  set +e
  FAKE_ANDROID_SNAPSHOT_CHANGE="$change" \
  FAKE_ANDROID_SNAPSHOT_TARGET="$fixture" \
  FAKE_ANDROID_SNAPSHOT_MARKER="$marker" \
  PYTHONPATH="$ANDROID_SNAPSHOT_FAULT_DIR${PYTHONPATH:+:$PYTHONPATH}" \
    run_transcript_classifier "$fixture" >"$stdout_file" 2>"$stderr_file"
  status=$?
  set -e
  [[ -s "$marker" ]] || fail "Android snapshot $change fault was not injected"
  [[ "$status" -eq 3 ]] ||
    fail "Android snapshot $change returned status $status, expected 3"
  assert_equals "$(cat <<EOF
$(transcript_file_record unknown false 0 0)
$(worker_log_record unknown 0 0)
$(combined_transcript_record unknown evidence-collection)
EOF
)" "$(cat "$stdout_file")"
  [[ ! -s "$stderr_file" ]] || fail "Android snapshot $change emitted a private diagnostic"
  [[ ! -s "$ADB_LOG" ]] || fail "Android snapshot $change reached ADB"
  [[ ! -s "$EXTERNAL_LOG" ]] || fail "Android snapshot $change reached an external command"
  assert_classifier_privacy "$(cat "$stdout_file" "$stderr_file")" "$fixture" "$WORKER_LOG"
}

assert_worker_result() {
  local scenario="$1"
  local capture_status="$2"
  local classification="$3"
  local activity_count="$4"
  local marker_count="$5"
  local combined_classification="$6"
  local combined_boundary="$7"
  local expected_status="$8"
  local transcript_fixture="$TRANSCRIPT_FIXTURE_DIR/worker-$scenario.ndjson"
  local worker_fixture="$TRANSCRIPT_FIXTURE_DIR/worker-$scenario.log"
  local stdout_file="$TMP_DIR/classifier-stdout"
  local stderr_file="$TMP_DIR/classifier-stderr"
  local status
  rm -f "$transcript_fixture" "$stdout_file" "$stderr_file"
  reset_fake
  write_transcript_fixture empty "$transcript_fixture"
  prepare_worker_log_fixture "$scenario" "$worker_fixture"
  set +e
  run_transcript_classifier "$transcript_fixture" "$worker_fixture" "$capture_status" \
    >"$stdout_file" 2>"$stderr_file"
  status=$?
  set -e
  [[ "$status" -eq "$expected_status" ]] ||
    fail "worker classifier returned status $status for $scenario, expected $expected_status"
  assert_equals "$(cat <<EOF
$(transcript_file_record unknown true 0 0)
$(worker_log_record "$classification" "$activity_count" "$marker_count")
$(combined_transcript_record "$combined_classification" "$combined_boundary")
EOF
)" "$(cat "$stdout_file")"
  [[ ! -s "$stderr_file" ]] || fail "worker classifier emitted a parser diagnostic for $scenario"
  [[ ! -s "$ADB_LOG" ]] || fail "worker classification reached ADB for $scenario"
  [[ ! -s "$EXTERNAL_LOG" ]] || fail "worker classification reached an external command for $scenario"
  assert_classifier_privacy "$(cat "$stdout_file" "$stderr_file")" \
    "$transcript_fixture" "$worker_fixture"
}

assert_combined_result() {
  local label="$1"
  local transcript_scenario="$2"
  local file_classification="$3"
  local file_covered="$4"
  local row_count="$5"
  local qualifying_count="$6"
  local worker_scenario="$7"
  local worker_classification="$8"
  local activity_count="$9"
  local marker_count="${10}"
  local combined_classification="${11}"
  local combined_boundary="${12}"
  local expected_status="${13}"
  local transcript_fixture="$TRANSCRIPT_FIXTURE_DIR/matrix-$label.ndjson"
  local worker_fixture="$TRANSCRIPT_FIXTURE_DIR/matrix-$label.log"
  local stdout_file="$TMP_DIR/classifier-stdout"
  local stderr_file="$TMP_DIR/classifier-stderr"
  local status
  rm -f "$transcript_fixture" "$stdout_file" "$stderr_file"
  reset_fake
  if [[ "$transcript_scenario" != "missing" ]]; then
    write_transcript_fixture "$transcript_scenario" "$transcript_fixture"
  fi
  prepare_worker_log_fixture "$worker_scenario" "$worker_fixture"
  set +e
  run_transcript_classifier "$transcript_fixture" "$worker_fixture" complete \
    >"$stdout_file" 2>"$stderr_file"
  status=$?
  set -e
  [[ "$status" -eq "$expected_status" ]] ||
    fail "combined classifier returned status $status for $label, expected $expected_status"
  assert_equals "$(cat <<EOF
$(transcript_file_record "$file_classification" "$file_covered" "$row_count" "$qualifying_count")
$(worker_log_record "$worker_classification" "$activity_count" "$marker_count")
$(combined_transcript_record "$combined_classification" "$combined_boundary")
EOF
)" "$(cat "$stdout_file")"
  [[ ! -s "$stderr_file" ]] || fail "combined classifier emitted a parser diagnostic for $label"
  [[ ! -s "$ADB_LOG" ]] || fail "combined classification reached ADB for $label"
  [[ ! -s "$EXTERNAL_LOG" ]] || fail "combined classification reached an external command for $label"
  assert_classifier_privacy "$(cat "$stdout_file" "$stderr_file")" \
    "$transcript_fixture" "$worker_fixture"
}

run_scenario() {
  local transport="$1"
  local network="$2"
  local route="$3"
  local app_state="$4"
  local lifecycle="$5"
  local target_seconds="$6"
  local output="${VOICE_STAGE1_TEST_EVENT_OUTPUT:-$TMP_DIR/automation-events.jsonl}"
  if [[ -z "${VOICE_STAGE1_TEST_EVENT_OUTPUT+x}" ]]; then
    rm -f "$output"
  fi
  runner_env \
    VOICE_STAGE1_CONVERSATION_ID=conversation-1 \
    VOICE_STAGE1_TRANSPORT="$transport" \
    VOICE_STAGE1_PCM_PATH="$PCM_PATH" \
    VOICE_STAGE1_INTERRUPT_PCM_PATH="$INTERRUPT_PCM_PATH" \
    VOICE_STAGE1_ROUTE="$route" \
    VOICE_STAGE1_APP_STATE="$app_state" \
    VOICE_STAGE1_NETWORK="$network" \
    VOICE_STAGE1_LIFECYCLE="$lifecycle" \
    VOICE_STAGE1_TARGET_SECONDS="$target_seconds" \
    VOICE_STAGE1_RUN_HASH="$RUN_HASH" \
    VOICE_STAGE1_COMPARISON_HASH="$COMPARISON_HASH" \
    VOICE_STAGE1_EVENT_OUTPUT="$output" \
    bash "$RUNNER" </dev/null
}

assert_common_success_contract() {
  local transport="$1"
  local output="$TMP_DIR/automation-events.jsonl"
  [[ -s "$output" ]] || fail "runner did not write finalized automation events"
  assert_selected_serial
  local separator=$'\x1f'
  local start_needle="--es${separator}transport${separator}${transport}"
  local start_commands
  start_commands="$(commands_matching "action.START")"
  [[ "$(printf '%s\n' "$start_commands" | awk -v needle="$start_needle" 'index($0, needle) { count++ } END { print count + 0 }')" == "1" ]] ||
    fail "transport extra was not passed exactly once on call start"
  [[ "$(command_count "exec-out${separator}run-as${separator}${PACKAGE}${separator}cat")" == "1" ]] ||
    fail "finalized JSONL was not fetched exactly once"
  local pulls
  pulls="$(commands_matching "exec-out${separator}run-as${separator}${PACKAGE}${separator}cat")"
  assert_contains "$pulls" "automation-events.jsonl"
  local all_commands
  all_commands="$(command_lines)"
  assert_not_contains "$all_commands" "automation.DUMP"
  assert_not_contains "$all_commands" "/data/local/tmp"
  assert_not_contains "$all_commands" "input-transcript"
  assert_not_contains "$all_commands" "output-transcript"
  assert_not_contains "$all_commands" "hermes-answer"
  assert_not_contains "$all_commands" "install"
  assert_not_contains "$all_commands" "push"
  assert_not_contains "$all_commands" "pull"
  assert_contains "$(cat "$output")" "\"observedTransport\":\"$transport\""
}

assert_worker_result marker_present complete marker-present 3 1 \
  bridge-breakage android-transcript-evidence 2
assert_worker_result marker_absent complete marker-absent 2 0 \
  absent-at-worker final-user-transcript 2
assert_worker_result marker_without_ready complete marker-present 2 1 \
  bridge-breakage android-transcript-evidence 2
assert_worker_result marker_before_ready complete marker-present 3 1 \
  bridge-breakage android-transcript-evidence 2
assert_worker_result mismatched_marker complete marker-absent 2 0 \
  absent-at-worker final-user-transcript 2
for unknown_worker_scenario in \
  registration_only \
  missing_ready \
  legacy_synthetic_ready \
  two_jobs \
  mismatched_ready \
  ready_before_request \
  missing_attribution \
  malformed_json \
  valid_then_malformed \
  unterminated_complete \
  duplicate_key \
  unstructured \
  missing \
  symlink \
  unreadable \
  wrong_mode; do
  assert_worker_result "$unknown_worker_scenario" complete unknown 0 0 \
    unknown evidence-collection 3
done
assert_worker_result truncated unavailable unknown 0 0 unknown evidence-collection 3

assert_combined_result file-present-worker-present \
  final_user present true 1 1 marker_present marker-present 3 1 \
  present ask-hermes 0
assert_combined_result file-present-worker-absent \
  final_user present true 1 1 marker_absent marker-absent 2 0 \
  present ask-hermes 0
assert_combined_result empty-worker-absent \
  empty unknown true 0 0 marker_absent marker-absent 2 0 \
  absent-at-worker final-user-transcript 2
assert_combined_result empty-worker-present \
  empty unknown true 0 0 marker_present marker-present 3 1 \
  bridge-breakage android-transcript-evidence 2
assert_combined_result job-worker-absent \
  job absent true 1 0 marker_absent marker-absent 2 0 \
  absent-at-worker final-user-transcript 2
assert_combined_result job-worker-present \
  job absent true 1 0 marker_present marker-present 3 1 \
  bridge-breakage android-transcript-evidence 2
assert_combined_result empty-worker-unknown \
  empty unknown true 0 0 registration_only unknown 0 0 \
  unknown evidence-collection 3
assert_combined_result missing-worker-present \
  missing unknown false 0 0 marker_present marker-present 3 1 \
  unknown evidence-collection 3
assert_combined_result malformed-worker-present \
  malformed_json unknown false 0 0 marker_present marker-present 3 1 \
  unknown evidence-collection 3
assert_combined_result missing-worker-absent \
  missing unknown false 0 0 marker_absent marker-absent 2 0 \
  absent-at-worker final-user-transcript 2
assert_combined_result malformed-worker-absent \
  malformed_json unknown false 0 0 marker_absent marker-absent 2 0 \
  absent-at-worker final-user-transcript 2

assert_transcript_file_result final_user present true 1 1
assert_transcript_file_result interrupted_user absent true 1 0
assert_transcript_file_result assistant absent true 1 0
assert_transcript_file_result job absent true 1 0
assert_transcript_file_result zero_final_user absent true 1 0
assert_transcript_file_result empty unknown true 0 0
assert_transcript_file_result missing unknown false 0 0
assert_transcript_file_result unreadable unknown false 0 0
assert_transcript_file_result symlink unknown false 0 0
assert_transcript_file_result wrong_mode unknown false 0 0
assert_transcript_file_result unterminated_complete unknown false 0 0
assert_transcript_file_result invalid_utf8 unknown false 0 0

for sanitized_kind in \
  job_accepted \
  job_running \
  still_working \
  job_succeeded \
  job_failed \
  job_expired \
  job_canceled \
  delivery_eligible \
  delivery_started \
  speech_started \
  delivery_blocked \
  delivery_announced \
  follow_up_correlation; do
  assert_transcript_file_result "valid_$sanitized_kind" absent true 1 0
done

for invalid_fixture in \
  mixed_session \
  duplicate_event \
  malformed_json \
  unknown_kind \
  bad_type \
  bad_schema \
  missing_key \
  qualifying_then_invalid \
  null_value \
  non_object \
  bool_count \
  bad_boolean \
  negative_count \
  bad_identifier \
  bad_hash \
  bad_timestamp \
  lossy_timestamp \
  bad_role \
  half_grounded \
  grounded_user; do
  assert_transcript_file_result "$invalid_fixture" unknown false 0 0
done

assert_android_snapshot_change_result mutation
assert_android_snapshot_change_result replacement
assert_android_snapshot_change_result truncation

reset_fake
set +e
VOICE_STAGE1_WORKER_LOG_PATH="$WORKER_LOG" \
VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS=unavailable \
bash "$RUNNER" --classify-transcript >"$TMP_DIR/classifier-stdout" 2>"$TMP_DIR/classifier-stderr"
status=$?
set -e
[[ "$status" -eq 1 ]] || fail "classifier accepted a missing transcript output environment value"
[[ ! -s "$TMP_DIR/classifier-stdout" ]] || fail "invalid classifier invocation emitted a result record"
assert_equals "stage1: VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT is required" \
  "$(cat "$TMP_DIR/classifier-stderr")"
[[ ! -s "$ADB_LOG" ]] || fail "invalid classification arguments reached ADB"

assert_invalid_classifier_environment() {
  local expected="$1"
  shift
  reset_fake
  set +e
  env "$@" bash "$RUNNER" --classify-transcript \
    >"$TMP_DIR/classifier-stdout" 2>"$TMP_DIR/classifier-stderr"
  status=$?
  set -e
  [[ "$status" -eq 1 ]] || fail "invalid classifier environment exited with status $status"
  [[ ! -s "$TMP_DIR/classifier-stdout" ]] ||
    fail "invalid classifier environment emitted result records"
  assert_equals "stage1: $expected" "$(cat "$TMP_DIR/classifier-stderr")"
  [[ ! -s "$ADB_LOG" ]] || fail "invalid classifier environment reached ADB"
  [[ ! -s "$EXTERNAL_LOG" ]] || fail "invalid classifier environment reached an external command"
}

write_transcript_fixture empty "$TRANSCRIPT_FIXTURE_DIR/invalid-environment.ndjson"
assert_invalid_classifier_environment \
  "VOICE_STAGE1_WORKER_LOG_PATH is required" \
  VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$TRANSCRIPT_FIXTURE_DIR/invalid-environment.ndjson" \
  VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS=unavailable
assert_invalid_classifier_environment \
  "VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS is required" \
  VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$TRANSCRIPT_FIXTURE_DIR/invalid-environment.ndjson" \
  VOICE_STAGE1_WORKER_LOG_PATH="$WORKER_LOG"
assert_invalid_classifier_environment \
  "VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS must be complete or unavailable" \
  VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$TRANSCRIPT_FIXTURE_DIR/invalid-environment.ndjson" \
  VOICE_STAGE1_WORKER_LOG_PATH="$WORKER_LOG" \
  VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS=truncated

assert_rejected_runner_arguments() {
  reset_fake
  set +e
  bash "$RUNNER" "$@" >"$TMP_DIR/classifier-stdout" 2>"$TMP_DIR/classifier-stderr"
  status=$?
  set -e
  [[ "$status" -eq 1 ]] || fail "invalid runner arguments exited with status $status"
  [[ ! -s "$TMP_DIR/classifier-stdout" ]] || fail "invalid runner arguments emitted stdout"
  assert_equals \
    "stage1: usage: voice-agent-stage1-e2e.sh [--preflight|--classify-transcript]" \
    "$(cat "$TMP_DIR/classifier-stderr")"
  [[ ! -s "$ADB_LOG" ]] || fail "invalid runner arguments reached ADB"
  [[ ! -s "$EXTERNAL_LOG" ]] || fail "invalid runner arguments reached an external command"
}

assert_rejected_runner_arguments --classify-transcript extra
assert_rejected_runner_arguments --classify-transcript=complete
assert_rejected_runner_arguments --unknown

reset_fake
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
assert_selected_serial

reset_fake
export FAKE_ADB_BROADCAST_MULTILINE=1
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
unset FAKE_ADB_BROADCAST_MULTILINE

reset_fake
export FAKE_ADB_STATUS_COLD_START=1
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
[[ "$(command_count "automation.STATUS")" == "1" ]] \
  || fail "idle-background preflight retried a valid unavailable network readback"
unset FAKE_ADB_STATUS_COLD_START

reset_fake
export FAKE_ADB_BACKGROUND_NETWORK_BLOCKED=1
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_contains "$preflight_output" "stage1.connectivity_readback=ready"
[[ "$(command_count "automation.STATUS")" == "1" ]] \
  || fail "background-blocked preflight did not accept one valid idle readback"
unset FAKE_ADB_BACKGROUND_NETWORK_BLOCKED

reset_fake
export FAKE_ADB_STAGE_VISIBILITY_DELAY=1
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
[[ "$(command_count "stat")" == "2" ]] \
  || fail "delayed fixture visibility did not retry size verification exactly once"
unset FAKE_ADB_STAGE_VISIBILITY_DELAY

reset_fake
export FAKE_ADB_INITIAL_RUN=foreign
export FAKE_ADB_APP_NETWORK=cellular
set +e
foreign_preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
foreign_preflight_status=$?
set -e
[[ "$foreign_preflight_status" -ne 0 ]] || fail "preflight accepted a foreign active run"
assert_contains "$foreign_preflight_output" "automation receiver already has an active run"
[[ "$(command_count "automation.STATUS")" == "1" ]] \
  || fail "preflight retried STATUS against a foreign active run"
unset FAKE_ADB_INITIAL_RUN FAKE_ADB_APP_NETWORK

reset_fake
export FAKE_ADB_STATUS_MALFORMED=1
set +e
malformed_status_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
malformed_status_status=$?
set -e
[[ "$malformed_status_status" -ne 0 ]] || fail "preflight accepted malformed STATUS output"
assert_contains "$malformed_status_output" "automation status returned malformed broadcast output"
[[ "$(command_count "automation.STATUS")" == "1" ]] \
  || fail "preflight retried malformed STATUS output"
unset FAKE_ADB_STATUS_MALFORMED

reset_fake
export FAKE_ADB_SVC_WIFI_USAGE_STATUS=1
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
unset FAKE_ADB_SVC_WIFI_USAGE_STATUS

reset_fake
export FAKE_ADB_SVC_WIFI_USAGE_STATUS=1
export FAKE_ADB_SVC_WIFI_USAGE_OUTPUT="error: unable to enable or disable Wi-Fi"
set +e
malformed_wifi_usage_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
malformed_wifi_usage_status=$?
set -e
[[ "$malformed_wifi_usage_status" -ne 0 ]] || fail "preflight accepted malformed svc wifi usage output"
assert_contains "$malformed_wifi_usage_output" "Wi-Fi control is unavailable"
assert_not_contains "$malformed_wifi_usage_output" "stage1.device="
unset FAKE_ADB_SVC_WIFI_USAGE_STATUS FAKE_ADB_SVC_WIFI_USAGE_OUTPUT

reset_fake
export FAKE_ADB_SVC_WIFI_USAGE_STATUS=2
set +e
invalid_wifi_usage_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
invalid_wifi_usage_status=$?
set -e
[[ "$invalid_wifi_usage_status" -ne 0 ]] || fail "preflight accepted an unexpected svc wifi usage status"
assert_contains "$invalid_wifi_usage_output" "Wi-Fi control readback failed"
assert_not_contains "$invalid_wifi_usage_output" "stage1.device="
unset FAKE_ADB_SVC_WIFI_USAGE_STATUS

reset_fake
export FAKE_ADB_SVC_DATA_USAGE_STATUS=1
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
unset FAKE_ADB_SVC_DATA_USAGE_STATUS

reset_fake
export FAKE_ADB_SVC_DATA_USAGE_STATUS=1
export FAKE_ADB_SVC_DATA_USAGE_OUTPUT="error: unable to enable or disable cellular service"
set +e
malformed_data_usage_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
malformed_data_usage_status=$?
set -e
[[ "$malformed_data_usage_status" -ne 0 ]] || fail "preflight accepted malformed svc data usage output"
assert_contains "$malformed_data_usage_output" "cellular control is unavailable"
assert_not_contains "$malformed_data_usage_output" "stage1.device="
unset FAKE_ADB_SVC_DATA_USAGE_STATUS FAKE_ADB_SVC_DATA_USAGE_OUTPUT

reset_fake
export FAKE_ADB_SVC_DATA_USAGE_STATUS=2
set +e
invalid_data_usage_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
invalid_data_usage_status=$?
set -e
[[ "$invalid_data_usage_status" -ne 0 ]] || fail "preflight accepted an unexpected svc data usage status"
assert_contains "$invalid_data_usage_output" "cellular control readback failed"
assert_not_contains "$invalid_data_usage_output" "stage1.device="
unset FAKE_ADB_SVC_DATA_USAGE_STATUS

reset_fake
export FAKE_ADB_DEVICES_MODE=multiple
multiple_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
assert_contains "$multiple_output" "stage1.device=$SERIAL"
assert_selected_serial
unset FAKE_ADB_DEVICES_MODE

reset_fake
set +e
wrong_serial_output="$(runner_env VOICE_STAGE1_SERIAL=WRONG_SERIAL bash "$RUNNER" --preflight </dev/null 2>&1)"
wrong_serial_status=$?
set -e
[[ "$wrong_serial_status" -ne 0 ]] || fail "preflight accepted the wrong physical serial"
assert_contains "$wrong_serial_output" "requires physical device $SERIAL"
[[ ! -s "$ADB_LOG" ]] || fail "wrong serial reached ADB before rejection"

reset_fake
export FAKE_ADB_EMULATOR=1
set +e
emulator_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
emulator_status=$?
set -e
[[ "$emulator_status" -ne 0 ]] || fail "preflight accepted emulator properties"
assert_contains "$emulator_output" "physical device verification failed"
assert_no_adb_mutations
unset FAKE_ADB_EMULATOR

reset_fake
export FAKE_ADB_FAIL_MODE=preflight_stage
set +e
runner_env bash "$RUNNER" --preflight </dev/null >/dev/null 2>&1
preflight_stage_status=$?
set -e
[[ "$preflight_stage_status" -ne 0 ]] || fail "partial preflight stage unexpectedly succeeded"
separator=$'\x1f'
[[ "$(command_count "rm${separator}-f${separator}files/voice-stage1/.preflight")" == "1" ]] ||
  fail "partial preflight stage was not cleaned exactly once"
assert_private_path_absent "files/voice-stage1/.preflight"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=preflight_remove
set +e
runner_env bash "$RUNNER" --preflight </dev/null >/dev/null 2>&1
preflight_remove_status=$?
set -e
[[ "$preflight_remove_status" -ne 0 ]] || fail "ambiguous preflight remove unexpectedly succeeded"
separator=$'\x1f'
[[ "$(command_count "rm${separator}-f${separator}files/voice-stage1/.preflight")" == "1" ]] ||
  fail "ambiguous preflight remove was retried"
assert_private_path_absent "files/voice-stage1/.preflight"
unset FAKE_ADB_FAIL_MODE

reset_fake
lock_path="$LOCK_DIR/voice-agent-stage1-$SERIAL.lock"
: > "$lock_path"
chmod 600 "$lock_path"
exec {held_lock_fd}> "$lock_path"
flock -n "$held_lock_fd"
set +e
locked_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
locked_status=$?
set -e
flock -u "$held_lock_fd"
exec {held_lock_fd}>&-
[[ "$locked_status" -ne 0 ]] || fail "concurrent runner acquired an already-held device lock"
assert_contains "$locked_output" "another Stage1 runner owns $SERIAL"
[[ ! -s "$ADB_LOG" ]] || fail "locked runner reached ADB"

reset_fake
export FAKE_ADB_INITIAL_RUN=foreign
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
foreign_active_status=$?
set -e
[[ "$foreign_active_status" -ne 0 ]] || fail "runner accepted a foreign active run"
[[ "$(command_count "automation.FINALIZE")" == "0" ]] || fail "cleanup finalized a foreign active run"
[[ "$(command_count "action.END")" == "0" ]] || fail "cleanup ended a call this invocation did not start"
unset FAKE_ADB_INITIAL_RUN

assert_probe_preflight_failure() {
  local expected="$1"
  local probe_value="$2"
  local transport="$3"
  local lifecycle="$4"
  local startup_path="$5"
  reset_fake
  export VOICE_STAGE1_STARTUP_TRUTH_PROBE="$probe_value"
  if [[ "$startup_path" == "__unset__" ]]; then
    unset VOICE_STAGE1_STARTUP_PCM_PATH
  else
    export VOICE_STAGE1_STARTUP_PCM_PATH="$startup_path"
  fi
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground "$lifecycle" 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "invalid probe preflight was accepted"
  assert_contains "$output" "$expected"
  [[ ! -s "$ADB_LOG" ]] || fail "invalid probe preflight reached ADB"
  unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH
}

EMPTY_STARTUP_PCM="$TMP_DIR/empty-startup.pcm"
SYMLINK_STARTUP_PCM="$TMP_DIR/symlink-startup.pcm"
EQUAL_STARTUP_PCM="$TMP_DIR/equal-startup.pcm"
: > "$EMPTY_STARTUP_PCM"
ln -s "$STARTUP_PCM_PATH" "$SYMLINK_STARTUP_PCM"
cp "$PCM_PATH" "$EQUAL_STARTUP_PCM"

assert_probe_preflight_failure \
  "VOICE_STAGE1_STARTUP_TRUTH_PROBE must be 0 or 1" \
  2 livekit_experimental steady "$STARTUP_PCM_PATH"
assert_probe_preflight_failure \
  "startup truth probe requires livekit_experimental transport" \
  1 direct_gemini steady "$STARTUP_PCM_PATH"
assert_probe_preflight_failure \
  "startup truth probe requires steady lifecycle" \
  1 livekit_experimental interruption "$STARTUP_PCM_PATH"
assert_probe_preflight_failure \
  "VOICE_STAGE1_STARTUP_PCM_PATH is required" \
  1 livekit_experimental steady __unset__
assert_probe_preflight_failure \
  "VOICE_STAGE1_STARTUP_PCM_PATH must be a nonempty regular file" \
  1 livekit_experimental steady "$EMPTY_STARTUP_PCM"
assert_probe_preflight_failure \
  "VOICE_STAGE1_STARTUP_PCM_PATH must be a nonempty regular file" \
  1 livekit_experimental steady "$SYMLINK_STARTUP_PCM"
assert_probe_preflight_failure \
  "startup and prompt fixtures must have different byte counts" \
  1 livekit_experimental steady "$EQUAL_STARTUP_PCM"

assert_transcript_preflight_failure() {
  local expected="$1"
  local transcript_probe="$2"
  local startup_probe="$3"
  local transport="$4"
  local lifecycle="$5"
  local startup_path="$6"
  local transcript_output="$7"
  reset_fake
  export VOICE_STAGE1_TRANSCRIPT_PROBE="$transcript_probe"
  export VOICE_STAGE1_STARTUP_TRUTH_PROBE="$startup_probe"
  if [[ "$startup_path" == "__unset__" ]]; then
    unset VOICE_STAGE1_STARTUP_PCM_PATH
  else
    export VOICE_STAGE1_STARTUP_PCM_PATH="$startup_path"
  fi
  if [[ "$transcript_output" == "__unset__" ]]; then
    unset VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT
  else
    export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
  fi
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground "$lifecycle" 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "invalid transcript probe preflight was accepted"
  assert_contains "$output" "$expected"
  [[ ! -s "$ADB_LOG" ]] || fail "invalid transcript probe preflight reached ADB"
  unset VOICE_STAGE1_TRANSCRIPT_PROBE VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT
  unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH
}

assert_evidence_output_preflight_failure() {
  local expected="$1"
  local automation_output="$2"
  local transcript_output="$3"
  reset_fake
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_output"
  export VOICE_STAGE1_TRANSCRIPT_PROBE=1
  export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
  export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
  export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
  set +e
  output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "invalid evidence outputs were accepted"
  assert_equals "stage1: $expected" "$output"
  [[ ! -s "$ADB_LOG" ]] || fail "invalid evidence outputs reached ADB"
}

assert_preexisting_evidence_output_preserved() {
  local expected="$1"
  local automation_output="$2"
  local transcript_output="$3"
  local preserved_path="$4"
  local before
  before="$(path_metadata "$preserved_path")"
  assert_evidence_output_preflight_failure "$expected" "$automation_output" "$transcript_output"
  assert_equals "$before" "$(path_metadata "$preserved_path")"
}

TRANSCRIPT_OUTPUT="$TMP_DIR/sanitized-events.ndjson"
EXPECTED_TRANSCRIPT_OUTPUT="$TMP_DIR/expected-sanitized-events.ndjson"
TRANSCRIPT_OUTPUT_LINK="$TMP_DIR/sanitized-events-link.ndjson"
TRANSCRIPT_OUTPUT_DIRECTORY="$TMP_DIR/sanitized-events-directory"
ln -s "$TRANSCRIPT_OUTPUT" "$TRANSCRIPT_OUTPUT_LINK"
mkdir "$TRANSCRIPT_OUTPUT_DIRECTORY"

TRANSCRIPT_OUTPUT_FIFO="$TMP_DIR/transcript-output.fifo"
AUTOMATION_OUTPUT_FIFO="$TMP_DIR/automation-output.fifo"
mkfifo "$TRANSCRIPT_OUTPUT_FIFO" "$AUTOMATION_OUTPUT_FIFO"

PREEXISTING_AUTOMATION="$TMP_DIR/preexisting-automation.jsonl"
PREEXISTING_AUTOMATION_SENTINEL="$TMP_DIR/preexisting-automation.sentinel"
PREEXISTING_TRANSCRIPT="$TMP_DIR/preexisting-transcript.ndjson"
PREEXISTING_TRANSCRIPT_SENTINEL="$TMP_DIR/preexisting-transcript.sentinel"
printf 'synthetic-preexisting-automation\n' > "$PREEXISTING_AUTOMATION_SENTINEL"
cp "$PREEXISTING_AUTOMATION_SENTINEL" "$PREEXISTING_AUTOMATION"
chmod 640 "$PREEXISTING_AUTOMATION"
printf 'synthetic-preexisting-transcript\n' > "$PREEXISTING_TRANSCRIPT_SENTINEL"
cp "$PREEXISTING_TRANSCRIPT_SENTINEL" "$PREEXISTING_TRANSCRIPT"
chmod 640 "$PREEXISTING_TRANSCRIPT"

ALIAS_PARENT="$TMP_DIR/output-alias-parent"
mkdir "$ALIAS_PARENT"
ALIAS_AUTOMATION="$ALIAS_PARENT/events.jsonl"
ALIAS_TRANSCRIPT="$ALIAS_PARENT/./events.jsonl"

HARDLINK_AUTOMATION="$TMP_DIR/hardlink-automation.jsonl"
HARDLINK_TRANSCRIPT="$TMP_DIR/hardlink-transcript.jsonl"
: > "$HARDLINK_AUTOMATION"
ln "$HARDLINK_AUTOMATION" "$HARDLINK_TRANSCRIPT"

assert_transcript_preflight_failure \
  "VOICE_STAGE1_TRANSCRIPT_PROBE must be 0 or 1" \
  2 1 livekit_experimental steady "$STARTUP_PCM_PATH" "$TRANSCRIPT_OUTPUT"
assert_transcript_preflight_failure \
  "transcript probe requires startup truth probe" \
  1 0 livekit_experimental steady "$STARTUP_PCM_PATH" "$TRANSCRIPT_OUTPUT"
assert_transcript_preflight_failure \
  "transcript probe requires livekit_experimental transport" \
  1 1 direct_gemini steady "$STARTUP_PCM_PATH" "$TRANSCRIPT_OUTPUT"
assert_transcript_preflight_failure \
  "transcript probe requires steady lifecycle" \
  1 1 livekit_experimental interruption "$STARTUP_PCM_PATH" "$TRANSCRIPT_OUTPUT"
assert_transcript_preflight_failure \
  "VOICE_STAGE1_STARTUP_PCM_PATH is required" \
  1 1 livekit_experimental steady __unset__ "$TRANSCRIPT_OUTPUT"
assert_transcript_preflight_failure \
  "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT is required" \
  1 1 livekit_experimental steady "$STARTUP_PCM_PATH" __unset__

assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_EVENT_OUTPUT must be absent" \
  "$PREEXISTING_AUTOMATION" "$TMP_DIR/preexisting-automation-transcript.ndjson" \
  "$PREEXISTING_AUTOMATION"
cmp -s "$PREEXISTING_AUTOMATION_SENTINEL" "$PREEXISTING_AUTOMATION" ||
  fail "pre-existing automation output bytes changed"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT must be absent" \
  "$TMP_DIR/preexisting-transcript-automation.jsonl" "$PREEXISTING_TRANSCRIPT" \
  "$PREEXISTING_TRANSCRIPT"
cmp -s "$PREEXISTING_TRANSCRIPT_SENTINEL" "$PREEXISTING_TRANSCRIPT" ||
  fail "pre-existing transcript output bytes changed"

assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT must be absent" \
  "$TMP_DIR/link-transcript-automation.jsonl" "$TRANSCRIPT_OUTPUT_LINK" \
  "$TRANSCRIPT_OUTPUT_LINK"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT must be absent" \
  "$TMP_DIR/directory-transcript-automation.jsonl" "$TRANSCRIPT_OUTPUT_DIRECTORY" \
  "$TRANSCRIPT_OUTPUT_DIRECTORY"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT must be absent" \
  "$TMP_DIR/fifo-transcript-automation.jsonl" "$TRANSCRIPT_OUTPUT_FIFO" \
  "$TRANSCRIPT_OUTPUT_FIFO"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_EVENT_OUTPUT must be absent" \
  "$AUTOMATION_OUTPUT_FIFO" "$TMP_DIR/fifo-automation-transcript.jsonl" \
  "$AUTOMATION_OUTPUT_FIFO"
SAME_OUTPUT="$TMP_DIR/same-evidence-output.jsonl"
assert_evidence_output_preflight_failure \
  "Stage1 evidence outputs must be distinct" "$SAME_OUTPUT" "$SAME_OUTPUT"
assert_evidence_output_preflight_failure \
  "Stage1 evidence outputs must be distinct" "$ALIAS_AUTOMATION" "$ALIAS_TRANSCRIPT"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_EVENT_OUTPUT must be absent" \
  "$HARDLINK_AUTOMATION" "$TMP_DIR/hardlink-automation-transcript.jsonl" \
  "$HARDLINK_AUTOMATION"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT must be absent" \
  "$TMP_DIR/hardlink-transcript-automation.jsonl" "$HARDLINK_TRANSCRIPT" \
  "$HARDLINK_TRANSCRIPT"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_EVENT_OUTPUT must be absent" \
  "$TRANSCRIPT_OUTPUT_LINK" "$TMP_DIR/automation-link-transcript.jsonl" \
  "$TRANSCRIPT_OUTPUT_LINK"
assert_preexisting_evidence_output_preserved \
  "VOICE_STAGE1_EVENT_OUTPUT must be absent" \
  "$TRANSCRIPT_OUTPUT_DIRECTORY" "$TMP_DIR/automation-directory-transcript.jsonl" \
  "$TRANSCRIPT_OUTPUT_DIRECTORY"

reset_fake
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null
assert_not_contains "$(command_lines)" "latest-trace-id.txt"
assert_not_contains "$(command_lines)" "voice-experience-events.ndjson"

enable_transcript_collection() {
  export VOICE_STAGE1_TRANSCRIPT_PROBE=1
  export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$TRANSCRIPT_OUTPUT"
  export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
  export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
  export FAKE_CLOCK_STEP=1
}

assert_transcript_output_mode() {
  [[ "$(stat -c %a "$TRANSCRIPT_OUTPUT")" == "600" ]] ||
    fail "sanitized transcript output was not mode 0600"
}

assert_no_unpublished_evidence_temps() {
  local automation_parent="$1"
  local transcript_parent="$2"
  if [[ -d "$automation_parent" ]]; then
    [[ -z "$(find "$automation_parent" -maxdepth 1 -name '.voice-stage1-events.*' -print -quit)" ]] ||
      fail "unpublished automation temp remained"
  fi
  if [[ -d "$transcript_parent" ]]; then
    [[ -z "$(find "$transcript_parent" -maxdepth 1 -name '.voice-stage1-transcript.*' -print -quit)" ]] ||
      fail "unpublished transcript temp remained"
  fi
}

assert_late_publication_race_preserved() {
  local family="$1"
  local automation_parent="$TMP_DIR/late-race-$family-automation"
  local transcript_parent="$TMP_DIR/late-race-$family-transcript"
  local automation_output="$automation_parent/events.jsonl"
  local transcript_output="$transcript_parent/events.ndjson"
  local raced_output
  local expected_diagnostic
  local transport="direct_gemini"
  local output
  local status
  reset_fake
  mkdir -p "$automation_parent" "$transcript_parent"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_output"
  if [[ "$family" == "transcript" ]]; then
    enable_transcript_collection
    export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
    raced_output="$transcript_output"
    expected_diagnostic="stage1: unable to collect sanitized transcript evidence"
    transport="livekit_experimental"
  else
    raced_output="$automation_output"
    expected_diagnostic="stage1: unable to fetch finalized automation events"
  fi
  export FAKE_PRIVATE_PUBLISH_RACE="$family"
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "late $family destination race was accepted"
  assert_equals "$expected_diagnostic" "$output"
  assert_equals "synthetic-late-destination" "$(cat "$raced_output")"
  [[ "$(stat -c %a "$raced_output")" == "640" ]] ||
    fail "late $family destination metadata changed"
  assert_no_unpublished_evidence_temps "$automation_parent" "$transcript_parent"
  if [[ "$family" == "transcript" ]]; then
    [[ ! -e "$automation_output" ]] || fail "transcript race published automation output"
  fi
}

assert_late_publication_race_preserved automation
assert_late_publication_race_preserved transcript

assert_no_private_evidence_child() {
  local directory="$1"
  local child
  child="$(find "$directory" -maxdepth 1 \
    \( -name '.voice-stage1-events.*' -o -name '.voice-stage1-transcript.*' \) \
    -print -quit)"
  [[ -z "$child" ]] || fail "late directory contains an unintended private evidence child"
}

assert_late_directory_publication_race_preserved() {
  local family="$1"
  local destination_kind="$2"
  local automation_parent="$TMP_DIR/PRIVATE-LATE-DIRECTORY-SENTINEL-$family-$destination_kind-automation"
  local transcript_parent="$TMP_DIR/PRIVATE-LATE-DIRECTORY-SENTINEL-$family-$destination_kind-transcript"
  local automation_output="$automation_parent/events.jsonl"
  local transcript_output="$transcript_parent/events.ndjson"
  local raced_output="$automation_output"
  local late_directory="$automation_output"
  local expected_diagnostic="stage1: unable to fetch finalized automation events"
  local transport="direct_gemini"
  local target_metadata=""
  local target_entry_metadata=""
  local output
  local status
  reset_fake
  mkdir -p "$automation_parent" "$transcript_parent"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_output"
  if [[ "$family" == "transcript" ]]; then
    enable_transcript_collection
    export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
    raced_output="$transcript_output"
    late_directory="$transcript_output"
    expected_diagnostic="stage1: unable to collect sanitized transcript evidence"
    transport="livekit_experimental"
  fi
  if [[ "$destination_kind" == "directory_symlink" ]]; then
    late_directory="$raced_output.target"
    mkdir -- "$late_directory"
    printf 'synthetic-preserved-entry\n' > "$late_directory/preserved-entry"
    chmod 750 "$late_directory"
    chmod 640 "$late_directory/preserved-entry"
    target_metadata="$(path_metadata "$late_directory")"
    target_entry_metadata="$(path_metadata "$late_directory/preserved-entry")"
    export FAKE_PRIVATE_PUBLISH_RACE_SYMLINK_TARGET="$late_directory"
  fi
  export FAKE_PRIVATE_PUBLISH_RACE="$family"
  export FAKE_PRIVATE_PUBLISH_RACE_KIND="$destination_kind"
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] ||
    fail "late $family $destination_kind destination race was accepted"
  assert_equals "$expected_diagnostic" "$output"
  assert_not_contains "$output" "PRIVATE-LATE-DIRECTORY-SENTINEL"
  if [[ "$destination_kind" == "directory_symlink" ]]; then
    [[ -L "$raced_output" ]] || fail "late $family directory symlink was replaced"
    assert_equals "$late_directory" "$(readlink -- "$raced_output")"
    assert_equals "$target_metadata" "$(path_metadata "$late_directory")"
    assert_equals "$target_entry_metadata" "$(path_metadata "$late_directory/preserved-entry")"
  else
    [[ -d "$raced_output" && ! -L "$raced_output" ]] ||
      fail "late $family directory was replaced"
  fi
  assert_equals "synthetic-preserved-entry" "$(cat "$late_directory/preserved-entry")"
  [[ "$(stat -c %a "$late_directory")" == "750" ]] ||
    fail "late $family $destination_kind metadata changed"
  [[ "$(stat -c %a "$late_directory/preserved-entry")" == "640" ]] ||
    fail "late $family $destination_kind entry metadata changed"
  assert_no_private_evidence_child "$late_directory"
  assert_no_unpublished_evidence_temps "$automation_parent" "$transcript_parent"
  if [[ "$family" == "transcript" ]]; then
    [[ ! -e "$automation_output" && ! -L "$automation_output" ]] ||
      fail "transcript $destination_kind race published automation output"
  fi
}

assert_late_directory_publication_race_preserved automation directory
assert_late_directory_publication_race_preserved automation directory_symlink
assert_late_directory_publication_race_preserved transcript directory
assert_late_directory_publication_race_preserved transcript directory_symlink

assert_failed_private_reopen_is_contained() {
  local family="$1"
  local automation_parent="$TMP_DIR/PRIVATE-REOPEN-SENTINEL-$family-automation"
  local transcript_parent="$TMP_DIR/PRIVATE-REOPEN-SENTINEL-$family-transcript"
  local automation_output="$automation_parent/events.jsonl"
  local transcript_output="$transcript_parent/events.ndjson"
  local failure_target="$TMP_DIR/reopen-failure-target-$family"
  local expected_diagnostic="stage1: unable to fetch finalized automation events"
  local transport="direct_gemini"
  local output
  local status
  reset_fake
  mkdir -p "$automation_parent" "$transcript_parent" "$failure_target"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_output"
  if [[ "$family" == "transcript" ]]; then
    enable_transcript_collection
    export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
    expected_diagnostic="stage1: unable to collect sanitized transcript evidence"
    transport="livekit_experimental"
  fi
  export FAKE_PRIVATE_REOPEN_FAIL="$family"
  export FAKE_PRIVATE_REOPEN_FAILURE_TARGET="$failure_target"
  export FAKE_PRIVATE_REOPEN_MARKER="$TMP_DIR/reopen-injected-$family"
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ -s "$FAKE_PRIVATE_REOPEN_MARKER" ]] || fail "$family private-temp reopen failure was not injected"
  [[ "$status" -ne 0 ]] || fail "$family private-temp reopen failure was accepted"
  assert_equals "$expected_diagnostic" "$output"
  assert_not_contains "$output" "PRIVATE-REOPEN-SENTINEL"
  [[ ! -e "$automation_output" && ! -L "$automation_output" ]] ||
    fail "$family private-temp reopen failure published automation output"
  [[ ! -e "$transcript_output" && ! -L "$transcript_output" ]] ||
    fail "$family private-temp reopen failure published transcript output"
  assert_no_unpublished_evidence_temps "$automation_parent" "$transcript_parent"
}

assert_failed_private_reopen_is_contained automation
assert_failed_private_reopen_is_contained transcript

assert_signal_cleans_owned_evidence() {
  local family="$1"
  local signal_name="$2"
  local expected_status="$3"
  local automation_parent="$TMP_DIR/PRIVATE-SIGNAL-SENTINEL-$family-automation"
  local transcript_parent="$TMP_DIR/PRIVATE-SIGNAL-SENTINEL-$family-transcript"
  local automation_output="$automation_parent/events.jsonl"
  local transcript_output="$transcript_parent/events.ndjson"
  local transport="direct_gemini"
  local output
  local status
  reset_fake
  mkdir -p "$automation_parent" "$transcript_parent"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_output"
  if [[ "$family" == "transcript" ]]; then
    enable_transcript_collection
    export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
    transport="livekit_experimental"
  fi
  export FAKE_PRIVATE_SIGNAL_AFTER_CHMOD="$signal_name:$family"
  export FAKE_PRIVATE_SIGNAL_MARKER="$TMP_DIR/signal-injected-$signal_name-$family"
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ -s "$FAKE_PRIVATE_SIGNAL_MARKER" ]] || fail "$signal_name was not injected for $family temp"
  [[ "$status" -eq "$expected_status" ]] ||
    fail "$signal_name during $family temp ownership exited with status $status"
  assert_equals "" "$output"
  assert_not_contains "$output" "PRIVATE-SIGNAL-SENTINEL"
  [[ ! -e "$automation_output" && ! -L "$automation_output" ]] ||
    fail "$signal_name during $family temp ownership published automation output"
  [[ ! -e "$transcript_output" && ! -L "$transcript_output" ]] ||
    fail "$signal_name during $family temp ownership published transcript output"
  assert_no_unpublished_evidence_temps "$automation_parent" "$transcript_parent"
}

assert_signal_cleans_owned_evidence automation TERM 143
assert_signal_cleans_owned_evidence transcript INT 130
assert_signal_cleans_owned_evidence automation HUP 129

assert_late_race_cleanup_failure_is_reported() {
  local family="$1"
  local automation_parent="$TMP_DIR/PRIVATE-RM-FAIL-SENTINEL-$family-automation"
  local transcript_parent="$TMP_DIR/PRIVATE-RM-FAIL-SENTINEL-$family-transcript"
  local automation_output="$automation_parent/events.jsonl"
  local transcript_output="$transcript_parent/events.ndjson"
  local temp_pattern=".voice-stage1-events.*"
  local raced_output="$automation_output"
  local stranded_parent="$automation_parent"
  local transport="direct_gemini"
  local output
  local status
  local stranded
  reset_fake
  mkdir -p "$automation_parent" "$transcript_parent"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_output"
  if [[ "$family" == "transcript" ]]; then
    enable_transcript_collection
    export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_output"
    temp_pattern=".voice-stage1-transcript.*"
    raced_output="$transcript_output"
    stranded_parent="$transcript_parent"
    transport="livekit_experimental"
  fi
  export FAKE_PRIVATE_PUBLISH_RACE="$family"
  export FAKE_PRIVATE_REMOVE_FAIL="$family"
  set +e
  output="$(run_scenario "$transport" stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "late $family race with cleanup failure was accepted"
  assert_equals "stage1: unable to clean unpublished Stage1 evidence" "$output"
  assert_not_contains "$output" "PRIVATE-RM-FAIL-SENTINEL"
  assert_equals "synthetic-late-destination" "$(cat "$raced_output")"
  [[ "$(stat -c %a "$raced_output")" == "640" ]] ||
    fail "late $family destination metadata changed after cleanup failure"
  stranded="$(find "$stranded_parent" -maxdepth 1 -name "$temp_pattern" -print)"
  [[ -n "$stranded" && "${stranded//$'\n'/}" == "$stranded" ]] ||
    fail "cleanup failure did not strand exactly one synthetic $family temp"
  [[ -f "$stranded" && ! -L "$stranded" ]] ||
    fail "cleanup failure stranded an unexpected $family object"
  unset FAKE_PRIVATE_REMOVE_FAIL
  rm -f -- "$stranded"
  assert_no_unpublished_evidence_temps "$automation_parent" "$transcript_parent"
}

assert_late_race_cleanup_failure_is_reported automation
assert_late_race_cleanup_failure_is_reported transcript

assert_fixture_size_failure_cleanup() {
  reset_fake
  local automation_parent="$TMP_DIR/fixture-size-automation"
  local transcript_parent="$TMP_DIR/fixture-size-transcript"
  mkdir -p "$automation_parent" "$transcript_parent"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$automation_parent/events.jsonl"
  enable_transcript_collection
  export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$transcript_parent/events.ndjson"
  export FAKE_WC_FAIL_WHEN_FINALIZED=1
  set +e
  output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "fixture-size failure was accepted"
  [[ ! -e "$automation_parent/events.jsonl" ]] ||
    fail "fixture-size failure published automation output"
  assert_no_unpublished_evidence_temps "$automation_parent" "$transcript_parent"
  assert_equals "stage1: unable to fetch finalized automation events" "$output"
  assert_not_contains "$output" "PRIVATE-FIXTURE-SIZE-PATH"
}

assert_fixture_size_failure_cleanup

reset_fake
rm -f "$TRANSCRIPT_OUTPUT"
enable_transcript_collection
export FAKE_ADB_TRACE_SNAPSHOT_CLOCK_ADVANCE=1000
export FAKE_CLOCK_STEP=1
export VOICE_STAGE1_TEST_MAX_WAIT_ATTEMPTS=120
run_scenario livekit_experimental stable_wifi speaker foreground steady 100 >/dev/null
python3 - "$STATE_DIR/state.json" <<'PY'
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
start = state["call_start_clock"]
end = state["call_end_clock"]
if type(start) is not int or type(end) is not int or end - start < 100:
    raise SystemExit("slow trace snapshot consumed the call-duration window")
PY

for private_command in mkdir mktemp chmod ln; do
  reset_fake
  AUTOMATION_SENTINEL_PARENT="$TMP_DIR/PRIVATE-OUTPUT-SENTINEL-automation-$private_command"
  TRANSCRIPT_SAFE_PARENT="$TMP_DIR/transcript-safe-$private_command"
  [[ "$private_command" == mkdir ]] || mkdir -p "$AUTOMATION_SENTINEL_PARENT"
  mkdir -p "$TRANSCRIPT_SAFE_PARENT"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$AUTOMATION_SENTINEL_PARENT/events.jsonl"
  enable_transcript_collection
  export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$TRANSCRIPT_SAFE_PARENT/events.ndjson"
  export FAKE_PRIVATE_FILE_FAIL="$private_command"
  set +e
  output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "automation $private_command failure was accepted"
  assert_equals "stage1: unable to fetch finalized automation events" "$output"
  assert_not_contains "$output" "PRIVATE-OUTPUT-SENTINEL"
  assert_no_unpublished_evidence_temps "$AUTOMATION_SENTINEL_PARENT" "$TRANSCRIPT_SAFE_PARENT"

  reset_fake
  AUTOMATION_SAFE_PARENT="$TMP_DIR/automation-safe-$private_command"
  TRANSCRIPT_SENTINEL_PARENT="$TMP_DIR/PRIVATE-OUTPUT-SENTINEL-transcript-$private_command"
  mkdir -p "$AUTOMATION_SAFE_PARENT"
  [[ "$private_command" == mkdir ]] || mkdir -p "$TRANSCRIPT_SENTINEL_PARENT"
  export VOICE_STAGE1_TEST_EVENT_OUTPUT="$AUTOMATION_SAFE_PARENT/events.jsonl"
  enable_transcript_collection
  export VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT="$TRANSCRIPT_SENTINEL_PARENT/events.ndjson"
  export FAKE_PRIVATE_FILE_FAIL="$private_command"
  set +e
  output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "transcript $private_command failure was accepted"
  assert_equals "stage1: unable to collect sanitized transcript evidence" "$output"
  assert_not_contains "$output" "PRIVATE-OUTPUT-SENTINEL"
  assert_no_unpublished_evidence_temps "$AUTOMATION_SAFE_PARENT" "$TRANSCRIPT_SENTINEL_PARENT"
done

reset_fake
rm -f "$TRANSCRIPT_OUTPUT"
enable_transcript_collection
export FAKE_ADB_VOICE_EVENTS=$'{"kind":"sanitized","text":"safe"}\n'
run_scenario livekit_experimental stable_wifi speaker foreground steady 20 >/dev/null
printf '%s\n' '{"kind":"sanitized","text":"safe"}' > "$EXPECTED_TRANSCRIPT_OUTPUT"
cmp -s "$EXPECTED_TRANSCRIPT_OUTPUT" "$TRANSCRIPT_OUTPUT" ||
  fail "sanitized transcript output did not preserve exact artifact bytes"
assert_transcript_output_mode
assert_only_allowlisted_stage1_artifacts

reset_fake
rm -f "$TRANSCRIPT_OUTPUT"
enable_transcript_collection
export FAKE_ADB_INITIAL_VOICE_TRACE_ABSENT=1
run_scenario livekit_experimental stable_wifi speaker foreground steady 20 >/dev/null
[[ -s "$TRANSCRIPT_OUTPUT" ]] || fail "new trace after an absent initial pointer was not collected"
assert_transcript_output_mode

reset_fake
rm -f "$TRANSCRIPT_OUTPUT"
enable_transcript_collection
export FAKE_ADB_VOICE_EVENTS_EXISTS=0
run_scenario livekit_experimental stable_wifi speaker foreground steady 20 >/dev/null
[[ -f "$TRANSCRIPT_OUTPUT" && ! -s "$TRANSCRIPT_OUTPUT" ]] ||
  fail "proven missing sanitized events did not produce an empty output"
assert_transcript_output_mode

assert_transcript_collection_failure() {
  local expected_mode="$1"
  reset_fake
  rm -f "$TRANSCRIPT_OUTPUT"
  enable_transcript_collection
  case "$expected_mode" in
    stale)
      export FAKE_ADB_INITIAL_VOICE_TRACE_ID=private-stale-trace
      export FAKE_ADB_CURRENT_VOICE_TRACE_ID=private-stale-trace
      ;;
    unsafe)
      export FAKE_ADB_INITIAL_VOICE_TRACE_ID=private-old-trace
      export FAKE_ADB_CURRENT_VOICE_TRACE_ID=../private-unsafe-trace
      ;;
    *)
      export FAKE_ADB_FAIL_MODE="$expected_mode"
      ;;
  esac
  if [[ "$expected_mode" == "voice_events_read" ]]; then
    export FAKE_ADB_NOISY_PRIVATE_FAILURE=1
  fi
  set +e
  output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "transcript collection failure $expected_mode was accepted"
  assert_equals "stage1: unable to collect sanitized transcript evidence" "$output"
  assert_no_unpublished_evidence_temps "$TMP_DIR" "$(dirname "$TRANSCRIPT_OUTPUT")"
  assert_not_contains "$output" "private-stale-trace"
  assert_not_contains "$output" "private-old-trace"
  assert_not_contains "$output" "private-unsafe-trace"
  [[ ! -e "$TRANSCRIPT_OUTPUT" ]] || fail "failed transcript collection manufactured an output"
  if [[ "$expected_mode" == "voice_events_read" ||
        "$expected_mode" == "final_trace_pointer_read" ]]; then
    [[ "$(command_count "action.END")" == "1" ]] ||
      fail "post-call transcript read failure did not end the call exactly once"
    [[ "$(command_count "automation.FINALIZE")" == "1" ]] ||
      fail "post-call transcript read failure did not finalize exactly once"
  fi
  unset FAKE_ADB_FAIL_MODE FAKE_ADB_INITIAL_VOICE_TRACE_ID FAKE_ADB_CURRENT_VOICE_TRACE_ID
}

assert_transcript_collection_failure stale
assert_transcript_collection_failure unsafe
assert_transcript_collection_failure trace_pointer_probe
assert_transcript_collection_failure initial_trace_pointer_read
assert_transcript_collection_failure voice_events_probe
assert_transcript_collection_failure voice_events_read

reset_fake
rm -f "$TRANSCRIPT_OUTPUT"
enable_transcript_collection
export FAKE_ADB_VOICE_EVENTS_SYMLINK=1
set +e
output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
status=$?
set -e
[[ "$status" -ne 0 ]] || fail "symlink sanitized event path was accepted"
assert_equals "stage1: unable to collect sanitized transcript evidence" "$output"
[[ ! -e "$TRANSCRIPT_OUTPUT" ]] || fail "symlink sanitized event path published an output"
[[ "$(command_count "action.END")" == "1" ]] ||
  fail "symlink sanitized event path did not end the call exactly once"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] ||
  fail "symlink sanitized event path did not finalize exactly once"
unset FAKE_ADB_VOICE_EVENTS_SYMLINK

assert_transcript_collection_failure final_trace_pointer_read

reset_fake
rm -f "$TRANSCRIPT_OUTPUT"
enable_transcript_collection
export FAKE_ADB_VOICE_EVENTS_REGULAR=0
set +e
output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
status=$?
set -e
[[ "$status" -ne 0 ]] || fail "non-regular sanitized event path was accepted"
assert_equals "stage1: unable to collect sanitized transcript evidence" "$output"
[[ ! -e "$TRANSCRIPT_OUTPUT" ]] || fail "invalid sanitized event path manufactured an output"
unset VOICE_STAGE1_TRANSCRIPT_PROBE VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH

reset_fake
export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
export VOICE_STAGE1_PROMPT_TRIGGER=unsupported
set +e
output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
status=$?
set -e
[[ "$status" -ne 0 ]] || fail "probe accepted a non-default prompt trigger"
assert_contains "$output" "invalid prompt trigger"
[[ ! -s "$ADB_LOG" ]] || fail "invalid prompt trigger reached ADB"
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH
unset VOICE_STAGE1_PROMPT_TRIGGER

reset_fake
export VOICE_STAGE1_PROMPT_TRIGGER=after_startup_playback_drained
set +e
output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
status=$?
set -e
[[ "$status" -ne 0 ]] || fail "old startup drain trigger was accepted"
assert_contains "$output" "invalid prompt trigger"
[[ ! -s "$ADB_LOG" ]] || fail "old startup drain trigger reached ADB"
unset VOICE_STAGE1_PROMPT_TRIGGER

ROWS=(
  'stable_wifi|speaker|foreground|steady|20'
  'stable_wifi|earpiece|background|steady|60'
  'stable_wifi|speaker|background|interruption|60'
  'cellular|speaker|foreground|steady|20'
  'cellular|earpiece|background|interruption|60'
  'wifi_cellular_wifi|speaker|foreground|reconnect|180'
  'wifi_cellular_wifi|earpiece|background|reconnect|60'
)

reset_fake
export FAKE_ADB_BACKGROUND_NETWORK_BLOCKED=1
blocked_foreground_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20)"
assert_equals 'stage1.run=complete' "$blocked_foreground_output"
call_active_index="$(command_index "dumpsys${separator}activity${separator}services")"
network_status_index="$(last_command_index "automation.STATUS")"
(( network_status_index > call_active_index )) \
  || fail "strict app network readback happened before the call service became active"
unset FAKE_ADB_BACKGROUND_NETWORK_BLOCKED

for row in "${ROWS[@]}"; do
  IFS='|' read -r network route app_state lifecycle target_seconds <<< "$row"
  reset_fake
  run_output="$(run_scenario direct_gemini "$network" "$route" "$app_state" "$lifecycle" "$target_seconds")"
  assert_equals 'stage1.run=complete' "$run_output"
  assert_common_success_contract direct_gemini

  separator=$'\x1f'
  home_needle="shell${separator}input${separator}keyevent${separator}HOME"
  if [[ "$app_state" == "background" ]]; then
    [[ "$(command_count "$home_needle")" == "1" ]] || fail "background row did not press HOME once"
    route_index="$(command_index "automation.ROUTE")"
    home_index="$(command_index "$home_needle")"
    (( home_index > route_index )) || fail "background transition happened before active route observation"
  else
    [[ "$(command_count "$home_needle")" == "0" ]] || fail "foreground row used the background keyevent"
  fi

  arm_needle="me.rerere.rikkahub.debug.voiceagent.ARM_CAPTURE_FIXTURE"
  trigger_needle="me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE"
  start_index="$(command_index "action.START")"
  arm_index="$(command_index "$arm_needle")"
  (( arm_index < start_index )) || fail "fixture was not armed before call start"
  [[ "$(command_count "$arm_needle")" == "1" ]] || fail "fixture was not armed exactly once"
  start_command="$(commands_matching "action.START")"
  [[ "$start_command" == *"captureFixtureToken"* && "$start_command" == *"fixture-1"* ]] ||
    fail "call start did not carry the armed fixture token"
  if [[ "$lifecycle" == "interruption" ]]; then
    [[ "$(command_count "$trigger_needle")" == "1" ]] || fail "interruption row did not trigger once"
    playback_index="$(command_index '\"name\":\"playback_active\"')"
    mark_index="$(command_index "interrupt_started")"
    trigger_index="$(last_command_index "$trigger_needle")"
    (( playback_index < mark_index && mark_index < trigger_index )) ||
      fail "interruption trigger did not follow playback-active and marker"
  else
    [[ "$(command_count "$trigger_needle")" == "0" ]] || fail "non-interruption row triggered another fixture"
  fi

  if [[ "$network" == "wifi_cellular_wifi" ]]; then
    handover_index="$(command_index "handover_started")"
    data_index="$(command_index "svc${separator}data${separator}enable")"
    disable_index="$(command_index "svc${separator}wifi${separator}disable")"
    restore_index="$(last_command_index "svc${separator}wifi${separator}enable")"
    (( handover_index < data_index && data_index < disable_index && disable_index < restore_index )) ||
      fail "handover mutation order was not mark, cellular, Wi-Fi off, Wi-Fi restore"
    [[ "$(command_count "reconnect_started")" == "1" ]] ||
      fail "reconnect row did not emit its start boundary"
    [[ "$(command_count "reconnect_transport_restored")" == "1" ]] ||
      fail "reconnect row did not emit its transport-restored boundary"
    [[ "$(command_count "handover_cellular_observed")" == "1" ]] ||
      fail "handover row did not emit validated cellular evidence"
    [[ "$(command_count "handover_wifi_restored")" == "1" ]] ||
      fail "handover row did not emit validated Wi-Fi restoration evidence"
    [[ "$(command_count "handover_media_restored")" == "1" ]] ||
      fail "handover row did not emit post-restoration media evidence"
    [[ "$(command_count "reconnect_media_restored")" == "1" ]] ||
      fail "reconnect row did not emit its restored-media boundary"
  fi
done

reset_fake
run_scenario livekit_experimental stable_wifi speaker foreground steady 20 >/dev/null
assert_common_success_contract livekit_experimental

reset_fake
export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
export FAKE_CLOCK_STEP=1
probe_output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20)"
assert_contains "$probe_output" 'stage1.startup_probe={"version":1,"classification":"startup-clean","promptOverlap":false,"activeIntervalsMs":[]}'
assert_contains "$probe_output" 'stage1.run=complete'
assert_common_success_contract livekit_experimental
[[ "$(command_count "me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE")" == "1" ]] ||
  fail "startup probe prompt was not triggered exactly once"
python3 - "$TMP_DIR/automation-events.jsonl" "$STARTUP_PCM_PATH" "$PCM_PATH" <<'PY'
import json
import os
import sys

events_path, startup_path, prompt_path = sys.argv[1:]
events = [json.loads(line) for line in open(events_path, encoding="utf-8") if line.strip()]
startup_bytes = os.path.getsize(startup_path)
prompt_bytes = os.path.getsize(prompt_path)
prompt_ends = [event for event in events if event.get("name") == "prompt_ended"]
if [event.get("byteCount") for event in prompt_ends] != [startup_bytes, prompt_bytes]:
    raise SystemExit("probe did not select distinct startup and staged prompt completions")
attestations = [event for event in events if event.get("name") == "capture_attested"]
if [event.get("fixtureBytes") for event in attestations] != [startup_bytes, prompt_bytes]:
    raise SystemExit("probe did not attest both fixtures in order")
post_prompt_attestations = [
    event for event in events
    if event.get("name") == "capture_attested"
    and event["monotonicMs"] > prompt_ends[1]["monotonicMs"]
]
if not post_prompt_attestations or post_prompt_attestations[0].get("fixtureBytes") != prompt_bytes:
    raise SystemExit("probe did not attest the staged prompt after its completion")
PY
assert_private_path_absent "files/voice-stage1/startup.pcm"
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH FAKE_CLOCK_STEP

while IFS='|' read -r timeline classification prompt_overlap expected_status; do
  reset_fake
  export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
  export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
  export FAKE_CLOCK_STEP=1
  export FAKE_ADB_PROBE_TIMELINE="$timeline"
  set +e
  timeline_output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  timeline_status=$?
  set -e
  [[ "$timeline_status" == "$expected_status" ]] ||
    fail "startup probe timeline $timeline returned $timeline_status, expected $expected_status"
  assert_contains "$timeline_output" "stage1.startup_probe={\"version\":1,\"classification\":\"$classification\",\"promptOverlap\":$prompt_overlap,\"activeIntervalsMs\":"
  if [[ "$timeline" == "call_active_before_attach_active" ]]; then
    assert_contains "$timeline_output" 'stage1.startup_probe={"version":1,"classification":"startup-audio-active","promptOverlap":false,"activeIntervalsMs":[[5,10]]}'
  fi
  if [[ "$classification" == "startup-audio-active" ]]; then
    assert_not_contains "$timeline_output" '"activeIntervalsMs":[]'
  fi
  if [[ "$expected_status" == "0" ]]; then
    assert_contains "$timeline_output" 'stage1.run=complete'
  else
    assert_not_contains "$timeline_output" 'stage1.run=complete'
    assert_contains "$timeline_output" 'startup probe did not produce a valid vertical slice'
    [[ -s "$TMP_DIR/automation-events.jsonl" ]] ||
      fail "classified startup probe timeline $timeline did not preserve its artifact"
    [[ "$(stat -c '%a' "$TMP_DIR/automation-events.jsonl")" == "600" ]] ||
      fail "classified startup probe timeline $timeline artifact was not mode 0600"
    assert_not_contains "$timeline_output" "$PCM_PATH"
    assert_not_contains "$timeline_output" "$STARTUP_PCM_PATH"
    assert_not_contains "$timeline_output" "$RUN_HASH"
    assert_not_contains "$timeline_output" "$COMPARISON_HASH"
    [[ "$(command_count "action.END")" == "1" ]] ||
      fail "classified startup probe timeline $timeline did not end exactly once"
    [[ "$(command_count "automation.FINALIZE")" == "1" ]] ||
      fail "classified startup probe timeline $timeline did not finalize exactly once"
  fi
done <<'EOF'
active|startup-audio-active|false|1
missing|startup-indeterminate|false|1
gap|startup-indeterminate|false|1
detached|startup-indeterminate|false|1
call_active_before_attach|startup-clean|false|0
call_active_before_attach_active|startup-audio-active|false|1
missing_attach|startup-indeterminate|false|1
duplicate_attach|startup-indeterminate|false|1
late_attach|startup-indeterminate|false|1
missing_call_active|startup-indeterminate|false|1
duplicate_call_active|startup-indeterminate|false|1
late_call_active|startup-indeterminate|false|1
malformed|startup-indeterminate|false|1
straddle|startup-indeterminate|true|1
prompt_overlap|startup-clean|true|1
EOF
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH
unset FAKE_CLOCK_STEP FAKE_ADB_PROBE_TIMELINE

for timeline in missing_response legacy_only; do
  reset_fake
  export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
  export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
  export FAKE_CLOCK_STEP=1
  export FAKE_ADB_PROBE_TIMELINE="$timeline"
  set +e
  timeline_output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
  timeline_status=$?
  set -e
  [[ "$timeline_status" -ne 0 ]] || fail "startup probe timeline $timeline accepted missing RMS response evidence"
  assert_contains "$timeline_output" 'stage1.startup_probe={"version":1,"classification":"startup-clean","promptOverlap":false,"activeIntervalsMs":[]}'
  assert_not_contains "$timeline_output" 'stage1.run=complete'
  [[ -s "$TMP_DIR/automation-events.jsonl" ]] ||
    fail "startup probe timeline $timeline did not preserve its classified artifact"
  [[ "$(stat -c '%a' "$TMP_DIR/automation-events.jsonl")" == "600" ]] ||
    fail "startup probe timeline $timeline artifact was not mode 0600"
done
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH
unset FAKE_CLOCK_STEP FAKE_ADB_PROBE_TIMELINE

reset_fake
export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
export FAKE_CLOCK_MODE=probe_frozen
set +e
frozen_probe_output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
frozen_probe_status=$?
set -e
[[ "$frozen_probe_status" -ne 0 ]] || fail "frozen startup probe clock was accepted"
assert_contains "$frozen_probe_output" "wait attempt limit reached for startup truth probe pre-roll"
[[ "$(command_count "me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE")" == "0" ]] ||
  fail "frozen startup probe triggered the staged prompt"
[[ "$(command_count "action.END")" == "1" ]] || fail "frozen startup probe did not end once"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] ||
  fail "frozen startup probe did not finalize once"
assert_private_path_absent "files/voice-stage1/startup.pcm"
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH FAKE_CLOCK_MODE

reset_fake
export VOICE_STAGE1_STARTUP_TRUTH_PROBE=1
export VOICE_STAGE1_STARTUP_PCM_PATH="$STARTUP_PCM_PATH"
export FAKE_CLOCK_MODE=probe_backward
set +e
backward_probe_output="$(run_scenario livekit_experimental stable_wifi speaker foreground steady 20 2>&1)"
backward_probe_status=$?
set -e
[[ "$backward_probe_status" -ne 0 ]] || fail "backward startup probe clock was accepted"
assert_contains "$backward_probe_output" "clock moved backward during startup truth probe pre-roll"
[[ "$(command_count "me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE")" == "0" ]] ||
  fail "backward startup probe triggered the staged prompt"
[[ "$(command_count "action.END")" == "1" ]] || fail "backward startup probe did not end once"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] ||
  fail "backward startup probe did not finalize once"
unset VOICE_STAGE1_STARTUP_TRUTH_PROBE VOICE_STAGE1_STARTUP_PCM_PATH FAKE_CLOCK_MODE

reset_fake
export FAKE_ADB_ARM_MALFORMED=1
set +e
missing_arm_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
missing_arm_status=$?
set -e
[[ "$missing_arm_status" -ne 0 ]] || fail "runner accepted missing fixture arm readback"
assert_contains "$missing_arm_output" "fixture arm returned malformed data"
[[ "$(command_count "action.START")" == "0" ]] || fail "missing fixture arm readback started a call"
unset FAKE_ADB_ARM_MALFORMED

reset_fake
export FAKE_ADB_SUPPRESS_CAPTURE_ATTESTATION=1
set +e
missing_attestation_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
missing_attestation_status=$?
set -e
[[ "$missing_attestation_status" -ne 0 ]] || fail "runner accepted missing capture attestation"
assert_equals "stage1: finalized automation event validation failed" "$missing_attestation_output"
unset FAKE_ADB_SUPPRESS_CAPTURE_ATTESTATION

reset_fake
export FAKE_ADB_EMPTY_CALL_ACTIVE_SNAPSHOT_ONCE=1
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null
assert_common_success_contract direct_gemini
unset FAKE_ADB_EMPTY_CALL_ACTIVE_SNAPSHOT_ONCE

reset_fake
export FAKE_ADB_FOREIGN_CALL_ACTIVE_SNAPSHOT_ONCE=1
set +e
foreign_boundary_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
foreign_boundary_status=$?
set -e
[[ "$foreign_boundary_status" -ne 0 ]] ||
  fail "foreign event identity was accepted as an ordering boundary"
assert_contains "$foreign_boundary_output" "event run hash mismatch"
unset FAKE_ADB_FOREIGN_CALL_ACTIVE_SNAPSHOT_ONCE

reset_fake
export FAKE_ADB_ROUTE_MODE=delayed
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null
assert_common_success_contract direct_gemini
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_ROUTE_MODE=stale
set +e
stale_route_output="$(run_scenario direct_gemini stable_wifi speaker background steady 20 2>&1)"
stale_route_status=$?
set -e
[[ "$stale_route_status" -ne 0 ]] || fail "stale pre-request route observation was accepted"
assert_contains "$stale_route_output" "timed out waiting for fresh route_observed"
separator=$'\x1f'
[[ "$(command_count "shell${separator}input${separator}keyevent${separator}HOME")" == "0" ]] ||
  fail "background transition ran before a fresh route observation"
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_ROUTE_MODE=precommand_pair
set +e
precommand_route_output="$(run_scenario direct_gemini stable_wifi speaker background steady 20 2>&1)"
precommand_route_status=$?
set -e
[[ "$precommand_route_status" -ne 0 ]] || fail "pre-command route pair satisfied the current ROUTE request"
assert_contains "$precommand_route_output" "timed out waiting for fresh route_observed"
separator=$'\x1f'
[[ "$(command_count "shell${separator}input${separator}keyevent${separator}HOME")" == "0" ]] ||
  fail "background transition ran without a callback for the current ROUTE request"
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_ROUTE_MODE=conflicting
set +e
conflicting_route_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
conflicting_route_status=$?
set -e
[[ "$conflicting_route_status" -ne 0 ]] || fail "conflicting delayed route observation was accepted"
assert_contains "$conflicting_route_output" "conflicting route observation"
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_LIFECYCLE_MODE=stale
set +e
stale_lifecycle_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
stale_lifecycle_status=$?
set -e
[[ "$stale_lifecycle_status" -ne 0 ]] || fail "stale pre-action lifecycle observation was accepted"
assert_contains "$stale_lifecycle_output" "timed out waiting for fresh lifecycle_observed"
unset FAKE_ADB_LIFECYCLE_MODE

reset_fake
export FAKE_ADB_LIFECYCLE_MODE=preaction_race
set +e
preaction_lifecycle_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
preaction_lifecycle_status=$?
set -e
[[ "$preaction_lifecycle_status" -ne 0 ]] || fail "pre-action lifecycle event passed without Android state transition"
assert_contains "$preaction_lifecycle_output" "lifecycle activity readback mismatch"
unset FAKE_ADB_LIFECYCLE_MODE

reset_fake
export FAKE_ADB_APP_NETWORK=cellular
set +e
network_mismatch="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
network_mismatch_status=$?
set -e
[[ "$network_mismatch_status" -ne 0 ]] || fail "runner accepted app/Android network disagreement"
assert_contains "$network_mismatch" "network observation mismatch"
unset FAKE_ADB_APP_NETWORK

reset_fake
export FAKE_ADB_OBSERVED_TRANSPORT=livekit_experimental
set +e
transport_mismatch="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
transport_mismatch_status=$?
set -e
[[ "$transport_mismatch_status" -ne 0 ]] || fail "runner accepted observed transport mismatch"
assert_contains "$transport_mismatch" "observed transport mismatch"
unset FAKE_ADB_OBSERVED_TRANSPORT

reset_fake
export FAKE_ADB_FAIL_MODE=route_rejected
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
route_status=$?
set -e
[[ "$route_status" -ne 0 ]] || fail "runner accepted rejected route mutation"
separator=$'\x1f'
[[ "$(command_count "action.END")" == "1" ]] || fail "known failure did not end the active call"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] || fail "known failure did not finalize the active run"
assert_contains "$(command_lines)" "rm${separator}-f${separator}files/voice-stage1/prompt.pcm"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=prepare
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
prepare_status=$?
set -e
[[ "$prepare_status" -ne 0 ]] || fail "ambiguous prepare unexpectedly succeeded"
[[ "$(command_count "automation.PREPARE")" == "1" ]] || fail "ambiguous prepare was retried"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] || fail "ambiguous prepare did not finalize once"
[[ "$(command_count "action.START")" == "0" ]] || fail "ambiguous prepare started a call"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=prepare_foreign
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
prepare_foreign_status=$?
set -e
[[ "$prepare_foreign_status" -ne 0 ]] || fail "foreign ambiguous prepare unexpectedly succeeded"
[[ "$(command_count "automation.PREPARE")" == "1" ]] || fail "foreign ambiguous prepare was retried"
[[ "$(command_count "automation.FINALIZE")" == "0" ]] || fail "cleanup finalized foreign ambiguous run"
[[ "$(command_count "action.END")" == "0" ]] || fail "foreign ambiguous prepare ended an unowned call"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=stage_interrupt
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
stage_status=$?
set -e
[[ "$stage_status" -ne 0 ]] || fail "partial fixture staging unexpectedly succeeded"
separator=$'\x1f'
assert_contains "$(command_lines)" "rm${separator}-f${separator}files/voice-stage1/prompt.pcm"
[[ "$(command_count "action.START")" == "0" ]] || fail "partial fixture staging started a call"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] || fail "partial fixture staging did not finalize"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=start
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
start_status=$?
set -e
[[ "$start_status" -ne 0 ]] || fail "ambiguous start unexpectedly succeeded"
[[ "$(command_count "action.START")" == "1" ]] || fail "ambiguous start was retried"
[[ "$(command_count "action.END")" == "1" ]] || fail "ambiguous start did not perform one cleanup end"
assert_not_contains "$(command_lines)" "install"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=wifi_disable
set +e
run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 >/dev/null 2>&1
mutation_status=$?
set -e
[[ "$mutation_status" -ne 0 ]] || fail "ambiguous network mutation unexpectedly succeeded"
separator=$'\x1f'
[[ "$(command_count "svc${separator}wifi${separator}disable")" == "1" ]] || fail "ambiguous mutation was retried"
[[ "$(command_count "action.START")" == "1" ]] || fail "failure retried the call start"
[[ "$(command_count "action.END")" == "1" ]] || fail "mutation failure did not clean up the call"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=wifi_restore
set +e
run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 >/dev/null 2>&1
restore_status=$?
set -e
[[ "$restore_status" -ne 0 ]] || fail "ambiguous Wi-Fi restore unexpectedly succeeded"
[[ "$(count_wifi_enables_after_last_disable)" == "1" ]] || fail "ambiguous Wi-Fi restore was retried"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_UNVALIDATED_AFTER_RESTORE=1
set +e
unvalidated_restore_output="$(run_scenario direct_gemini cellular speaker foreground steady 20 2>&1)"
unvalidated_restore_status=$?
set -e
[[ "$unvalidated_restore_status" -ne 0 ]] || fail "unvalidated Wi-Fi cleanup was marked proven"
assert_not_contains "$unvalidated_restore_output" "stage1.run=complete"
[[ "$(count_wifi_enables_after_last_disable)" == "1" ]] || fail "unvalidated cleanup retried Wi-Fi restore"
unset FAKE_ADB_UNVALIDATED_AFTER_RESTORE

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=playback_written
set +e
recovery_output="$(run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 2>&1)"
recovery_status=$?
set -e
[[ "$recovery_status" -ne 0 ]] || fail "missing post-handover media restoration was accepted"
assert_contains "$recovery_output" "timed out waiting for post-handover playback_written"
unset FAKE_ADB_SUPPRESS_EVENT

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=reconnect_transport_restored
set +e
transport_recovery_output="$(run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 2>&1)"
transport_recovery_status=$?
set -e
[[ "$transport_recovery_status" -ne 0 ]] || fail "missing transport-owned restoration was accepted"
assert_contains "$transport_recovery_output" "timed out waiting for reconnect_transport_restored"
unset FAKE_ADB_SUPPRESS_EVENT

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=call_active
set +e
timeout_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
timeout_status=$?
set -e
[[ "$timeout_status" -ne 0 ]] || fail "missing event wait was unbounded or accepted"
assert_contains "$timeout_output" "timed out waiting for call_active"
[[ -s "$CLOCK_LOG" ]] || fail "bounded waits did not use the injected clock"
unset FAKE_ADB_SUPPRESS_EVENT

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=call_active
export FAKE_CLOCK_MODE=frozen
set +e
frozen_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
frozen_status=$?
set -e
[[ "$frozen_status" -ne 0 ]] || fail "frozen injected clock produced an unbounded or successful wait"
assert_contains "$frozen_output" "wait attempt limit reached for call_active"
[[ "$(command_count '\"name\":\"call_active\"')" -le 8 ]] || fail "frozen clock exceeded wait attempt cap"
unset FAKE_ADB_SUPPRESS_EVENT FAKE_CLOCK_MODE

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=call_active
export FAKE_CLOCK_MODE=backward
set +e
backward_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
backward_status=$?
set -e
[[ "$backward_status" -ne 0 ]] || fail "backward injected clock was accepted"
assert_contains "$backward_output" "clock moved backward while waiting for call_active"
[[ "$(command_count '\"name\":\"call_active\"')" -le 2 ]] || fail "backward clock continued polling"
unset FAKE_ADB_SUPPRESS_EVENT FAKE_CLOCK_MODE

printf 'voice-agent-stage1-e2e tests passed.\n'
