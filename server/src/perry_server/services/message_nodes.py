from __future__ import annotations

import json
from typing import Any
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.models.conversation import Conversation
from perry_server.models.message_node import MessageNode
from perry_server.schemas.conversations import (
    MessageNodeChangesResponse,
    MessageNodeDto,
    MessageNodeListResponse,
)


def to_dto(row: MessageNode) -> MessageNodeDto:
    messages: list[Any] = []
    if row.messages_json:
        try:
            parsed = json.loads(row.messages_json)
            if isinstance(parsed, list):
                messages = parsed
        except json.JSONDecodeError:
            messages = []
    return MessageNodeDto(
        id=row.id,
        conversation_id=row.conversation_id,
        node_index=row.node_index,
        select_index=row.select_index,
        messages=messages,
        revision=row.revision,
        deleted_at=row.deleted_at.isoformat() if row.deleted_at else None,
    )


async def _owned_conversation(
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


async def list_nodes(
    session: AsyncSession,
    *,
    user_id: UUID,
    conversation_id: UUID,
    before_index: int | None = None,
    limit: int = 50,
    include_deleted: bool = False,
) -> MessageNodeListResponse | None:
    """Return newest nodes first by node_index DESC, optional history page via before_index."""
    if await _owned_conversation(session, user_id=user_id, conversation_id=conversation_id) is None:
        return None

    limit = max(1, min(limit, 200))
    stmt = select(MessageNode).where(
        MessageNode.user_id == user_id,
        MessageNode.conversation_id == conversation_id,
    )
    if not include_deleted:
        stmt = stmt.where(MessageNode.deleted_at.is_(None))
    if before_index is not None:
        stmt = stmt.where(MessageNode.node_index < before_index)
    stmt = stmt.order_by(MessageNode.node_index.desc()).limit(limit + 1)

    result = await session.execute(stmt)
    rows = list(result.scalars().all())
    has_more = len(rows) > limit
    rows = rows[:limit]
    # Client usually wants chronological order for display
    rows_asc = list(reversed(rows))
    oldest = rows_asc[0].node_index if rows_asc else None
    return MessageNodeListResponse(
        items=[to_dto(r) for r in rows_asc],
        has_more=has_more,
        oldest_index=oldest,
    )


async def list_node_changes(
    session: AsyncSession,
    *,
    user_id: UUID,
    conversation_id: UUID,
    since_revision: int = 0,
    limit: int = 200,
) -> MessageNodeChangesResponse | None:
    if await _owned_conversation(session, user_id=user_id, conversation_id=conversation_id) is None:
        return None

    limit = max(1, min(limit, 500))
    result = await session.execute(
        select(MessageNode)
        .where(
            MessageNode.user_id == user_id,
            MessageNode.conversation_id == conversation_id,
            MessageNode.revision > since_revision,
        )
        .order_by(MessageNode.revision.asc())
        .limit(limit)
    )
    rows = list(result.scalars().all())
    max_rev_result = await session.execute(
        select(func.coalesce(func.max(MessageNode.revision), 0)).where(
            MessageNode.user_id == user_id,
            MessageNode.conversation_id == conversation_id,
        )
    )
    max_revision = int(max_rev_result.scalar_one() or 0)
    return MessageNodeChangesResponse(
        items=[to_dto(r) for r in rows],
        max_revision=max_revision,
    )


async def get_node(
    session: AsyncSession,
    *,
    user_id: UUID,
    conversation_id: UUID,
    node_id: UUID,
) -> MessageNode | None:
    result = await session.execute(
        select(MessageNode).where(
            MessageNode.user_id == user_id,
            MessageNode.conversation_id == conversation_id,
            MessageNode.id == node_id,
        )
    )
    return result.scalar_one_or_none()
