from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.models.assistant import Assistant
from perry_server.models.change_log import ChangeLog
from perry_server.models.mutation_receipt import MutationReceipt
from perry_server.models.setting import Setting
from perry_server.schemas.sync import (
    BootstrapResponse,
    ChangeItem,
    ChangesResponse,
    MutationItem,
    MutationResult,
    MutationsResponse,
)

SUPPORTED_ENTITY_TYPES = {"setting", "assistant"}


def _now() -> datetime:
    return datetime.now(UTC)


def _setting_payload(setting: Setting) -> dict[str, Any]:
    return {
        "id": str(setting.id),
        "key": setting.key,
        "value": json.loads(setting.value_json),
        "revision": setting.revision,
        "payload_schema_version": setting.payload_schema_version,
        "updated_at": setting.updated_at.isoformat() if setting.updated_at else None,
        "deleted_at": setting.deleted_at.isoformat() if setting.deleted_at else None,
        "updated_by_device": str(setting.updated_by_device) if setting.updated_by_device else None,
    }


def _assistant_payload(assistant: Assistant) -> dict[str, Any]:
    return {
        "id": str(assistant.id),
        "name": assistant.name,
        "payload": json.loads(assistant.payload_json),
        "revision": assistant.revision,
        "payload_schema_version": assistant.payload_schema_version,
        "updated_at": assistant.updated_at.isoformat() if assistant.updated_at else None,
        "deleted_at": assistant.deleted_at.isoformat() if assistant.deleted_at else None,
        "updated_by_device": (
            str(assistant.updated_by_device) if assistant.updated_by_device else None
        ),
    }


async def get_current_cursor(session: AsyncSession, user_id: UUID) -> int:
    result = await session.execute(
        select(ChangeLog.seq)
        .where(ChangeLog.user_id == user_id)
        .order_by(ChangeLog.seq.desc())
        .limit(1)
    )
    seq = result.scalar_one_or_none()
    return int(seq or 0)


async def bootstrap(session: AsyncSession, *, user_id: UUID) -> BootstrapResponse:
    cursor = await get_current_cursor(session, user_id)
    settings_result = await session.execute(
        select(Setting).where(Setting.user_id == user_id, Setting.deleted_at.is_(None))
    )
    assistants_result = await session.execute(
        select(Assistant).where(Assistant.user_id == user_id, Assistant.deleted_at.is_(None))
    )
    return BootstrapResponse(
        cursor=cursor,
        server_time=_now().isoformat(),
        settings=[_setting_payload(row) for row in settings_result.scalars().all()],
        assistants=[_assistant_payload(row) for row in assistants_result.scalars().all()],
    )


async def list_changes(
    session: AsyncSession,
    *,
    user_id: UUID,
    cursor: int,
    limit: int,
) -> ChangesResponse:
    limit = max(1, min(limit, 500))
    result = await session.execute(
        select(ChangeLog)
        .where(ChangeLog.user_id == user_id, ChangeLog.seq > cursor)
        .order_by(ChangeLog.seq.asc())
        .limit(limit + 1)
    )
    rows = list(result.scalars().all())
    has_more = len(rows) > limit
    rows = rows[:limit]
    changes: list[ChangeItem] = []
    for row in rows:
        payload = json.loads(row.payload_json) if row.payload_json else None
        changes.append(
            ChangeItem(
                seq=row.seq,
                entity_type=row.entity_type,
                entity_id=row.entity_id,
                operation=row.operation,
                revision=row.revision,
                changed_at=row.changed_at.isoformat() if row.changed_at else _now().isoformat(),
                changed_by_device=row.changed_by_device,
                payload=payload,
            )
        )
    next_cursor = changes[-1].seq if changes else cursor
    return ChangesResponse(changes=changes, next_cursor=next_cursor, has_more=has_more)


async def apply_mutations(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    mutations: list[MutationItem],
) -> MutationsResponse:
    results: list[MutationResult] = []
    for item in mutations:
        results.append(await _apply_one(session, user_id=user_id, device_id=device_id, item=item))
    await session.commit()
    cursor = await get_current_cursor(session, user_id)
    return MutationsResponse(results=results, cursor=cursor)


async def _apply_one(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    existing = await session.get(MutationReceipt, item.mutation_id)
    if existing is not None:
        cached = MutationResult.model_validate(json.loads(existing.response_json))
        if cached.status == "applied":
            return cached.model_copy(update={"status": "already_applied"})
        return cached

    if item.entity_type not in SUPPORTED_ENTITY_TYPES:
        result = MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=f"unsupported entity_type: {item.entity_type}",
        )
        await _store_receipt(session, user_id, device_id, item, result)
        return result

    if item.entity_type == "setting":
        result = await _apply_setting_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    elif item.entity_type == "assistant":
        result = await _apply_assistant_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    else:  # pragma: no cover
        result = MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="unsupported entity_type",
        )

    await _store_receipt(session, user_id, device_id, item, result)
    return result


async def _store_receipt(
    session: AsyncSession,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
    result: MutationResult,
) -> None:
    receipt = MutationReceipt(
        mutation_id=item.mutation_id,
        user_id=user_id,
        device_id=device_id,
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        status=result.status,
        revision=result.revision,
        response_json=result.model_dump_json(),
    )
    session.add(receipt)
    await session.flush()


async def _apply_setting_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    key = item.entity_id
    result = await session.execute(
        select(Setting).where(Setting.user_id == user_id, Setting.key == key)
    )
    setting = result.scalar_one_or_none()

    if item.operation == "delete":
        if setting is None or setting.deleted_at is not None:
            rev = setting.revision if setting is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_setting_payload(setting) if setting else None,
            )
        if item.base_revision != 0 and item.base_revision != setting.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=setting.revision,
                server_payload=_setting_payload(setting),
                message="base_revision mismatch",
            )
        setting.revision += 1
        setting.deleted_at = _now()
        setting.updated_at = _now()
        setting.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="setting",
            entity_id=key,
            operation="delete",
            revision=setting.revision,
            payload=_setting_payload(setting),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=setting.revision,
            server_payload=_setting_payload(setting),
        )

    payload = item.payload or {}
    if "value" not in payload:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="payload.value is required for setting upsert",
        )

    value_json = json.dumps(payload["value"], ensure_ascii=False, separators=(",", ":"))

    if setting is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        setting = Setting(
            user_id=user_id,
            key=key,
            value_json=value_json,
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(setting)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="setting",
            entity_id=key,
            operation="upsert",
            revision=setting.revision,
            payload=_setting_payload(setting),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=setting.revision,
            server_payload=_setting_payload(setting),
        )

    if item.base_revision != setting.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=setting.revision,
            server_payload=_setting_payload(setting),
            message="base_revision mismatch",
        )

    setting.value_json = value_json
    setting.revision += 1
    setting.payload_schema_version = item.payload_schema_version
    setting.updated_at = _now()
    setting.updated_by_device = device_id
    setting.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="setting",
        entity_id=key,
        operation="upsert",
        revision=setting.revision,
        payload=_setting_payload(setting),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=setting.revision,
        server_payload=_setting_payload(setting),
    )


async def _apply_assistant_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    try:
        assistant_id = UUID(item.entity_id)
    except ValueError:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id must be a UUID for assistant",
        )

    result = await session.execute(
        select(Assistant).where(Assistant.user_id == user_id, Assistant.id == assistant_id)
    )
    assistant = result.scalar_one_or_none()

    if item.operation == "delete":
        if assistant is None or assistant.deleted_at is not None:
            rev = assistant.revision if assistant is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_assistant_payload(assistant) if assistant else None,
            )
        if item.base_revision != 0 and item.base_revision != assistant.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=assistant.revision,
                server_payload=_assistant_payload(assistant),
                message="base_revision mismatch",
            )
        assistant.revision += 1
        assistant.deleted_at = _now()
        assistant.updated_at = _now()
        assistant.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="assistant",
            entity_id=str(assistant_id),
            operation="delete",
            revision=assistant.revision,
            payload=_assistant_payload(assistant),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=assistant.revision,
            server_payload=_assistant_payload(assistant),
        )

    payload = item.payload or {}
    if "payload" not in payload:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="payload.payload is required for assistant upsert",
        )

    body = payload["payload"]
    if not isinstance(body, dict):
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="payload.payload must be an object",
        )
    name = str(payload.get("name") or body.get("name") or "")
    payload_json = json.dumps(body, ensure_ascii=False, separators=(",", ":"))

    if assistant is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        assistant = Assistant(
            id=assistant_id,
            user_id=user_id,
            name=name,
            payload_json=payload_json,
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(assistant)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="assistant",
            entity_id=str(assistant_id),
            operation="upsert",
            revision=assistant.revision,
            payload=_assistant_payload(assistant),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=assistant.revision,
            server_payload=_assistant_payload(assistant),
        )

    if item.base_revision != assistant.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=assistant.revision,
            server_payload=_assistant_payload(assistant),
            message="base_revision mismatch",
        )

    assistant.name = name
    assistant.payload_json = payload_json
    assistant.revision += 1
    assistant.payload_schema_version = item.payload_schema_version
    assistant.updated_at = _now()
    assistant.updated_by_device = device_id
    assistant.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="assistant",
        entity_id=str(assistant_id),
        operation="upsert",
        revision=assistant.revision,
        payload=_assistant_payload(assistant),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=assistant.revision,
        server_payload=_assistant_payload(assistant),
    )


async def _append_change(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    entity_type: str,
    entity_id: str,
    operation: str,
    revision: int,
    payload: dict[str, Any] | None,
) -> None:
    session.add(
        ChangeLog(
            user_id=user_id,
            entity_type=entity_type,
            entity_id=entity_id,
            operation=operation,
            revision=revision,
            changed_by_device=device_id,
            payload_json=json.dumps(payload, ensure_ascii=False) if payload is not None else None,
        )
    )
    await session.flush()
