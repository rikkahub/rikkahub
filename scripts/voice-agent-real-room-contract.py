#!/usr/bin/env python3
"""Pure, fixed checkpoint predicates for real-room voice evidence."""

from __future__ import annotations

import enum
import hashlib
import json
import re
import sys
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class Expectation(enum.StrEnum):
    SINGLE_RESULT_ANNOUNCED = "single_result_announced"
    SINGLE_FOLLOW_UP_GROUNDED = "single_follow_up_grounded"
    PARALLEL_FIRST_PENDING = "parallel_first_pending"
    PARALLEL_LATER_COMPLETED_FIRST = "parallel_later_completed_first"
    PARALLEL_BOTH_ANNOUNCED = "parallel_both_announced"
    INTERRUPTION_DELIVERY_ACTIVE = "interruption_delivery_active"
    INTERRUPTION_OBSERVED = "interruption_observed"
    INTERRUPTION_RECOVERED = "interruption_recovered"
    ISOLATION_FIRST_ACTIVE = "isolation_first_active"
    ISOLATION_TWO_DISTINCT = "isolation_two_distinct"
    ISOLATION_TERMINAL_HEALTHY = "isolation_terminal_healthy"


class ContractError(Exception):
    def __init__(self, boundary: str):
        super().__init__(boundary)
        self.boundary = boundary


JOB_IDENTITY_FIELDS = (
    "userTurnId",
    "requestHash",
    "toolCallId",
    "argumentHash",
    "jobId",
    "ownerHash",
    "conversationHash",
    "voiceSessionHash",
    "roomHash",
    "traceHash",
)
TERMINAL_KINDS = {"job_succeeded", "job_failed", "job_expired", "job_canceled"}
FAILURE_KINDS = {"job_failed", "job_expired", "job_canceled"}
DELIVERY_KINDS = {
    "delivery_eligible",
    "delivery_started",
    "speech_started",
    "delivery_blocked",
    "delivery_announced",
}
QUIET_RESET_NAMES = {
    "interrupt_started",
    "playback_active",
    "playback_stopped",
    "playback_drained",
}
MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
HASH = re.compile(r"sha256:[0-9a-f]{64}")
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,128}")
VOICE_IDENTIFIERS = {
    "eventId",
    "userTurnId",
    "toolCallId",
    "jobId",
    "turnId",
    "groundedJobId",
    "assistantTurnId",
    "followUpTurnId",
}
VOICE_HASHES = {
    "voiceSessionHash",
    "eventHash",
    "requestHash",
    "argumentHash",
    "ownerHash",
    "conversationHash",
    "roomHash",
    "traceHash",
    "resultHash",
    "groundedResultHash",
}
VOICE_COUNTS = {
    "promptCharacterCount",
    "answerCharacterCount",
    "failureReasonCharacterCount",
    "textCharacterCount",
}
VOICE_BOOLEANS = {"interrupted", "userSpeaking", "agentSpeaking"}
CANONICAL_INSTANT = re.compile(
    r"[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.(?:[0-9]{3}|[0-9]{6}|[0-9]{9}))?Z"
)
AUTOMATION_KEYS = (
    "schemaVersion",
    "monotonicMs",
    "wallClockMs",
    "runHash",
    "comparisonHash",
    "requestedTransport",
    "observedTransport",
    "name",
    "route",
    "network",
    "lifecycle",
    "playbackEpoch",
    "byteCount",
    "rmsActive",
    "audioWindowMicros",
    "succeeded",
    "correlationKind",
    "correlationHash",
    "requestedModelHash",
    "observedModelHash",
    "voiceHash",
    "instructionHash",
    "directAccountConfigurationHash",
    "conversationHash",
    "captureSource",
    "micBytes",
    "fixtureBytes",
)
VOICE_BASE = {"version", "voiceSessionHash", "eventId", "kind", "observedAt", "eventHash"}
VOICE_JOB = set(JOB_IDENTITY_FIELDS) - {"voiceSessionHash"}
VOICE_SCHEMAS = {
    "session_binding": VOICE_BASE | {"ownerHash", "conversationHash", "roomHash", "traceHash"},
    "job_accepted": VOICE_BASE | VOICE_JOB | {"promptCharacterCount"},
    "job_running": VOICE_BASE | VOICE_JOB,
    "still_working": VOICE_BASE | VOICE_JOB,
    "job_succeeded": VOICE_BASE | VOICE_JOB | {"resultHash", "answerCharacterCount"},
    "job_failed": VOICE_BASE | VOICE_JOB | {"failureReasonCharacterCount"},
    "job_expired": VOICE_BASE | VOICE_JOB | {"failureReasonCharacterCount"},
    "job_canceled": VOICE_BASE | VOICE_JOB | {"failureReasonCharacterCount"},
    "delivery_eligible": VOICE_BASE | {"toolCallId", "jobId"},
    "delivery_started": VOICE_BASE | {"toolCallId", "jobId"},
    "speech_started": VOICE_BASE | {"toolCallId", "jobId"},
    "delivery_blocked": VOICE_BASE | {"toolCallId", "jobId", "userSpeaking", "agentSpeaking"},
    "delivery_announced": VOICE_BASE | {"toolCallId", "jobId", "assistantTurnId"},
    "follow_up_correlation": VOICE_BASE | {"followUpTurnId", "assistantTurnId", "resultHash"},
}
FINALIZATION_KEYS = {
    "schemaVersion",
    "outcome",
    "reason",
    "callStopped",
    "automationFinalized",
    "forcedFallbackUsed",
}
CLEANUP_KEYS = {
    "schemaVersion",
    "outcome",
    "callStopped",
    "automationFinalized",
    "fixturesRemoved",
    "finalizationHash",
}
FINALIZATION_REASON_TERMINALS = {
    "complete": ("complete", True, True, False),
    "bound_call_rejected": ("product_failure", False, False, False),
    "call_stop_failed": ("product_failure", False, False, False),
    "call_stop_timeout": ("product_failure", False, False, False),
    "persistence_drain_failed": ("product_failure", False, False, False),
    "automation_finalize_rejected": ("product_failure", True, False, False),
    "automation_finalize_failed": ("product_failure", True, False, False),
    "forced_fallback_used": ("product_failure", False, False, True),
    "device_unavailable": ("infrastructure_interruption", False, False, False),
    "adb_route_unavailable": ("infrastructure_interruption", False, False, False),
}
PRIVATE_BASE = {"version", "voiceSessionId", "eventId", "kind", "observedAt"}
PRIVATE_JOB = set(JOB_IDENTITY_FIELDS) - {"voiceSessionHash"}
PRIVATE_SCHEMAS = {
    "session_binding": PRIVATE_BASE
    | {"ownerHash", "conversationHash", "voiceSessionHash", "roomHash", "traceHash"},
    "job_accepted": PRIVATE_BASE
    | PRIVATE_JOB
    | {"voiceSessionHash", "prompt"},
    "job_running": PRIVATE_BASE | PRIVATE_JOB | {"voiceSessionHash"},
    "still_working": PRIVATE_BASE | PRIVATE_JOB | {"voiceSessionHash"},
    "job_succeeded": PRIVATE_BASE
    | PRIVATE_JOB
    | {"voiceSessionHash", "resultHash", "answer"},
    "job_failed": PRIVATE_BASE
    | PRIVATE_JOB
    | {"voiceSessionHash", "failureReason"},
    "job_expired": PRIVATE_BASE
    | PRIVATE_JOB
    | {"voiceSessionHash", "failureReason"},
    "job_canceled": PRIVATE_BASE
    | PRIVATE_JOB
    | {"voiceSessionHash", "failureReason"},
    "delivery_eligible": PRIVATE_BASE | {"toolCallId", "jobId"},
    "delivery_started": PRIVATE_BASE | {"toolCallId", "jobId"},
    "speech_started": PRIVATE_BASE | {"toolCallId", "jobId"},
    "delivery_blocked": PRIVATE_BASE
    | {"toolCallId", "jobId", "userSpeaking", "agentSpeaking"},
    "delivery_announced": PRIVATE_BASE
    | {"toolCallId", "jobId", "assistantTurnId"},
    "follow_up_correlation": PRIVATE_BASE
    | {"followUpTurnId", "assistantTurnId", "resultHash"},
}


def _require(condition: bool, boundary: str) -> None:
    if not condition:
        raise ContractError(boundary)


def _require_canonical_value(value: object) -> None:
    if isinstance(value, Mapping):
        for key, member in value.items():
            _require(type(key) is str, "canonical_json")
            _require_canonical_value(member)
        return
    if isinstance(value, list):
        for member in value:
            _require_canonical_value(member)
        return
    _require(type(value) in {str, bool, int}, "canonical_json")


def canonical_json_bytes(value: Mapping[str, object]) -> bytes:
    _require(isinstance(value, Mapping), "canonical_json")
    _require_canonical_value(value)
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, OverflowError) as error:
        raise ContractError("canonical_json") from error


def sha256_bytes(value: bytes | bytearray | memoryview) -> str:
    return "sha256:" + hashlib.sha256(bytes(value)).hexdigest()


def parse_canonical_json_bytes(content: bytes) -> dict[str, object]:
    _require(
        bool(content)
        and len(content) <= 65536
        and not content.startswith(b"\xef\xbb\xbf")
        and b"\r" not in content
        and not content.endswith(b"\n"),
        "canonical_json",
    )
    try:
        text = content.decode("utf-8")
        pairs = json.loads(text, object_pairs_hook=lambda value: value)
    except (UnicodeDecodeError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise ContractError("canonical_json") from error
    _require(
        type(pairs) is list
        and all(type(pair) is tuple and len(pair) == 2 for pair in pairs),
        "canonical_json",
    )
    keys = [key for key, _ in pairs]
    _require(
        all(type(key) is str for key in keys)
        and len(keys) == len(set(keys)),
        "canonical_json",
    )
    value = dict(pairs)
    _require(canonical_json_bytes(value) == content, "canonical_json")
    return value


def validate_finalization(value: object) -> dict[str, object]:
    _require(type(value) is dict and set(value) == FINALIZATION_KEYS, "finalization")
    finalization = dict(value)
    _require(
        type(finalization["schemaVersion"]) is int
        and finalization["schemaVersion"] == 1
        and type(finalization["outcome"]) is str
        and type(finalization["reason"]) is str
        and type(finalization["callStopped"]) is bool
        and type(finalization["automationFinalized"]) is bool
        and type(finalization["forcedFallbackUsed"]) is bool,
        "finalization",
    )
    outcome = finalization["outcome"]
    reason = finalization["reason"]
    call_stopped = finalization["callStopped"]
    automation_finalized = finalization["automationFinalized"]
    forced_fallback = finalization["forcedFallbackUsed"]
    _require(
        FINALIZATION_REASON_TERMINALS.get(reason)
        == (outcome, call_stopped, automation_finalized, forced_fallback),
        "finalization",
    )
    return finalization


def validate_cleanup(
    value: object,
    finalization: object | None = None,
) -> dict[str, object]:
    _require(type(value) is dict and set(value) == CLEANUP_KEYS, "cleanup")
    cleanup = dict(value)
    _require(
        type(cleanup["schemaVersion"]) is int
        and cleanup["schemaVersion"] == 2
        and type(cleanup["outcome"]) is str
        and cleanup["outcome"]
        in {"complete", "product_failure", "infrastructure_interruption"}
        and type(cleanup["callStopped"]) is bool
        and type(cleanup["automationFinalized"]) is bool
        and type(cleanup["fixturesRemoved"]) is bool
        and type(cleanup["finalizationHash"]) is str
        and HASH.fullmatch(cleanup["finalizationHash"]) is not None
        and (not cleanup["automationFinalized"] or cleanup["callStopped"]),
        "cleanup",
    )
    outcome = cleanup["outcome"]
    if outcome == "complete":
        _require(
            cleanup["callStopped"]
            and cleanup["automationFinalized"]
            and cleanup["fixturesRemoved"],
            "cleanup",
        )
    elif outcome == "product_failure":
        _require(not cleanup["automationFinalized"], "cleanup")
    else:
        _require(
            not cleanup["callStopped"] and not cleanup["automationFinalized"],
            "cleanup",
        )
    if finalization is not None:
        validated_finalization = validate_finalization(finalization)
        _require(
            cleanup["outcome"] == validated_finalization["outcome"]
            and cleanup["callStopped"] == validated_finalization["callStopped"]
            and cleanup["automationFinalized"]
            == validated_finalization["automationFinalized"]
            and cleanup["finalizationHash"]
            == sha256_bytes(canonical_json_bytes(validated_finalization)),
            "cleanup",
        )
    return cleanup


def _identity(row: dict[str, Any]) -> tuple[Any, ...]:
    return tuple(row.get(field) for field in JOB_IDENTITY_FIELDS)


def _delivery_identity(row: dict[str, Any]) -> tuple[Any, Any]:
    return row.get("toolCallId"), row.get("jobId")


def jobs_by_identity(voice: Sequence[dict[str, Any]]) -> dict[tuple[Any, ...], list[dict[str, Any]]]:
    jobs: dict[tuple[Any, ...], list[dict[str, Any]]] = {}
    for row in voice:
        if row.get("kind") in {"job_accepted", "job_running", "still_working", *TERMINAL_KINDS}:
            jobs.setdefault(_identity(row), []).append(row)
    return jobs


def ordered_event(
    rows: Sequence[dict[str, Any]],
    kind: str,
    predicate=lambda row: True,
    after: int = -1,
) -> int | None:
    for index in range(after + 1, len(rows)):
        row = rows[index]
        if row.get("kind", row.get("name")) == kind and predicate(row):
            return index
    return None


def playback_epochs(automation: Sequence[dict[str, Any]]) -> dict[int, list[dict[str, Any]]]:
    epochs: dict[int, list[dict[str, Any]]] = {}
    for row in automation:
        epoch = row.get("playbackEpoch")
        if type(epoch) is int and epoch > 0:
            epochs.setdefault(epoch, []).append(row)
    return epochs


def first_quiet_after_last_reset(
    automation: Sequence[dict[str, Any]],
    before_ms: int | None = None,
) -> int | None:
    observations = sorted(
        (
        row
        for row in automation
        if type(row.get("monotonicMs")) is int
        and (before_ms is None or row["monotonicMs"] < before_ms)
        ),
        key=lambda row: row["monotonicMs"],
    )
    quiet_start = None
    for row in observations:
        name = row.get("name")
        if name in QUIET_RESET_NAMES or (
            name == "playback_written" and row.get("rmsActive") is True
        ):
            quiet_start = None
        elif (
            name == "playback_written"
            and row.get("rmsActive") is False
            and quiet_start is None
        ):
            quiet_start = row["monotonicMs"]
    return quiet_start


def _accepted(voice: Sequence[dict[str, Any]], boundary: str, count: int) -> list[tuple[int, dict[str, Any]]]:
    rows = [(index, row) for index, row in enumerate(voice) if row.get("kind") == "job_accepted"]
    _require(len(rows) == count, boundary)
    identities = [_identity(row) for _, row in rows]
    _require(len(set(identities)) == len(identities), boundary)
    return rows


def _events_for_identity(
    voice: Sequence[dict[str, Any]], kind: str, identity: tuple[Any, ...]
) -> list[tuple[int, dict[str, Any]]]:
    return [
        (index, row)
        for index, row in enumerate(voice)
        if row.get("kind") == kind and _identity(row) == identity
    ]


def _delivery_events(
    voice: Sequence[dict[str, Any]], kind: str, identity: tuple[Any, ...]
) -> list[tuple[int, dict[str, Any]]]:
    pair = identity[2], identity[4]
    return [
        (index, row)
        for index, row in enumerate(voice)
        if row.get("kind") == kind and _delivery_identity(row) == pair
    ]


def _single_result(voice: Sequence[dict[str, Any]]) -> tuple[tuple[Any, ...], str, str, int]:
    accepted_rows = _accepted(voice, "single_accepted_job", 1)
    accepted_index, accepted_row = accepted_rows[0]
    identity = _identity(accepted_row)
    succeeded = _events_for_identity(voice, "job_succeeded", identity)
    _require(len(succeeded) == 1 and succeeded[0][0] > accepted_index, "single_succeeded_identity")
    success_index, success = succeeded[0]
    result_hash = success.get("resultHash")
    _require(type(result_hash) is str, "single_succeeded_identity")
    eligible = _delivery_events(voice, "delivery_eligible", identity)
    speech = _delivery_events(voice, "speech_started", identity)
    started = _delivery_events(voice, "delivery_started", identity)
    _require(len(eligible) == len(speech) == len(started) == 1, "single_delivery_order")
    grounded = [
        (index, row)
        for index, row in enumerate(voice)
        if row.get("kind") == "transcript"
        and row.get("role") == "assistant"
        and row.get("groundedJobId") == identity[4]
        and row.get("groundedResultHash") == result_hash
    ]
    _require(len(grounded) == 1, "single_grounded_assistant")
    grounded_index, grounded_row = grounded[0]
    assistant_turn = grounded_row.get("turnId")
    announced = [
        (index, row)
        for index, row in _delivery_events(voice, "delivery_announced", identity)
        if row.get("assistantTurnId") == assistant_turn
    ]
    _require(
        len(announced) == 1
        and sum(row.get("kind") == "delivery_announced" for row in voice) == 1,
        "single_announcement",
    )
    announcement_index = announced[0][0]
    _require(
        success_index < eligible[0][0] < speech[0][0] < started[0][0]
        < grounded_index < announcement_index,
        "single_delivery_order",
    )
    return identity, result_hash, assistant_turn, announcement_index


def _parallel_identities(
    voice: Sequence[dict[str, Any]],
) -> tuple[tuple[int, tuple[Any, ...]], tuple[int, tuple[Any, ...]]]:
    accepted_rows = _accepted(voice, "parallel_two_accepted", 2)
    first_index, first_row = accepted_rows[0]
    second_index, second_row = accepted_rows[1]
    first = _identity(first_row)
    second = _identity(second_row)
    _require(first != second, "parallel_distinct_identity")
    accepted_pairs = {(first[2], first[4]), (second[2], second[4])}
    _require(
        all(
            _delivery_identity(row) in accepted_pairs
            for row in voice
            if row.get("kind") in DELIVERY_KINDS
        ),
        "parallel_delivery_identity",
    )
    return (first_index, first), (second_index, second)


def _parallel_later(voice: Sequence[dict[str, Any]]) -> tuple[tuple[Any, ...], tuple[Any, ...], int]:
    (_first_acceptance, first), (second_acceptance, second) = _parallel_identities(voice)
    _require(
        not any(
            (_identity(row) == first and row.get("kind") in TERMINAL_KINDS)
            or (
                _delivery_identity(row) == (first[2], first[4])
                and row.get("kind") == "delivery_announced"
            )
            for row in voice
        ),
        "parallel_first_pending",
    )
    succeeded = _events_for_identity(voice, "job_succeeded", second)
    announced = _delivery_events(voice, "delivery_announced", second)
    _require(len(succeeded) == 1, "parallel_second_succeeded")
    _require(len(announced) == 1, "parallel_second_announcement")
    _require(
        second_acceptance < succeeded[0][0] < announced[0][0],
        "parallel_second_order",
    )
    return first, second, announced[0][0]


def _interruption_active(
    automation: Sequence[dict[str, Any]], voice: Sequence[dict[str, Any]], allow_announcement: bool
) -> tuple[tuple[Any, ...], int, int]:
    accepted_rows = _accepted(voice, "interruption_one_job", 1)
    identity = _identity(accepted_rows[0][1])
    succeeded = _events_for_identity(voice, "job_succeeded", identity)
    started = _delivery_events(voice, "delivery_started", identity)
    _require(len(succeeded) == 1, "interruption_succeeded_identity")
    _require(len(started) == 1 and succeeded[0][0] < started[0][0], "interruption_delivery_identity")
    if not allow_announcement:
        _require(
            not any(row.get("kind") == "delivery_announced" for row in voice),
            "interruption_no_announcement",
        )
    active = [
        (index, row)
        for index, row in enumerate(automation)
        if row.get("name") == "playback_active"
        and type(row.get("playbackEpoch")) is int
        and row["playbackEpoch"] > 0
    ]
    _require(bool(active), "interruption_active_epoch")
    return identity, active[0][1]["playbackEpoch"], active[0][0]


def _interruption_stop(
    automation: Sequence[dict[str, Any]], voice: Sequence[dict[str, Any]], allow_announcement: bool
) -> tuple[tuple[Any, ...], int, int]:
    identity, epoch, active_index = _interruption_active(automation, voice, allow_announcement)
    interrupted = ordered_event(automation, "interrupt_started", after=active_index)
    _require(interrupted is not None, "interruption_boundary")
    stopped = ordered_event(
        automation,
        "playback_stopped",
        lambda row: row.get("playbackEpoch") == epoch,
        interrupted,
    )
    _require(stopped is not None, "interruption_stopped_epoch")
    return identity, epoch, stopped


def evaluate_checkpoint(
    expectation: Expectation,
    automation: Sequence[dict[str, Any]],
    voice: Sequence[dict[str, Any]],
    quiet_ns: int,
) -> None:
    _require(type(quiet_ns) is int and quiet_ns >= 0, "quiet_threshold")
    expectation = Expectation(expectation)

    if expectation is Expectation.SINGLE_RESULT_ANNOUNCED:
        _single_result(voice)
        return

    if expectation is Expectation.SINGLE_FOLLOW_UP_GROUNDED:
        _, result_hash, assistant_turn, announcement_index = _single_result(voice)
        users = [
            (index, row)
            for index, row in enumerate(voice)
            if index > announcement_index and row.get("kind") == "transcript" and row.get("role") == "user"
        ]
        _require(len(users) == 1, "follow_up_user_turn")
        user_index, user = users[0]
        correlations = [
            row
            for index, row in enumerate(voice)
            if index > user_index
            and row.get("kind") == "follow_up_correlation"
            and row.get("followUpTurnId") == user.get("turnId")
            and row.get("assistantTurnId") == assistant_turn
            and row.get("resultHash") == result_hash
        ]
        _require(len(correlations) == 1, "follow_up_correlation")
        return

    if expectation is Expectation.PARALLEL_FIRST_PENDING:
        accepted_rows = _accepted(voice, "parallel_first_accepted", 1)
        identity = _identity(accepted_rows[0][1])
        _require(
            not any(_identity(row) == identity and row.get("kind") in TERMINAL_KINDS for row in voice),
            "parallel_first_nonterminal",
        )
        _require(not _delivery_events(voice, "delivery_announced", identity), "parallel_first_no_announcement")
        return

    if expectation is Expectation.PARALLEL_LATER_COMPLETED_FIRST:
        _parallel_later(voice)
        return

    if expectation is Expectation.PARALLEL_BOTH_ANNOUNCED:
        (first_acceptance, first), (second_acceptance, second) = _parallel_identities(voice)
        second_success = _events_for_identity(voice, "job_succeeded", second)
        second_announced = _delivery_events(voice, "delivery_announced", second)
        first_success = _events_for_identity(voice, "job_succeeded", first)
        first_announced = _delivery_events(voice, "delivery_announced", first)
        _require(len(second_success) == len(second_announced) == 1, "parallel_second_announcement")
        _require(len(first_success) == len(first_announced) == 1, "parallel_first_announcement")
        _require(
            first_acceptance < first_success[0][0]
            and second_acceptance < second_success[0][0],
            "parallel_acceptance_order",
        )
        _require(
            second_success[0][0] < second_announced[0][0] < first_success[0][0] < first_announced[0][0],
            "parallel_completion_order",
        )
        return

    if expectation is Expectation.INTERRUPTION_DELIVERY_ACTIVE:
        _interruption_active(automation, voice, False)
        return

    if expectation is Expectation.INTERRUPTION_OBSERVED:
        _interruption_stop(automation, voice, False)
        return

    if expectation is Expectation.INTERRUPTION_RECOVERED:
        identity, old_epoch, stopped_index = _interruption_stop(automation, voice, True)
        new_active = next(
            (
                (index, row)
                for index, row in enumerate(automation)
                if index > stopped_index
                and row.get("name") == "playback_active"
                and type(row.get("playbackEpoch")) is int
                and row["playbackEpoch"] != old_epoch
            ),
            None,
        )
        _require(new_active is not None, "recovery_new_epoch")
        active_index, active_row = new_active
        active_ms = active_row.get("monotonicMs")
        quiet_start = first_quiet_after_last_reset(automation, active_ms)
        threshold_ms = (quiet_ns + 999_999) // 1_000_000
        _require(
            type(active_ms) is int
            and quiet_start is not None
            and active_ms - quiet_start >= threshold_ms,
            "recovery_continuous_quiet",
        )
        drained = ordered_event(
            automation,
            "playback_drained",
            lambda row: row.get("playbackEpoch") == active_row["playbackEpoch"],
            active_index,
        )
        _require(drained is not None, "recovery_drained_epoch")
        succeeded = _events_for_identity(voice, "job_succeeded", identity)
        _require(len(succeeded) == 1, "recovery_succeeded_identity")
        result_hash = succeeded[0][1].get("resultHash")
        grounded = [
            (index, row)
            for index, row in enumerate(voice)
            if row.get("kind") == "transcript"
            and row.get("role") == "assistant"
            and row.get("groundedJobId") == identity[4]
            and row.get("groundedResultHash") == result_hash
        ]
        _require(len(grounded) == 1, "recovery_grounded_assistant")
        announced = [
            (index, row)
            for index, row in _delivery_events(voice, "delivery_announced", identity)
            if row.get("assistantTurnId") == grounded[0][1].get("turnId")
        ]
        all_announced = [row for row in voice if row.get("kind") == "delivery_announced"]
        _require(
            len(all_announced) == 1
            and len(announced) == 1
            and all_announced[0] is announced[0][1]
            and grounded[0][0] < announced[0][0],
            "recovery_announcement",
        )
        return

    if expectation is Expectation.ISOLATION_FIRST_ACTIVE:
        accepted_rows = _accepted(voice, "isolation_target_accepted", 1)
        identity = _identity(accepted_rows[0][1])
        _require(
            not any(_identity(row) == identity and row.get("kind") in TERMINAL_KINDS for row in voice),
            "isolation_target_nonterminal",
        )
        return

    if expectation is Expectation.ISOLATION_TWO_DISTINCT:
        accepted_rows = _accepted(voice, "isolation_two_accepted", 2)
        first = accepted_rows[0][1]
        second = accepted_rows[1][1]
        _require(
            all(first[field] != second[field] for field in ("requestHash", "toolCallId", "jobId")),
            "isolation_disjoint_identity",
        )
        return

    if expectation is Expectation.ISOLATION_TERMINAL_HEALTHY:
        accepted_rows = _accepted(voice, "isolation_two_accepted", 2)
        target_acceptance, target_row = accepted_rows[0]
        healthy_acceptance, healthy_row = accepted_rows[1]
        target = _identity(target_row)
        healthy = _identity(healthy_row)
        _require(
            all(target[index] != healthy[index] for index in (1, 2, 4)),
            "isolation_disjoint_identity",
        )
        target_failures = [
            (index, row)
            for index, row in enumerate(voice)
            if row.get("kind") in FAILURE_KINDS and _identity(row) == target
        ]
        _require(len(target_failures) == 1, "isolation_target_terminal")
        _require(target_acceptance < target_failures[0][0], "isolation_target_order")
        _require(not _events_for_identity(voice, "job_succeeded", target), "isolation_target_terminal")
        healthy_success = _events_for_identity(voice, "job_succeeded", healthy)
        _require(len(healthy_success) == 1, "isolation_healthy_succeeded")
        _require(
            not any(row.get("kind") in FAILURE_KINDS and _identity(row) == healthy for row in voice),
            "isolation_healthy_succeeded",
        )
        _require(
            not any(
                row.get("kind") in DELIVERY_KINDS
                and _delivery_identity(row) == (target[2], target[4])
                for row in voice
            ),
            "isolation_target_no_delivery",
        )
        result_hash = healthy_success[0][1].get("resultHash")
        grounded = [
            (index, row)
            for index, row in enumerate(voice)
            if row.get("kind") == "transcript"
            and row.get("role") == "assistant"
            and row.get("groundedJobId") == healthy[4]
            and row.get("groundedResultHash") == result_hash
        ]
        _require(len(grounded) == 1, "isolation_healthy_grounded")
        announced = [
            (index, row)
            for index, row in _delivery_events(voice, "delivery_announced", healthy)
            if row.get("assistantTurnId") == grounded[0][1].get("turnId")
        ]
        _require(len(announced) == 1, "isolation_healthy_announced")
        _require(
            healthy_acceptance < healthy_success[0][0]
            < grounded[0][0] < announced[0][0],
            "isolation_healthy_order",
        )
        return

    raise ContractError("expectation")


def _parse_pairs(line: str) -> tuple[list[str], dict[str, Any]]:
    pairs = json.loads(line, object_pairs_hook=lambda value: value)
    if type(pairs) is not list or any(type(pair) is not tuple for pair in pairs):
        raise ValueError
    keys = [key for key, _ in pairs]
    if any(type(key) is not str for key in keys) or len(keys) != len(set(keys)):
        raise ValueError
    return keys, dict(pairs)


def _text_lines(content: bytes) -> list[str]:
    if (
        not content
        or len(content) > MAX_ARTIFACT_BYTES
        or b"\r" in content
        or not content.endswith(b"\n")
    ):
        raise ContractError("evidence_envelope")
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ContractError("evidence_envelope") from error
    lines = text[:-1].split("\n")
    if not lines or any(not line for line in lines):
        raise ContractError("evidence_envelope")
    return lines


def _canonical_timestamp(value: Any) -> bool:
    if type(value) is not str or CANONICAL_INSTANT.fullmatch(value) is None:
        return False
    if "." in value and value[:-1].endswith("000"):
        return False
    parse_value = re.sub(r"(\.[0-9]{6})[0-9]{3}Z$", r"\1Z", value)
    try:
        datetime.fromisoformat(parse_value[:-1] + "+00:00").astimezone(timezone.utc)
    except ValueError:
        return False
    return True


def parse_automation_bytes(
    content: bytes,
    run_hash: str | None = None,
    comparison_hash: str | None = None,
    transport: str = "livekit_experimental",
) -> list[dict[str, Any]]:
    rows = []
    previous_ms = None
    for line in _text_lines(content):
        try:
            keys, row = _parse_pairs(line)
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            raise ContractError("automation_evidence") from error
        _require(keys == list(AUTOMATION_KEYS), "automation_evidence")
        _require(json.dumps(row, separators=(",", ":"), ensure_ascii=False) == line, "automation_evidence")
        monotonic_ms = row.get("monotonicMs")
        _require(
            type(row.get("schemaVersion")) is int
            and row["schemaVersion"] == 1
            and type(monotonic_ms) is int
            and monotonic_ms > 0
            and (previous_ms is None or monotonic_ms > previous_ms)
            and type(row.get("wallClockMs")) is int
            and row["wallClockMs"] > 0
            and HASH.fullmatch(row.get("runHash", "")) is not None
            and HASH.fullmatch(row.get("comparisonHash", "")) is not None
            and row.get("requestedTransport") == transport
            and type(row.get("name")) is str,
            "automation_evidence",
        )
        epoch = row.get("playbackEpoch")
        byte_count = row.get("byteCount")
        audio_window = row.get("audioWindowMicros")
        _require(epoch is None or (type(epoch) is int and epoch > 0), "automation_evidence")
        _require(byte_count is None or (type(byte_count) is int and byte_count >= 0), "automation_evidence")
        _require(
            row.get("rmsActive") is None or type(row.get("rmsActive")) is bool,
            "automation_evidence",
        )
        _require(
            audio_window is None or (type(audio_window) is int and audio_window > 0),
            "automation_evidence",
        )
        if row["name"] == "playback_written":
            _require(
                type(byte_count) is int
                and byte_count > 0
                and type(row.get("rmsActive")) is bool
                and type(audio_window) is int
                and audio_window > 0,
                "automation_evidence",
            )
        else:
            _require(row.get("rmsActive") is None and audio_window is None, "automation_evidence")
        _require(
            row.get("succeeded") is None or type(row.get("succeeded")) is bool,
            "automation_evidence",
        )
        correlation_kind = row.get("correlationKind")
        correlation_hash = row.get("correlationHash")
        _require((correlation_kind is None) == (correlation_hash is None), "automation_evidence")
        if correlation_hash is not None:
            _require(
                type(correlation_kind) is str
                and type(correlation_hash) is str
                and HASH.fullmatch(correlation_hash) is not None,
                "automation_evidence",
            )
        configuration_fields = (
            "requestedModelHash",
            "observedModelHash",
            "voiceHash",
            "instructionHash",
            "directAccountConfigurationHash",
            "conversationHash",
        )
        if row["name"] == "direct_config_attested":
            _require(
                all(
                    type(row[field]) is str and HASH.fullmatch(row[field]) is not None
                    for field in configuration_fields
                ),
                "automation_evidence",
            )
        else:
            _require(all(row[field] is None for field in configuration_fields), "automation_evidence")
        capture_fields = ("captureSource", "micBytes", "fixtureBytes")
        if row["name"] == "capture_attested":
            _require(
                row["captureSource"] in {"microphone", "fixture"}
                and all(type(row[field]) is int and row[field] >= 0 for field in capture_fields[1:]),
                "automation_evidence",
            )
        else:
            _require(all(row[field] is None for field in capture_fields), "automation_evidence")
        if run_hash is not None:
            _require(row["runHash"] == run_hash, "automation_binding")
        if comparison_hash is not None:
            _require(row["comparisonHash"] == comparison_hash, "automation_binding")
        previous_ms = monotonic_ms
        rows.append(row)
    return rows


def parse_voice_bytes(content: bytes) -> list[dict[str, Any]]:
    rows = []
    event_ids = set()
    for line in _text_lines(content):
        try:
            keys, row = _parse_pairs(line)
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            raise ContractError("voice_evidence") from error
        schema = VOICE_SCHEMAS.get(row.get("kind"))
        if row.get("kind") == "transcript":
            schema = VOICE_BASE | {"turnId", "role", "interrupted", "textCharacterCount"}
            grounding = {"groundedJobId", "groundedResultHash"}
            if set(keys) == schema | grounding:
                schema |= grounding
        _require(schema is not None and keys == sorted(schema), "voice_evidence")
        _require(json.dumps(row, sort_keys=True, separators=(",", ":"), ensure_ascii=False) == line, "voice_evidence")
        _require(
            type(row.get("version")) is int
            and row["version"] == 1
            and HASH.fullmatch(row.get("voiceSessionHash", "")) is not None
            and IDENTIFIER.fullmatch(row.get("eventId", "")) is not None
            and row["eventId"] not in event_ids
            and _canonical_timestamp(row.get("observedAt"))
            and HASH.fullmatch(row.get("eventHash", "")) is not None
            and "voiceSessionId" not in row,
            "voice_evidence",
        )
        _require(
            all(
                type(row[field]) is str and IDENTIFIER.fullmatch(row[field]) is not None
                for field in set(row) & VOICE_IDENTIFIERS
            ),
            "voice_evidence",
        )
        _require(
            all(
                type(row[field]) is str and HASH.fullmatch(row[field]) is not None
                for field in set(row) & VOICE_HASHES
            ),
            "voice_evidence",
        )
        _require(
            all(type(row[field]) is int and row[field] >= 0 for field in set(row) & VOICE_COUNTS),
            "voice_evidence",
        )
        _require(
            all(type(row[field]) is bool for field in set(row) & VOICE_BOOLEANS),
            "voice_evidence",
        )
        if row["kind"] == "transcript":
            grounded = "groundedJobId" in row and "groundedResultHash" in row
            _require(
                row.get("role") in {"user", "assistant"}
                and not (row.get("role") == "user" and (row.get("interrupted") or grounded))
                and (("groundedJobId" in row) == ("groundedResultHash" in row)),
                "voice_evidence",
            )
        event_ids.add(row["eventId"])
        rows.append(row)
    binding_rows = [row for row in rows if row.get("kind") == "session_binding"]
    _require(len(binding_rows) == 1 and rows[0] is binding_rows[0], "voice_session_binding")
    binding = binding_rows[0]
    for row in rows:
        _require(row["voiceSessionHash"] == binding["voiceSessionHash"], "voice_session_binding")
        if row.get("kind") in {"job_accepted", "job_running", "still_working", *TERMINAL_KINDS}:
            _require(
                all(row[field] == binding[field] for field in ("ownerHash", "conversationHash", "roomHash", "traceHash")),
                "voice_session_binding",
            )
    return rows


def _parse_private_voice_bytes(
    content: bytes,
) -> tuple[list[dict[str, Any]], list[bytes]]:
    rows: list[dict[str, Any]] = []
    raw_rows: list[bytes] = []
    event_ids: set[str] = set()
    for line in _text_lines(content):
        try:
            keys, row = _parse_pairs(line)
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            raise ContractError("private_voice_evidence") from error
        schema = PRIVATE_SCHEMAS.get(row.get("kind"))
        if row.get("kind") == "transcript":
            schema = PRIVATE_BASE | {"turnId", "role", "text", "interrupted"}
            grounding = {"groundedJobId", "groundedResultHash"}
            if set(keys) == schema | grounding:
                schema |= grounding
        _require(
            schema is not None
            and keys == sorted(schema)
            and canonical_json_bytes(row).decode("utf-8") == line,
            "private_voice_evidence",
        )
        _require(
            type(row.get("version")) is int
            and row["version"] == 1
            and IDENTIFIER.fullmatch(row.get("voiceSessionId", "")) is not None
            and IDENTIFIER.fullmatch(row.get("eventId", "")) is not None
            and row["eventId"] not in event_ids
            and _canonical_timestamp(row.get("observedAt")),
            "private_voice_evidence",
        )
        _require(
            all(
                type(row[field]) is str
                and IDENTIFIER.fullmatch(row[field]) is not None
                for field in set(row) & VOICE_IDENTIFIERS
            ),
            "private_voice_evidence",
        )
        _require(
            all(
                type(row[field]) is str and HASH.fullmatch(row[field]) is not None
                for field in set(row) & (VOICE_HASHES - {"eventHash"})
            ),
            "private_voice_evidence",
        )
        _require(
            all(type(row[field]) is bool for field in set(row) & VOICE_BOOLEANS),
            "private_voice_evidence",
        )
        kind = row["kind"]
        if kind == "session_binding" or kind in {
            "job_accepted",
            "job_running",
            "still_working",
            *TERMINAL_KINDS,
        }:
            _require(
                row["voiceSessionHash"]
                == sha256_bytes(row["voiceSessionId"].encode("utf-8")),
                "private_voice_evidence",
            )
        if kind == "job_accepted":
            _require(
                type(row["prompt"]) is str and bool(row["prompt"].strip()),
                "private_voice_evidence",
            )
        elif kind == "job_succeeded":
            _require(
                type(row["answer"]) is str
                and bool(row["answer"].strip())
                and row["resultHash"]
                == sha256_bytes(row["answer"].encode("utf-8")),
                "private_voice_evidence",
            )
        elif kind in FAILURE_KINDS:
            _require(
                type(row["failureReason"]) is str
                and bool(row["failureReason"].strip())
                and len(row["failureReason"]) <= 512
                and not any(ord(character) < 32 for character in row["failureReason"]),
                "private_voice_evidence",
            )
        elif kind == "transcript":
            grounded = "groundedJobId" in row and "groundedResultHash" in row
            _require(
                row["role"] in {"user", "assistant"}
                and type(row["text"]) is str
                and bool(row["text"].strip())
                and type(row["interrupted"]) is bool
                and not (row["role"] == "user" and (row["interrupted"] or grounded))
                and (("groundedJobId" in row) == ("groundedResultHash" in row)),
                "private_voice_evidence",
            )
        event_ids.add(row["eventId"])
        rows.append(row)
        raw_rows.append(line.encode("utf-8"))

    bindings = [row for row in rows if row["kind"] == "session_binding"]
    _require(len(bindings) == 1 and rows[0] is bindings[0], "voice_session_binding")
    binding = bindings[0]
    for row in rows:
        _require(
            row["voiceSessionId"] == binding["voiceSessionId"],
            "voice_session_binding",
        )
        if row["kind"] in {
            "job_accepted",
            "job_running",
            "still_working",
            *TERMINAL_KINDS,
        }:
            _require(
                all(
                    row[field] == binding[field]
                    for field in (
                        "ownerHash",
                        "conversationHash",
                        "voiceSessionHash",
                        "roomHash",
                        "traceHash",
                    )
                ),
                "voice_session_binding",
            )
    return rows, raw_rows


def _utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _sanitized_projection(private: dict[str, Any], raw: bytes) -> dict[str, Any]:
    kind = private["kind"]
    projected: dict[str, Any] = {
        "version": private["version"],
        "voiceSessionHash": sha256_bytes(private["voiceSessionId"].encode("utf-8")),
        "eventId": private["eventId"],
        "kind": kind,
        "observedAt": private["observedAt"],
        "eventHash": sha256_bytes(raw),
    }
    fields: tuple[str, ...]
    if kind == "session_binding":
        fields = ("ownerHash", "conversationHash", "roomHash", "traceHash")
    elif kind in {"job_accepted", "job_running", "still_working", *TERMINAL_KINDS}:
        fields = tuple(PRIVATE_JOB)
        if kind == "job_accepted":
            projected["promptCharacterCount"] = _utf16_length(private["prompt"])
        elif kind == "job_succeeded":
            fields += ("resultHash",)
            projected["answerCharacterCount"] = _utf16_length(private["answer"])
        elif kind in FAILURE_KINDS:
            projected["failureReasonCharacterCount"] = _utf16_length(
                private["failureReason"]
            )
    elif kind == "transcript":
        fields = ("turnId", "role", "interrupted")
        if "groundedJobId" in private:
            fields += ("groundedJobId", "groundedResultHash")
        projected["textCharacterCount"] = _utf16_length(private["text"])
    elif kind == "delivery_blocked":
        fields = ("toolCallId", "jobId", "userSpeaking", "agentSpeaking")
    elif kind == "delivery_announced":
        fields = ("toolCallId", "jobId", "assistantTurnId")
    elif kind in {"delivery_eligible", "delivery_started", "speech_started"}:
        fields = ("toolCallId", "jobId")
    elif kind == "follow_up_correlation":
        fields = ("followUpTurnId", "assistantTurnId", "resultHash")
    else:
        raise ContractError("voice_correspondence")
    projected.update({field: private[field] for field in fields})
    return projected


def _validate_terminal_order(
    automation: Sequence[dict[str, Any]],
    finalization: Mapping[str, object],
) -> None:
    names = [row["name"] for row in automation]
    stopped_events = [
        index for index, row in enumerate(automation) if row["name"] == "call_stopped"
    ]
    stopped = [
        index
        for index, row in enumerate(automation)
        if row["name"] == "call_stopped" and row.get("succeeded") is True
    ]
    finalized = [
        index for index, row in enumerate(automation) if row["name"] == "run_finalized"
    ]
    _require(
        len(stopped_events) <= 1 and len(finalized) <= 1,
        "automation_terminal_order",
    )
    if finalization["outcome"] == "infrastructure_interruption":
        _require(
            (not stopped_events and not finalized)
            or (
                len(stopped_events) == 1
                and len(stopped) == 1
                and not finalized
                and stopped[0] == len(automation) - 1
            )
            or (
                len(stopped_events) == 1
                and len(stopped) == 1
                and len(finalized) == 1
                and stopped[0] + 1 == finalized[0]
                and finalized[0] == len(automation) - 1
            ),
            "automation_terminal_order",
        )
        return
    if finalization["callStopped"]:
        _require(
            len(stopped_events) == 1 and len(stopped) == 1,
            "automation_terminal_order",
        )
    elif finalization["reason"] == "call_stop_failed":
        _require(
            len(stopped_events) == 1
            and automation[stopped_events[0]].get("succeeded") is False
            and stopped_events[0] == len(automation) - 1,
            "automation_terminal_order",
        )
    elif finalization["reason"] == "forced_fallback_used":
        _require(
            not stopped
            and (
                not stopped_events
                or (
                    automation[stopped_events[0]].get("succeeded") is False
                    and stopped_events[0] == len(automation) - 1
                )
            ),
            "automation_terminal_order",
        )
    else:
        _require(not stopped_events, "automation_terminal_order")
    if finalization["automationFinalized"]:
        _require(
            len(finalized) == 1
            and bool(stopped)
            and stopped[0] + 1 == finalized[0]
            and finalized[0] == len(automation) - 1,
            "automation_terminal_order",
        )
    else:
        _require(not finalized, "automation_terminal_order")
        if stopped:
            _require(stopped[0] == len(automation) - 1, "automation_terminal_order")
    if "run_finalized" in names:
        _require(names[-1] == "run_finalized", "automation_terminal_order")


def validate_capture_bundle(
    automation_bytes: bytes,
    private_bytes: bytes,
    sanitized_bytes: bytes,
    attempt: Mapping[str, object],
    finalization: object,
) -> None:
    validated_finalization = validate_finalization(finalization)
    _require(isinstance(attempt, Mapping), "attempt_binding")
    run_hash = attempt.get("runHash")
    comparison_hash = attempt.get("comparisonHash")
    _require(
        type(run_hash) is str
        and HASH.fullmatch(run_hash) is not None
        and type(comparison_hash) is str
        and HASH.fullmatch(comparison_hash) is not None,
        "attempt_binding",
    )
    automation = parse_automation_bytes(automation_bytes, run_hash, comparison_hash)
    private, raw_private = _parse_private_voice_bytes(private_bytes)
    sanitized = parse_voice_bytes(sanitized_bytes)
    _require(len(private) == len(sanitized), "voice_correspondence")
    for private_row, raw_row, sanitized_row in zip(
        private, raw_private, sanitized, strict=True
    ):
        _require(
            _sanitized_projection(private_row, raw_row) == sanitized_row,
            "voice_correspondence",
        )
    _validate_terminal_order(automation, validated_finalization)


def validate_automation_finalization(
    automation_bytes: bytes,
    run_hash: str,
    comparison_hash: str,
    finalization: object,
) -> None:
    validated_finalization = validate_finalization(finalization)
    automation = parse_automation_bytes(
        automation_bytes,
        run_hash,
        comparison_hash,
    )
    _validate_terminal_order(automation, validated_finalization)


def _read(path: str) -> bytes:
    try:
        return Path(path).read_bytes()
    except OSError as error:
        raise ContractError("evidence_envelope") from error


def _main(arguments: Sequence[str]) -> int:
    if len(arguments) == 2 and arguments[0] == "--validate-expectation":
        try:
            Expectation(arguments[1])
        except ValueError:
            return 2
        return 0
    if len(arguments) == 2 and arguments[0] == "--validate-finalization":
        try:
            validate_finalization(parse_canonical_json_bytes(_read(arguments[1])))
        except ContractError:
            return 3
        return 0
    if len(arguments) == 3 and arguments[0] == "--validate-cleanup":
        try:
            cleanup = parse_canonical_json_bytes(_read(arguments[1]))
            finalization = parse_canonical_json_bytes(_read(arguments[2]))
            validate_cleanup(cleanup, finalization)
        except ContractError:
            return 3
        return 0
    if len(arguments) == 7 and arguments[0] == "--validate-capture":
        _, automation_path, private_path, sanitized_path, run_hash, comparison_hash, finalization_path = arguments
        try:
            finalization = parse_canonical_json_bytes(_read(finalization_path))
            validate_capture_bundle(
                automation_bytes=_read(automation_path),
                private_bytes=_read(private_path),
                sanitized_bytes=_read(sanitized_path),
                attempt={"runHash": run_hash, "comparisonHash": comparison_hash},
                finalization=finalization,
            )
        except ContractError:
            return 3
        return 0
    if len(arguments) == 5 and arguments[0] == "--validate-automation-finalization":
        _, automation_path, run_hash, comparison_hash, finalization_path = arguments
        try:
            validate_automation_finalization(
                _read(automation_path),
                run_hash,
                comparison_hash,
                parse_canonical_json_bytes(_read(finalization_path)),
            )
        except ContractError:
            return 3
        return 0
    if len(arguments) == 6 and arguments[0] == "--encode-finalization":
        _, outcome, reason, call_stopped, automation_finalized, forced_fallback = arguments
        if any(
            value not in {"true", "false"}
            for value in (call_stopped, automation_finalized, forced_fallback)
        ):
            return 2
        try:
            value = validate_finalization(
                {
                    "schemaVersion": 1,
                    "outcome": outcome,
                    "reason": reason,
                    "callStopped": call_stopped == "true",
                    "automationFinalized": automation_finalized == "true",
                    "forcedFallbackUsed": forced_fallback == "true",
                }
            )
        except ContractError:
            return 3
        sys.stdout.buffer.write(canonical_json_bytes(value))
        return 0
    if len(arguments) == 6 and arguments[0] == "--encode-cleanup":
        _, outcome, call_stopped, automation_finalized, fixtures_removed, finalization_hash = arguments
        if any(
            value not in {"true", "false"}
            for value in (call_stopped, automation_finalized, fixtures_removed)
        ):
            return 2
        try:
            value = validate_cleanup(
                {
                    "schemaVersion": 2,
                    "outcome": outcome,
                    "callStopped": call_stopped == "true",
                    "automationFinalized": automation_finalized == "true",
                    "fixturesRemoved": fixtures_removed == "true",
                    "finalizationHash": finalization_hash,
                }
            )
        except ContractError:
            return 3
        sys.stdout.buffer.write(canonical_json_bytes(value))
        return 0
    if len(arguments) != 7 or arguments[0] != "--evaluate":
        return 2
    _, expectation_value, automation_path, voice_path, run_hash, comparison_hash, quiet_ns = arguments
    try:
        expectation = Expectation(expectation_value)
        threshold = int(quiet_ns)
        automation = parse_automation_bytes(_read(automation_path), run_hash, comparison_hash)
        voice = parse_voice_bytes(_read(voice_path))
        evaluate_checkpoint(expectation, automation, voice, threshold)
    except (ValueError, ContractError) as error:
        boundary = error.boundary if isinstance(error, ContractError) else "expectation"
        print(boundary)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
