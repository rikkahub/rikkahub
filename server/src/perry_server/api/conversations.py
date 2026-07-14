from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.deps import AuthContext, get_db, require_device
from perry_server.errors import AppError
from perry_server.schemas.conversations import (
    ConversationListResponse,
    ConversationSummary,
    MessageNodeChangesResponse,
    MessageNodeDto,
    MessageNodeListResponse,
)
from perry_server.services import conversations as conversations_service
from perry_server.services import message_nodes as message_nodes_service

router = APIRouter(prefix="/v1/conversations", tags=["conversations"])


@router.get("", response_model=ConversationListResponse)
async def list_conversations(
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    cursor: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
    assistant_id: Annotated[UUID | None, Query()] = None,
) -> ConversationListResponse:
    try:
        return await conversations_service.list_conversations(
            session,
            user_id=auth.user_id,
            limit=limit,
            cursor=cursor,
            assistant_id=assistant_id,
        )
    except ValueError as exc:
        raise AppError("invalid_cursor", str(exc), status_code=400) from exc


@router.get("/{conversation_id}", response_model=ConversationSummary)
async def get_conversation(
    conversation_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> ConversationSummary:
    row = await conversations_service.get_conversation(
        session, user_id=auth.user_id, conversation_id=conversation_id
    )
    if row is None:
        raise AppError("not_found", "conversation not found", status_code=404)
    return conversations_service.to_summary(row)


@router.get("/{conversation_id}/nodes", response_model=MessageNodeListResponse)
async def list_message_nodes(
    conversation_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    before_index: Annotated[int | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
) -> MessageNodeListResponse:
    result = await message_nodes_service.list_nodes(
        session,
        user_id=auth.user_id,
        conversation_id=conversation_id,
        before_index=before_index,
        limit=limit,
    )
    if result is None:
        raise AppError("not_found", "conversation not found", status_code=404)
    return result


@router.get("/{conversation_id}/nodes/changes", response_model=MessageNodeChangesResponse)
async def list_message_node_changes(
    conversation_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    since_revision: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=500)] = 200,
) -> MessageNodeChangesResponse:
    result = await message_nodes_service.list_node_changes(
        session,
        user_id=auth.user_id,
        conversation_id=conversation_id,
        since_revision=since_revision,
        limit=limit,
    )
    if result is None:
        raise AppError("not_found", "conversation not found", status_code=404)
    return result


@router.get("/{conversation_id}/nodes/{node_id}", response_model=MessageNodeDto)
async def get_message_node(
    conversation_id: UUID,
    node_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> MessageNodeDto:
    row = await message_nodes_service.get_node(
        session,
        user_id=auth.user_id,
        conversation_id=conversation_id,
        node_id=node_id,
    )
    if row is None or row.deleted_at is not None:
        raise AppError("not_found", "message node not found", status_code=404)
    return message_nodes_service.to_dto(row)


@router.delete("/{conversation_id}", status_code=204)
async def delete_conversation(
    conversation_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> None:
    # Soft-delete via sync mutations is preferred; this endpoint is a convenience
    # for remote clients and writes a tombstone through the mutation path shape.
    from uuid import uuid4

    from perry_server.schemas.sync import MutationItem
    from perry_server.services import sync as sync_service

    row = await conversations_service.get_conversation(
        session, user_id=auth.user_id, conversation_id=conversation_id
    )
    if row is None:
        raise AppError("not_found", "conversation not found", status_code=404)
    await sync_service.apply_mutations(
        session,
        user_id=auth.user_id,
        device_id=auth.device_id,
        mutations=[
            MutationItem(
                mutation_id=uuid4(),
                entity_type="conversation",
                entity_id=str(conversation_id),
                operation="delete",
                base_revision=row.revision,
                payload=None,
            )
        ],
    )
