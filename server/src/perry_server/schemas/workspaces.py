from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


class WorkspaceCreateRequest(BaseModel):
    id: UUID | None = None
    name: str = Field(min_length=1, max_length=128)
    image: str | None = Field(default=None, max_length=256)
    tool_approvals: dict[str, bool] = Field(default_factory=dict)


class WorkspaceUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=128)
    tool_approvals: dict[str, bool] | None = None


class WorkspaceDto(BaseModel):
    id: UUID
    name: str
    image: str
    shell_status: str
    tool_approvals: dict[str, bool] = Field(default_factory=dict)
    created_at_ms: int
    updated_at_ms: int
    last_access_at_ms: int | None = None


class WorkspaceListResponse(BaseModel):
    items: list[WorkspaceDto] = Field(default_factory=list)


class WorkspaceExecuteRequest(BaseModel):
    command: str = Field(min_length=1, max_length=256_000)
    cwd: str = ""
    timeout_ms: int = Field(default=30_000, ge=1_000, le=600_000)
    stdin_base64: str | None = None


class WorkspaceCommandResultDto(BaseModel):
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False
    truncated: bool = False


class WorkspaceFileEntryDto(BaseModel):
    path: str
    name: str
    is_directory: bool
    size_bytes: int
    updated_at_ms: int


class WorkspaceFileListResponse(BaseModel):
    items: list[WorkspaceFileEntryDto] = Field(default_factory=list)


class WorkspaceMoveRequest(BaseModel):
    source: str
    target: str
    overwrite: bool = False


class WorkspaceStatusResponse(BaseModel):
    status: str
    details: dict[str, Any] = Field(default_factory=dict)
