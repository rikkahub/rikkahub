from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


class ConversationSummary(BaseModel):
    id: UUID
    assistant_id: UUID
    title: str
    create_at_ms: int
    update_at_ms: int
    is_pinned: bool
    folder_id: str = ""
    sync_enabled: bool = True
    revision: int
    payload: dict[str, Any] = Field(default_factory=dict)
    deleted_at: str | None = None


class ConversationListResponse(BaseModel):
    items: list[ConversationSummary]
    next_cursor: str | None = None
    has_more: bool = False
