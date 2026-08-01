#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/scripts/voice-agent-stage1-e2e.sh"
TMP_DIR="$(mktemp -d)"
BIN_DIR="$TMP_DIR/bin"
STATE_DIR="$TMP_DIR/state"
ADB_LOG="$TMP_DIR/adb-argv.bin"
CLOCK_LOG="$TMP_DIR/clock-argv.bin"
LOCK_DIR="$TMP_DIR/locks"
PCM_PATH="$TMP_DIR/prompt.pcm"
INTERRUPT_PCM_PATH="$TMP_DIR/interrupt.pcm"
STARTUP_PCM_PATH="$TMP_DIR/startup.pcm"
SERIAL="RZCX71NXRPB"
PACKAGE="me.rerere.rikkahub.debug"
RUN_HASH="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
COMPARISON_HASH="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$BIN_DIR" "$STATE_DIR" "$LOCK_DIR"
printf 'primary-pcm' > "$PCM_PATH"
printf 'interrupt-pcm' > "$INTERRUPT_PCM_PATH"
printf 'startup-silence' > "$STARTUP_PCM_PATH"

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
        state["call_started"] = True
        emit("call_start_requested", observed_transport=state["transport"])
        save()
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "start":
            sys.exit(74)
        observed = os.environ.get("FAKE_ADB_OBSERVED_TRANSPORT", state["transport"])
        startup_probe = os.environ.get("VOICE_STAGE1_STARTUP_TRUTH_PROBE") == "1"
        probe_timeline = os.environ.get("FAKE_ADB_PROBE_TIMELINE", "clean")
        if startup_probe:
            emit("remote_track_attached", playback_epoch=1)
            if probe_timeline == "duplicate_attach":
                emit("remote_track_attached", playback_epoch=1)
        if os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "call_active":
            emit("call_active", observed_transport=observed)
        if startup_probe and probe_timeline != "missing":
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
            if not startup_probe:
                emit("remote_audio_first_non_silent", playback_epoch=1)
                emit("playback_active", playback_epoch=1)
        save()
    else:
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
                elif probe_timeline == "misordered_call_active":
                    initial_start = next(
                        event["monotonicMs"] for event in parsed_events
                        if event.get("name") == "injection_started"
                        and event.get("byteCount") == state["fixture_initial_bytes"]
                    )
                    for event in parsed_events:
                        if event.get("name") == "call_active":
                            event["monotonicMs"] = initial_start + 1
                            event["wallClockMs"] = 1_800_000_000_000 + initial_start + 1
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

reset_fake() {
  rm -rf "$STATE_DIR"
  mkdir -p "$STATE_DIR"
  : > "$ADB_LOG"
  : > "$CLOCK_LOG"
  rm -f "$TMP_DIR/clock-counter" "$TMP_DIR/clock-counter.calls"
  rm -f "$LOCK_DIR"/*
  unset FAKE_ADB_DEVICES_MODE FAKE_ADB_FAIL_MODE FAKE_ADB_OBSERVED_TRANSPORT
  unset FAKE_ADB_APP_NETWORK FAKE_ADB_SUPPRESS_EVENT FAKE_ADB_EMULATOR
  unset FAKE_ADB_ROUTE_MODE FAKE_ADB_LIFECYCLE_MODE FAKE_CLOCK_MODE FAKE_ADB_INITIAL_RUN
  unset FAKE_ADB_UNVALIDATED_AFTER_RESTORE FAKE_ADB_STATUS_COLD_START
  unset FAKE_ADB_BACKGROUND_NETWORK_BLOCKED
  unset FAKE_ADB_STATUS_MALFORMED FAKE_ADB_STAGE_VISIBILITY_DELAY
  unset FAKE_ADB_ARM_MALFORMED FAKE_ADB_SUPPRESS_CAPTURE_ATTESTATION
  unset FAKE_ADB_PROBE_TIMELINE FAKE_CLOCK_STEP
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
    VOICE_STAGE1_MAX_WAIT_ATTEMPTS=8 \
    VOICE_STAGE1_LOCK_DIR="$LOCK_DIR" \
    VOICE_STAGE1_SERIAL="$SERIAL" \
    VOICE_STAGE1_PACKAGE="$PACKAGE" \
    "$@"
}

run_scenario() {
  local transport="$1"
  local network="$2"
  local route="$3"
  local app_state="$4"
  local lifecycle="$5"
  local target_seconds="$6"
  local output="$TMP_DIR/automation-events.jsonl"
  rm -f "$output"
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
duplicate_attach|startup-indeterminate|false|1
missing_call_active|startup-indeterminate|false|1
misordered_call_active|startup-indeterminate|false|1
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
assert_contains "$missing_attestation_output" "missing required automation event: capture_attested"
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
