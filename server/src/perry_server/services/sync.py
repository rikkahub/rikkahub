from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.models.assistant import Assistant
from perry_server.models.assistant_memory import AssistantMemory
from perry_server.models.change_log import ChangeLog
from perry_server.models.conversation import Conversation
from perry_server.models.conversation_folder import ConversationFolder
from perry_server.models.favorite import Favorite
from perry_server.models.file_object import FileObject
from perry_server.models.message_node import MessageNode
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
from perry_server.services.files import file_payload as _file_payload_from_service

SUPPORTED_ENTITY_TYPES = {
    "setting",
    "assistant",
    "conversation",
    "conversation_folder",
    "message_node",
    "assistant_memory",
    "favorite",
    "file",
}
BOOTSTRAP_CONVERSATION_LIMIT = 200


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


def _conversation_payload(conversation: Conversation) -> dict[str, Any]:
    return {
        "id": str(conversation.id),
        "assistant_id": str(conversation.assistant_id),
        "title": conversation.title,
        "create_at_ms": conversation.create_at_ms,
        "update_at_ms": conversation.update_at_ms,
        "is_pinned": conversation.is_pinned,
        "folder_id": conversation.folder_id or "",
        "sync_enabled": conversation.sync_enabled,
        "payload": json.loads(conversation.payload_json),
        "revision": conversation.revision,
        "payload_schema_version": conversation.payload_schema_version,
        "updated_at": conversation.updated_at.isoformat() if conversation.updated_at else None,
        "deleted_at": conversation.deleted_at.isoformat() if conversation.deleted_at else None,
        "updated_by_device": (
            str(conversation.updated_by_device) if conversation.updated_by_device else None
        ),
    }


def _folder_payload(folder: ConversationFolder) -> dict[str, Any]:
    return {
        "id": str(folder.id),
        "assistant_id": str(folder.assistant_id),
        "name": folder.name,
        "sort_index": folder.sort_index,
        "create_at_ms": folder.create_at_ms,
        "revision": folder.revision,
        "payload_schema_version": folder.payload_schema_version,
        "updated_at": folder.updated_at.isoformat() if folder.updated_at else None,
        "deleted_at": folder.deleted_at.isoformat() if folder.deleted_at else None,
        "updated_by_device": str(folder.updated_by_device) if folder.updated_by_device else None,
    }


def _message_node_payload(node: MessageNode) -> dict[str, Any]:
    messages: list[Any] = []
    if node.messages_json:
        try:
            parsed = json.loads(node.messages_json)
            if isinstance(parsed, list):
                messages = parsed
        except json.JSONDecodeError:
            messages = []
    return {
        "id": str(node.id),
        "conversation_id": str(node.conversation_id),
        "node_index": node.node_index,
        "select_index": node.select_index,
        "messages": messages,
        "revision": node.revision,
        "payload_schema_version": node.payload_schema_version,
        "updated_at": node.updated_at.isoformat() if node.updated_at else None,
        "deleted_at": node.deleted_at.isoformat() if node.deleted_at else None,
        "updated_by_device": str(node.updated_by_device) if node.updated_by_device else None,
    }


def _memory_payload(memory: AssistantMemory) -> dict[str, Any]:
    return {
        "id": str(memory.id),
        "assistant_id": memory.assistant_id,
        "content": memory.content,
        "revision": memory.revision,
        "payload_schema_version": memory.payload_schema_version,
        "updated_at": memory.updated_at.isoformat() if memory.updated_at else None,
        "deleted_at": memory.deleted_at.isoformat() if memory.deleted_at else None,
        "updated_by_device": str(memory.updated_by_device) if memory.updated_by_device else None,
    }


def _file_payload(row: FileObject) -> dict[str, Any]:
    return _file_payload_from_service(row)


def _favorite_payload(favorite: Favorite) -> dict[str, Any]:
    meta: Any = None
    if favorite.meta_json:
        try:
            meta = json.loads(favorite.meta_json)
        except json.JSONDecodeError:
            meta = favorite.meta_json
    ref: Any = {}
    if favorite.ref_json:
        try:
            ref = json.loads(favorite.ref_json)
        except json.JSONDecodeError:
            ref = favorite.ref_json
    return {
        "id": favorite.id,
        "type": favorite.type,
        "ref_key": favorite.ref_key,
        "ref_json": ref,
        "snapshot_json": favorite.snapshot_json,
        "meta_json": meta,
        "created_at_ms": favorite.created_at_ms,
        "updated_at_ms": favorite.updated_at_ms,
        "revision": favorite.revision,
        "payload_schema_version": favorite.payload_schema_version,
        "updated_at": favorite.updated_at.isoformat() if favorite.updated_at else None,
        "deleted_at": favorite.deleted_at.isoformat() if favorite.deleted_at else None,
        "updated_by_device": str(favorite.updated_by_device) if favorite.updated_by_device else None,
    }


def _extract_memory_fields(payload: dict[str, Any]) -> dict[str, Any]:
    body = payload.get("payload")
    if not isinstance(body, dict):
        body = {}
    src = {**body, **{k: v for k, v in payload.items() if k != "payload"}}
    assistant_raw = src.get("assistant_id") if "assistant_id" in src else src.get("assistantId")
    if assistant_raw is None or str(assistant_raw).strip() == "":
        raise ValueError("assistant_id is required")
    assistant_id = str(assistant_raw)
    if assistant_id != "__global__":
        # Validate UUID form for non-global memories, but store as string.
        UUID(assistant_id)
    content = str(src.get("content") or "")
    return {"assistant_id": assistant_id, "content": content}


def _extract_file_fields(payload: dict[str, Any]) -> dict[str, Any]:
    body = payload.get("payload")
    if not isinstance(body, dict):
        body = {}
    src = {**body, **{k: v for k, v in payload.items() if k != "payload"}}
    folder = str(src.get("folder") or "upload")
    display_name = str(
        src.get("display_name") if "display_name" in src else src.get("displayName") or "file"
    )
    mime_type = str(
        src.get("mime_type") if "mime_type" in src else src.get("mimeType") or "application/octet-stream"
    )
    size_bytes = int(src.get("size_bytes") if "size_bytes" in src else src.get("sizeBytes") or 0)
    sha256 = str(src.get("sha256") or "").lower()
    object_key = str(src.get("object_key") if "object_key" in src else src.get("objectKey") or "")
    upload_status = str(
        src.get("upload_status") if "upload_status" in src else src.get("uploadStatus") or "pending"
    )
    if upload_status not in {"pending", "ready", "failed", "deleted"}:
        upload_status = "pending"
    return {
        "folder": folder,
        "display_name": display_name,
        "mime_type": mime_type,
        "size_bytes": size_bytes,
        "sha256": sha256,
        "object_key": object_key,
        "upload_status": upload_status,
    }


def _extract_favorite_fields(payload: dict[str, Any]) -> dict[str, Any]:
    body = payload.get("payload")
    if not isinstance(body, dict):
        body = {}
    src = {**body, **{k: v for k, v in payload.items() if k != "payload"}}
    fav_type = str(src.get("type") or "node")
    ref_key = str(src.get("ref_key") if "ref_key" in src else src.get("refKey") or "")
    if not ref_key:
        raise ValueError("ref_key is required")
    ref_raw = src.get("ref_json") if "ref_json" in src else src.get("refJson")
    if isinstance(ref_raw, (dict, list)):
        ref_json = json.dumps(ref_raw, ensure_ascii=False, separators=(",", ":"))
    else:
        ref_json = str(ref_raw or "{}")
    snapshot = src.get("snapshot_json") if "snapshot_json" in src else src.get("snapshotJson")
    snapshot_json = "" if snapshot is None else str(snapshot)
    meta_raw = src.get("meta_json") if "meta_json" in src else src.get("metaJson")
    if meta_raw is None:
        meta_json = None
    elif isinstance(meta_raw, (dict, list)):
        meta_json = json.dumps(meta_raw, ensure_ascii=False, separators=(",", ":"))
    else:
        meta_json = str(meta_raw)
    created_at_ms = int(src.get("created_at_ms") if "created_at_ms" in src else src.get("createdAt") or 0)
    updated_at_ms = int(
        src.get("updated_at_ms") if "updated_at_ms" in src else src.get("updatedAt") or created_at_ms or 0
    )
    return {
        "type": fav_type,
        "ref_key": ref_key,
        "ref_json": ref_json,
        "snapshot_json": snapshot_json,
        "meta_json": meta_json,
        "created_at_ms": created_at_ms,
        "updated_at_ms": updated_at_ms,
    }


def _extract_message_node_fields(payload: dict[str, Any]) -> dict[str, Any]:
    body = payload.get("payload")
    if not isinstance(body, dict):
        body = {}
    src = {**body, **{k: v for k, v in payload.items() if k != "payload"}}

    conversation_raw = src.get("conversation_id") or src.get("conversationId")
    if conversation_raw is None or conversation_raw == "":
        raise ValueError("conversation_id is required")
    conversation_id = UUID(str(conversation_raw))

    node_index = int(src.get("node_index") if "node_index" in src else src.get("nodeIndex") or 0)
    select_index = int(
        src.get("select_index") if "select_index" in src else src.get("selectIndex") or 0
    )
    messages = src.get("messages")
    if messages is None:
        messages = []
    if not isinstance(messages, list):
        raise ValueError("messages must be a list")
    return {
        "conversation_id": conversation_id,
        "node_index": node_index,
        "select_index": select_index,
        "messages_json": json.dumps(messages, ensure_ascii=False, separators=(",", ":")),
    }


def _extract_folder_fields(payload: dict[str, Any]) -> dict[str, Any]:
    body = payload.get("payload")
    if not isinstance(body, dict):
        body = {}
    src = {**body, **{k: v for k, v in payload.items() if k != "payload"}}
    assistant_raw = src.get("assistant_id") or src.get("assistantId")
    if assistant_raw is None or assistant_raw == "":
        raise ValueError("assistant_id is required")
    return {
        "assistant_id": UUID(str(assistant_raw)),
        "name": str(src.get("name") or ""),
        "sort_index": int(src.get("sort_index") if "sort_index" in src else src.get("sortIndex") or 0),
        "create_at_ms": int(src.get("create_at_ms") or src.get("createAt") or 0),
    }


def _extract_conversation_fields(payload: dict[str, Any]) -> dict[str, Any]:
    """Normalize client mutation payload into column values + stored JSON body."""
    body = payload.get("payload")
    if not isinstance(body, dict):
        body = {}
    # Allow flat payload or nested under "payload"
    src = {**body, **{k: v for k, v in payload.items() if k != "payload"}}

    def as_uuid_str(value: Any, field: str) -> str:
        if value is None or value == "":
            raise ValueError(f"{field} is required")
        return str(UUID(str(value)))

    assistant_id = as_uuid_str(src.get("assistant_id") or src.get("assistantId"), "assistant_id")
    title = str(src.get("title") or "")
    create_at_ms = int(src.get("create_at_ms") or src.get("createAt") or 0)
    update_at_ms = int(src.get("update_at_ms") or src.get("updateAt") or create_at_ms or 0)
    is_pinned = bool(src.get("is_pinned") if "is_pinned" in src else src.get("isPinned", False))
    folder_raw = src.get("folder_id") if "folder_id" in src else src.get("folderId", "")
    folder_id = "" if folder_raw in (None, "") else str(folder_raw)
    sync_enabled = bool(
        src.get("sync_enabled") if "sync_enabled" in src else src.get("syncEnabled", True)
    )
    stored = {
        "assistant_id": assistant_id,
        "title": title,
        "create_at_ms": create_at_ms,
        "update_at_ms": update_at_ms,
        "is_pinned": is_pinned,
        "folder_id": folder_id,
        "sync_enabled": sync_enabled,
        "chat_suggestions": src.get("chat_suggestions") or src.get("chatSuggestions") or [],
        "custom_system_prompt": src.get("custom_system_prompt")
        if "custom_system_prompt" in src
        else src.get("customSystemPrompt"),
        "mode_injection_ids": src.get("mode_injection_ids")
        if "mode_injection_ids" in src
        else src.get("modeInjectionIds") or [],
        "lorebook_ids": src.get("lorebook_ids")
        if "lorebook_ids" in src
        else src.get("lorebookIds") or [],
    }
    return {
        "assistant_id": UUID(assistant_id),
        "title": title,
        "create_at_ms": create_at_ms,
        "update_at_ms": update_at_ms,
        "is_pinned": is_pinned,
        "folder_id": folder_id,
        "sync_enabled": sync_enabled,
        "payload_json": json.dumps(stored, ensure_ascii=False, separators=(",", ":")),
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
    conversations_result = await session.execute(
        select(Conversation)
        .where(Conversation.user_id == user_id, Conversation.deleted_at.is_(None))
        .order_by(
            Conversation.is_pinned.desc(),
            Conversation.update_at_ms.desc(),
            Conversation.id.desc(),
        )
        .limit(BOOTSTRAP_CONVERSATION_LIMIT)
    )
    folders_result = await session.execute(
        select(ConversationFolder).where(
            ConversationFolder.user_id == user_id,
            ConversationFolder.deleted_at.is_(None),
        )
    )
    memories_result = await session.execute(
        select(AssistantMemory).where(
            AssistantMemory.user_id == user_id,
            AssistantMemory.deleted_at.is_(None),
        )
    )
    favorites_result = await session.execute(
        select(Favorite).where(Favorite.user_id == user_id, Favorite.deleted_at.is_(None))
    )
    files_result = await session.execute(
        select(FileObject).where(FileObject.user_id == user_id, FileObject.deleted_at.is_(None))
    )
    return BootstrapResponse(
        cursor=cursor,
        server_time=_now().isoformat(),
        settings=[_setting_payload(row) for row in settings_result.scalars().all()],
        assistants=[_assistant_payload(row) for row in assistants_result.scalars().all()],
        conversations=[
            _conversation_payload(row) for row in conversations_result.scalars().all()
        ],
        conversation_folders=[_folder_payload(row) for row in folders_result.scalars().all()],
        assistant_memories=[_memory_payload(row) for row in memories_result.scalars().all()],
        favorites=[_favorite_payload(row) for row in favorites_result.scalars().all()],
        files=[_file_payload(row) for row in files_result.scalars().all()],
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
    elif item.entity_type == "conversation":
        result = await _apply_conversation_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    elif item.entity_type == "conversation_folder":
        result = await _apply_folder_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    elif item.entity_type == "message_node":
        result = await _apply_message_node_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    elif item.entity_type == "assistant_memory":
        result = await _apply_memory_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    elif item.entity_type == "favorite":
        result = await _apply_favorite_mutation(
            session, user_id=user_id, device_id=device_id, item=item
        )
    elif item.entity_type == "file":
        result = await _apply_file_mutation(
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


async def _apply_folder_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    try:
        folder_id = UUID(item.entity_id)
    except ValueError:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id must be a UUID for conversation_folder",
        )

    result = await session.execute(
        select(ConversationFolder).where(
            ConversationFolder.user_id == user_id, ConversationFolder.id == folder_id
        )
    )
    folder = result.scalar_one_or_none()

    if item.operation == "delete":
        if folder is None or folder.deleted_at is not None:
            rev = folder.revision if folder is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_folder_payload(folder) if folder else None,
            )
        if item.base_revision != 0 and item.base_revision != folder.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=folder.revision,
                server_payload=_folder_payload(folder),
                message="base_revision mismatch",
            )
        folder.revision += 1
        folder.deleted_at = _now()
        folder.updated_at = _now()
        folder.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="conversation_folder",
            entity_id=str(folder_id),
            operation="delete",
            revision=folder.revision,
            payload=_folder_payload(folder),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=folder.revision,
            server_payload=_folder_payload(folder),
        )

    try:
        fields = _extract_folder_fields(item.payload or {})
    except (ValueError, TypeError) as exc:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=str(exc),
        )

    if folder is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        folder = ConversationFolder(
            id=folder_id,
            user_id=user_id,
            assistant_id=fields["assistant_id"],
            name=fields["name"],
            sort_index=fields["sort_index"],
            create_at_ms=fields["create_at_ms"],
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(folder)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="conversation_folder",
            entity_id=str(folder_id),
            operation="upsert",
            revision=folder.revision,
            payload=_folder_payload(folder),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=folder.revision,
            server_payload=_folder_payload(folder),
        )

    if item.base_revision != folder.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=folder.revision,
            server_payload=_folder_payload(folder),
            message="base_revision mismatch",
        )

    folder.assistant_id = fields["assistant_id"]
    folder.name = fields["name"]
    folder.sort_index = fields["sort_index"]
    folder.create_at_ms = fields["create_at_ms"]
    folder.revision += 1
    folder.payload_schema_version = item.payload_schema_version
    folder.updated_at = _now()
    folder.updated_by_device = device_id
    folder.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="conversation_folder",
        entity_id=str(folder_id),
        operation="upsert",
        revision=folder.revision,
        payload=_folder_payload(folder),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=folder.revision,
        server_payload=_folder_payload(folder),
    )


async def _apply_conversation_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    try:
        conversation_id = UUID(item.entity_id)
    except ValueError:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id must be a UUID for conversation",
        )

    result = await session.execute(
        select(Conversation).where(
            Conversation.user_id == user_id, Conversation.id == conversation_id
        )
    )
    conversation = result.scalar_one_or_none()

    if item.operation == "delete":
        if conversation is None or conversation.deleted_at is not None:
            rev = conversation.revision if conversation is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_conversation_payload(conversation) if conversation else None,
            )
        if item.base_revision != 0 and item.base_revision != conversation.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=conversation.revision,
                server_payload=_conversation_payload(conversation),
                message="base_revision mismatch",
            )
        conversation.revision += 1
        conversation.deleted_at = _now()
        conversation.updated_at = _now()
        conversation.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="conversation",
            entity_id=str(conversation_id),
            operation="delete",
            revision=conversation.revision,
            payload=_conversation_payload(conversation),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=conversation.revision,
            server_payload=_conversation_payload(conversation),
        )

    try:
        fields = _extract_conversation_fields(item.payload or {})
    except (ValueError, TypeError) as exc:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=str(exc),
        )

    if conversation is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        conversation = Conversation(
            id=conversation_id,
            user_id=user_id,
            assistant_id=fields["assistant_id"],
            title=fields["title"],
            create_at_ms=fields["create_at_ms"],
            update_at_ms=fields["update_at_ms"],
            is_pinned=fields["is_pinned"],
            folder_id=fields["folder_id"],
            sync_enabled=fields["sync_enabled"],
            payload_json=fields["payload_json"],
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(conversation)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="conversation",
            entity_id=str(conversation_id),
            operation="upsert",
            revision=conversation.revision,
            payload=_conversation_payload(conversation),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=conversation.revision,
            server_payload=_conversation_payload(conversation),
        )

    if item.base_revision != conversation.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=conversation.revision,
            server_payload=_conversation_payload(conversation),
            message="base_revision mismatch",
        )

    conversation.assistant_id = fields["assistant_id"]
    conversation.title = fields["title"]
    conversation.create_at_ms = fields["create_at_ms"]
    conversation.update_at_ms = fields["update_at_ms"]
    conversation.is_pinned = fields["is_pinned"]
    conversation.folder_id = fields["folder_id"]
    conversation.sync_enabled = fields["sync_enabled"]
    conversation.payload_json = fields["payload_json"]
    conversation.revision += 1
    conversation.payload_schema_version = item.payload_schema_version
    conversation.updated_at = _now()
    conversation.updated_by_device = device_id
    conversation.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="conversation",
        entity_id=str(conversation_id),
        operation="upsert",
        revision=conversation.revision,
        payload=_conversation_payload(conversation),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=conversation.revision,
        server_payload=_conversation_payload(conversation),
    )


async def _apply_message_node_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    try:
        node_id = UUID(item.entity_id)
    except ValueError:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id must be a UUID for message_node",
        )

    result = await session.execute(
        select(MessageNode).where(MessageNode.user_id == user_id, MessageNode.id == node_id)
    )
    node = result.scalar_one_or_none()

    if item.operation == "delete":
        if node is None or node.deleted_at is not None:
            rev = node.revision if node is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_message_node_payload(node) if node else None,
            )
        if item.base_revision != 0 and item.base_revision != node.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=node.revision,
                server_payload=_message_node_payload(node),
                message="base_revision mismatch",
            )
        node.revision += 1
        node.deleted_at = _now()
        node.updated_at = _now()
        node.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="message_node",
            entity_id=str(node_id),
            operation="delete",
            revision=node.revision,
            payload=_message_node_payload(node),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=node.revision,
            server_payload=_message_node_payload(node),
        )

    try:
        fields = _extract_message_node_fields(item.payload or {})
    except (ValueError, TypeError) as exc:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=str(exc),
        )

    # Parent conversation must exist and belong to user (or be syncing in same batch).
    parent = await session.execute(
        select(Conversation).where(
            Conversation.user_id == user_id,
            Conversation.id == fields["conversation_id"],
        )
    )
    conversation = parent.scalar_one_or_none()
    if conversation is None:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="conversation not found for message_node",
        )
    if conversation.deleted_at is not None:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="conversation is deleted",
        )
    if not conversation.sync_enabled:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="conversation sync_enabled=false",
        )

    # Free unique (conversation_id, node_index) if held by another live node.
    conflict_result = await session.execute(
        select(MessageNode).where(
            MessageNode.user_id == user_id,
            MessageNode.conversation_id == fields["conversation_id"],
            MessageNode.node_index == fields["node_index"],
            MessageNode.id != node_id,
            MessageNode.deleted_at.is_(None),
        )
    )
    index_holder = conflict_result.scalar_one_or_none()
    if index_holder is not None:
        index_holder.revision += 1
        index_holder.deleted_at = _now()
        index_holder.updated_at = _now()
        index_holder.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="message_node",
            entity_id=str(index_holder.id),
            operation="delete",
            revision=index_holder.revision,
            payload=_message_node_payload(index_holder),
        )

    if node is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        node = MessageNode(
            id=node_id,
            user_id=user_id,
            conversation_id=fields["conversation_id"],
            node_index=fields["node_index"],
            messages_json=fields["messages_json"],
            select_index=fields["select_index"],
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(node)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="message_node",
            entity_id=str(node_id),
            operation="upsert",
            revision=node.revision,
            payload=_message_node_payload(node),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=node.revision,
            server_payload=_message_node_payload(node),
        )

    if item.base_revision != node.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=node.revision,
            server_payload=_message_node_payload(node),
            message="base_revision mismatch",
        )

    node.conversation_id = fields["conversation_id"]
    node.node_index = fields["node_index"]
    node.messages_json = fields["messages_json"]
    node.select_index = fields["select_index"]
    node.revision += 1
    node.payload_schema_version = item.payload_schema_version
    node.updated_at = _now()
    node.updated_by_device = device_id
    node.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="message_node",
        entity_id=str(node_id),
        operation="upsert",
        revision=node.revision,
        payload=_message_node_payload(node),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=node.revision,
        server_payload=_message_node_payload(node),
    )


async def _apply_memory_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    try:
        memory_id = UUID(item.entity_id)
    except ValueError:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id must be a UUID for assistant_memory",
        )

    result = await session.execute(
        select(AssistantMemory).where(
            AssistantMemory.user_id == user_id,
            AssistantMemory.id == memory_id,
        )
    )
    memory = result.scalar_one_or_none()

    if item.operation == "delete":
        if memory is None or memory.deleted_at is not None:
            rev = memory.revision if memory is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_memory_payload(memory) if memory else None,
            )
        if item.base_revision != 0 and item.base_revision != memory.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=memory.revision,
                server_payload=_memory_payload(memory),
                message="base_revision mismatch",
            )
        memory.revision += 1
        memory.deleted_at = _now()
        memory.updated_at = _now()
        memory.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="assistant_memory",
            entity_id=str(memory_id),
            operation="delete",
            revision=memory.revision,
            payload=_memory_payload(memory),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=memory.revision,
            server_payload=_memory_payload(memory),
        )

    try:
        fields = _extract_memory_fields(item.payload or {})
    except (ValueError, TypeError) as exc:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=str(exc),
        )

    if memory is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        memory = AssistantMemory(
            id=memory_id,
            user_id=user_id,
            assistant_id=fields["assistant_id"],
            content=fields["content"],
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(memory)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="assistant_memory",
            entity_id=str(memory_id),
            operation="upsert",
            revision=memory.revision,
            payload=_memory_payload(memory),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=memory.revision,
            server_payload=_memory_payload(memory),
        )

    if item.base_revision != memory.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=memory.revision,
            server_payload=_memory_payload(memory),
            message="base_revision mismatch",
        )

    memory.assistant_id = fields["assistant_id"]
    memory.content = fields["content"]
    memory.revision += 1
    memory.payload_schema_version = item.payload_schema_version
    memory.updated_at = _now()
    memory.updated_by_device = device_id
    memory.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="assistant_memory",
        entity_id=str(memory_id),
        operation="upsert",
        revision=memory.revision,
        payload=_memory_payload(memory),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=memory.revision,
        server_payload=_memory_payload(memory),
    )


async def _apply_favorite_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    favorite_id = item.entity_id
    if not favorite_id or len(favorite_id) > 256:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id invalid for favorite",
        )

    result = await session.execute(
        select(Favorite).where(Favorite.user_id == user_id, Favorite.id == favorite_id)
    )
    favorite = result.scalar_one_or_none()

    if item.operation == "delete":
        if favorite is None or favorite.deleted_at is not None:
            rev = favorite.revision if favorite is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_favorite_payload(favorite) if favorite else None,
            )
        if item.base_revision != 0 and item.base_revision != favorite.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=favorite.revision,
                server_payload=_favorite_payload(favorite),
                message="base_revision mismatch",
            )
        favorite.revision += 1
        favorite.deleted_at = _now()
        favorite.updated_at = _now()
        favorite.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="favorite",
            entity_id=favorite_id,
            operation="delete",
            revision=favorite.revision,
            payload=_favorite_payload(favorite),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=favorite.revision,
            server_payload=_favorite_payload(favorite),
        )

    try:
        fields = _extract_favorite_fields(item.payload or {})
    except (ValueError, TypeError) as exc:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=str(exc),
        )

    # Unique (user_id, ref_key): tombstone other live row with same ref_key.
    ref_holder_result = await session.execute(
        select(Favorite).where(
            Favorite.user_id == user_id,
            Favorite.ref_key == fields["ref_key"],
            Favorite.id != favorite_id,
            Favorite.deleted_at.is_(None),
        )
    )
    ref_holder = ref_holder_result.scalar_one_or_none()
    if ref_holder is not None:
        ref_holder.revision += 1
        ref_holder.deleted_at = _now()
        ref_holder.updated_at = _now()
        ref_holder.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="favorite",
            entity_id=ref_holder.id,
            operation="delete",
            revision=ref_holder.revision,
            payload=_favorite_payload(ref_holder),
        )

    if favorite is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        favorite = Favorite(
            id=favorite_id,
            user_id=user_id,
            type=fields["type"],
            ref_key=fields["ref_key"],
            ref_json=fields["ref_json"],
            snapshot_json=fields["snapshot_json"],
            meta_json=fields["meta_json"],
            created_at_ms=fields["created_at_ms"],
            updated_at_ms=fields["updated_at_ms"],
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(favorite)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="favorite",
            entity_id=favorite_id,
            operation="upsert",
            revision=favorite.revision,
            payload=_favorite_payload(favorite),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=favorite.revision,
            server_payload=_favorite_payload(favorite),
        )

    if item.base_revision != favorite.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=favorite.revision,
            server_payload=_favorite_payload(favorite),
            message="base_revision mismatch",
        )

    favorite.type = fields["type"]
    favorite.ref_key = fields["ref_key"]
    favorite.ref_json = fields["ref_json"]
    favorite.snapshot_json = fields["snapshot_json"]
    favorite.meta_json = fields["meta_json"]
    favorite.created_at_ms = fields["created_at_ms"]
    favorite.updated_at_ms = fields["updated_at_ms"]
    favorite.revision += 1
    favorite.payload_schema_version = item.payload_schema_version
    favorite.updated_at = _now()
    favorite.updated_by_device = device_id
    favorite.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="favorite",
        entity_id=favorite_id,
        operation="upsert",
        revision=favorite.revision,
        payload=_favorite_payload(favorite),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=favorite.revision,
        server_payload=_favorite_payload(favorite),
    )


async def _apply_file_mutation(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    item: MutationItem,
) -> MutationResult:
    try:
        file_id = UUID(item.entity_id)
    except ValueError:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message="entity_id must be a UUID for file",
        )

    result = await session.execute(
        select(FileObject).where(FileObject.user_id == user_id, FileObject.id == file_id)
    )
    row = result.scalar_one_or_none()

    if item.operation == "delete":
        if row is None or row.deleted_at is not None:
            rev = row.revision if row is not None else 0
            return MutationResult(
                mutation_id=item.mutation_id,
                status="applied",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=rev,
                server_payload=_file_payload(row) if row else None,
            )
        if item.base_revision != 0 and item.base_revision != row.revision:
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=row.revision,
                server_payload=_file_payload(row),
                message="base_revision mismatch",
            )
        row.revision += 1
        row.deleted_at = _now()
        row.upload_status = "deleted"
        row.updated_at = _now()
        row.updated_by_device = device_id
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="file",
            entity_id=str(file_id),
            operation="delete",
            revision=row.revision,
            payload=_file_payload(row),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=row.revision,
            server_payload=_file_payload(row),
        )

    try:
        fields = _extract_file_fields(item.payload or {})
    except (ValueError, TypeError) as exc:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="rejected",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            message=str(exc),
        )

    if row is None:
        if item.base_revision not in (0,):
            return MutationResult(
                mutation_id=item.mutation_id,
                status="conflict",
                entity_type=item.entity_type,
                entity_id=item.entity_id,
                revision=0,
                message="entity does not exist; base_revision must be 0",
            )
        object_key = fields["object_key"] or f"users/{user_id}/files/{file_id}/{fields['display_name']}"
        row = FileObject(
            id=file_id,
            user_id=user_id,
            folder=fields["folder"],
            display_name=fields["display_name"],
            mime_type=fields["mime_type"],
            size_bytes=fields["size_bytes"],
            sha256=fields["sha256"],
            object_key=object_key,
            upload_status=fields["upload_status"],
            revision=1,
            payload_schema_version=item.payload_schema_version,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(row)
        await session.flush()
        await _append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_type="file",
            entity_id=str(file_id),
            operation="upsert",
            revision=row.revision,
            payload=_file_payload(row),
        )
        return MutationResult(
            mutation_id=item.mutation_id,
            status="applied",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=row.revision,
            server_payload=_file_payload(row),
        )

    if item.base_revision != row.revision:
        return MutationResult(
            mutation_id=item.mutation_id,
            status="conflict",
            entity_type=item.entity_type,
            entity_id=item.entity_id,
            revision=row.revision,
            server_payload=_file_payload(row),
            message="base_revision mismatch",
        )

    row.folder = fields["folder"]
    row.display_name = fields["display_name"]
    row.mime_type = fields["mime_type"]
    row.size_bytes = fields["size_bytes"]
    if fields["sha256"]:
        row.sha256 = fields["sha256"]
    if fields["object_key"]:
        row.object_key = fields["object_key"]
    row.upload_status = fields["upload_status"]
    row.revision += 1
    row.payload_schema_version = item.payload_schema_version
    row.updated_at = _now()
    row.updated_by_device = device_id
    row.deleted_at = None
    await session.flush()
    await _append_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_type="file",
        entity_id=str(file_id),
        operation="upsert",
        revision=row.revision,
        payload=_file_payload(row),
    )
    return MutationResult(
        mutation_id=item.mutation_id,
        status="applied",
        entity_type=item.entity_type,
        entity_id=item.entity_id,
        revision=row.revision,
        server_payload=_file_payload(row),
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
