from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, Field


class FileInitRequest(BaseModel):
    id: UUID | None = None
    folder: str = "upload"
    display_name: str = Field(min_length=1, max_length=512)
    mime_type: str = "application/octet-stream"
    size_bytes: int = Field(ge=0)
    sha256: str = Field(min_length=64, max_length=64)


class FileInitResponse(BaseModel):
    id: UUID
    upload_status: Literal["pending", "ready", "failed", "deleted"]
    object_key: str
    # Always proxy via Perry; MinIO credentials never leave the server.
    transfer_mode: Literal["proxy"] = "proxy"
    content_path: str
    # Deprecated: always null (kept for older clients).
    upload_url: str | None = None
    revision: int
    deduplicated: bool = False


class FileCompleteRequest(BaseModel):
    size_bytes: int | None = Field(default=None, ge=0)
    sha256: str | None = Field(default=None, min_length=64, max_length=64)


class FileDto(BaseModel):
    id: UUID
    folder: str
    display_name: str
    mime_type: str
    size_bytes: int
    sha256: str
    object_key: str
    upload_status: str
    revision: int
    payload_schema_version: int
    updated_at: str | None = None
    deleted_at: str | None = None
    updated_by_device: UUID | None = None
    payload: dict[str, Any] | None = None


class FileDownloadUrlResponse(BaseModel):
    id: UUID
    # Authenticated Perry content path (not a MinIO presign).
    download_url: str
    content_path: str
    transfer_mode: Literal["proxy"] = "proxy"
    expires_in_seconds: int = 0
    upload_status: str
