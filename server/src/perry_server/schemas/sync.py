from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, Field


class MutationItem(BaseModel):
    mutation_id: UUID
    entity_type: str = Field(min_length=1, max_length=64)
    entity_id: str = Field(min_length=1, max_length=64)
    operation: Literal["upsert", "delete"]
    base_revision: int = Field(ge=0)
    payload_schema_version: int = Field(default=1, ge=1)
    payload: dict[str, Any] | None = None


class MutationsRequest(BaseModel):
    device_id: UUID
    mutations: list[MutationItem] = Field(min_length=1, max_length=100)


class MutationResult(BaseModel):
    mutation_id: UUID
    status: Literal["applied", "already_applied", "conflict", "rejected"]
    entity_type: str
    entity_id: str
    revision: int | None = None
    server_payload: dict[str, Any] | None = None
    message: str | None = None


class MutationsResponse(BaseModel):
    results: list[MutationResult]
    cursor: int


class ChangeItem(BaseModel):
    seq: int
    entity_type: str
    entity_id: str
    operation: str
    revision: int
    changed_at: str
    changed_by_device: UUID | None = None
    payload: dict[str, Any] | None = None


class ChangesResponse(BaseModel):
    changes: list[ChangeItem]
    next_cursor: int
    has_more: bool


class BootstrapResponse(BaseModel):
    cursor: int
    server_time: str
    settings: list[dict[str, Any]] = Field(default_factory=list)
