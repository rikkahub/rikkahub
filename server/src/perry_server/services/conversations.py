from __future__ import annotations

import base64
import json
from typing import Any
from uuid import UUID

from sqlalchemy import and_, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.models.conversation import Conversation
from perry_server.schemas.conversations import ConversationListResponse, ConversationSummary


def _encode_cursor(update_at_ms: int, conversation_id: UUID) -> str:
    raw = f"{update_at_ms}:{conversation_id}"
    return base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii")


def _decode_cursor(cursor: str) -> tuple[int, UUID]:
    raw = base64.urlsafe_b64decode(cursor.encode("ascii")).decode("utf-8")
    update_at_ms_s, id_s = raw.split(":", 1)
    return int(update_at_ms_s), UUID(id_s)


def to_summary(row: Conversation) -> ConversationSummary:
    payload: dict[str, Any] = {}
    if row.payload_json:
        try:
            payload = json.loads(row.payload_json)
        except json.JSONDecodeError:
            payload = {}
    return ConversationSummary(
        id=row.id,
        assistant_id=row.assistant_id,
        title=row.title,
        create_at_ms=row.create_at_ms,
        update_at_ms=row.update_at_ms,
        is_pinned=row.is_pinned,
        folder_id=row.folder_id or "",
        sync_enabled=row.sync_enabled,
        revision=row.revision,
        payload=payload,
        deleted_at=row.deleted_at.isoformat() if row.deleted_at else None,
    )


async def list_conversations(
    session: AsyncSession,
    *,
    user_id: UUID,
    limit: int = 30,
    cursor: str | None = None,
    assistant_id: UUID | None = None,
) -> ConversationListResponse:
    limit = max(1, min(limit, 100))
    stmt = select(Conversation).where(
        Conversation.user_id == user_id,
        Conversation.deleted_at.is_(None),
        Conversation.sync_enabled.is_(True),
    )
    if assistant_id is not None:
        stmt = stmt.where(Conversation.assistant_id == assistant_id)

    if cursor:
        try:
            cursor_ms, cursor_id = _decode_cursor(cursor)
        except Exception as exc:  # noqa: BLE001
            raise ValueError("invalid cursor") from exc
        # (update_at_ms, id) DESC page
        stmt = stmt.where(
            or_(
                Conversation.update_at_ms < cursor_ms,
                and_(
                    Conversation.update_at_ms == cursor_ms,
                    Conversation.id < cursor_id,
                ),
            )
        )

    # Cursor is (update_at_ms, id) only — pin is a payload field; client may re-sort.
    stmt = stmt.order_by(
        Conversation.update_at_ms.desc(),
        Conversation.id.desc(),
    ).limit(limit + 1)

    result = await session.execute(stmt)
    rows = list(result.scalars().all())
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = None
    if has_more and rows:
        last = rows[-1]
        next_cursor = _encode_cursor(last.update_at_ms, last.id)
    return ConversationListResponse(
        items=[to_summary(r) for r in rows],
        next_cursor=next_cursor,
        has_more=has_more,
    )


async def get_conversation(
    session: AsyncSession,
    *,
    user_id: UUID,
    conversation_id: UUID,
) -> Conversation | None:
    result = await session.execute(
        select(Conversation).where(
            Conversation.user_id == user_id,
            Conversation.id == conversation_id,
            Conversation.deleted_at.is_(None),
        )
    )
    return result.scalar_one_or_none()
