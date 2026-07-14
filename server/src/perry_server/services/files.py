from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.errors import AppError
from perry_server.models.change_log import ChangeLog
from perry_server.models.file_object import FileObject
from perry_server.schemas.files import (
    FileCompleteRequest,
    FileDownloadUrlResponse,
    FileDto,
    FileInitRequest,
    FileInitResponse,
)
from perry_server.services.storage import ObjectStorage


def _now() -> datetime:
    return datetime.now(UTC)


def file_payload(row: FileObject) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "folder": row.folder,
        "display_name": row.display_name,
        "mime_type": row.mime_type,
        "size_bytes": int(row.size_bytes),
        "sha256": row.sha256,
        "object_key": row.object_key,
        "upload_status": row.upload_status,
        "revision": row.revision,
        "payload_schema_version": row.payload_schema_version,
        "updated_at": row.updated_at.isoformat() if row.updated_at else None,
        "deleted_at": row.deleted_at.isoformat() if row.deleted_at else None,
        "updated_by_device": str(row.updated_by_device) if row.updated_by_device else None,
    }


def to_dto(row: FileObject) -> FileDto:
    payload = file_payload(row)
    return FileDto(
        id=row.id,
        folder=row.folder,
        display_name=row.display_name,
        mime_type=row.mime_type,
        size_bytes=int(row.size_bytes),
        sha256=row.sha256,
        object_key=row.object_key,
        upload_status=row.upload_status,
        revision=row.revision,
        payload_schema_version=row.payload_schema_version,
        updated_at=payload["updated_at"],
        deleted_at=payload["deleted_at"],
        updated_by_device=row.updated_by_device,
        payload=payload,
    )


async def _append_file_change(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID | None,
    entity_id: str,
    operation: str,
    revision: int,
    payload: dict[str, Any],
) -> None:
    import json

    session.add(
        ChangeLog(
            user_id=user_id,
            entity_type="file",
            entity_id=entity_id,
            operation=operation,
            revision=revision,
            changed_by_device=device_id,
            payload_json=json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        )
    )


async def get_file(
    session: AsyncSession,
    *,
    user_id: UUID,
    file_id: UUID,
) -> FileObject | None:
    result = await session.execute(
        select(FileObject).where(FileObject.user_id == user_id, FileObject.id == file_id)
    )
    return result.scalar_one_or_none()


async def init_upload(
    session: AsyncSession,
    storage: ObjectStorage,
    *,
    user_id: UUID,
    device_id: UUID,
    request: FileInitRequest,
) -> FileInitResponse:
    if not storage.is_configured():
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)

    sha = request.sha256.lower()
    if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
        raise AppError("invalid_sha256", "sha256 must be 64 hex chars", status_code=400)

    # Dedup: reuse ready object with same hash+size for this user.
    existing_ready = await session.execute(
        select(FileObject).where(
            FileObject.user_id == user_id,
            FileObject.sha256 == sha,
            FileObject.size_bytes == request.size_bytes,
            FileObject.upload_status == "ready",
            FileObject.deleted_at.is_(None),
        )
    )
    donor = existing_ready.scalars().first()

    file_id = request.id or uuid4()
    current = await get_file(session, user_id=user_id, file_id=file_id)
    storage.ensure_bucket()

    if donor is not None and (current is None or current.id != donor.id):
        object_key = donor.object_key
        if current is None:
            row = FileObject(
                id=file_id,
                user_id=user_id,
                folder=request.folder or "upload",
                display_name=request.display_name,
                mime_type=request.mime_type,
                size_bytes=request.size_bytes,
                sha256=sha,
                object_key=object_key,
                upload_status="ready",
                revision=1,
                payload_schema_version=1,
                updated_by_device=device_id,
                deleted_at=None,
            )
            session.add(row)
        else:
            current.folder = request.folder or current.folder
            current.display_name = request.display_name
            current.mime_type = request.mime_type
            current.size_bytes = request.size_bytes
            current.sha256 = sha
            current.object_key = object_key
            current.upload_status = "ready"
            current.revision += 1
            current.updated_at = _now()
            current.updated_by_device = device_id
            current.deleted_at = None
            row = current
        await session.flush()
        await _append_file_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_id=str(row.id),
            operation="upsert",
            revision=row.revision,
            payload=file_payload(row),
        )
        await session.commit()
        return FileInitResponse(
            id=row.id,
            upload_status="ready",
            object_key=row.object_key,
            upload_url=None,
            revision=row.revision,
            deduplicated=True,
        )

    object_key = storage.build_object_key(
        user_id=user_id,
        file_id=file_id,
        display_name=request.display_name,
    )
    if current is None:
        row = FileObject(
            id=file_id,
            user_id=user_id,
            folder=request.folder or "upload",
            display_name=request.display_name,
            mime_type=request.mime_type,
            size_bytes=request.size_bytes,
            sha256=sha,
            object_key=object_key,
            upload_status="pending",
            revision=1,
            payload_schema_version=1,
            updated_by_device=device_id,
            deleted_at=None,
        )
        session.add(row)
    else:
        if current.upload_status == "ready" and current.sha256 == sha and current.size_bytes == request.size_bytes:
            await session.commit()
            return FileInitResponse(
                id=current.id,
                upload_status="ready",
                object_key=current.object_key,
                upload_url=None,
                revision=current.revision,
                deduplicated=True,
            )
        current.folder = request.folder or current.folder
        current.display_name = request.display_name
        current.mime_type = request.mime_type
        current.size_bytes = request.size_bytes
        current.sha256 = sha
        current.object_key = object_key
        current.upload_status = "pending"
        current.revision += 1
        current.updated_at = _now()
        current.updated_by_device = device_id
        current.deleted_at = None
        row = current

    await session.flush()
    await _append_file_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_id=str(row.id),
        operation="upsert",
        revision=row.revision,
        payload=file_payload(row),
    )
    await session.commit()
    upload_url = storage.presign_put(row.object_key)
    return FileInitResponse(
        id=row.id,
        upload_status="pending",
        object_key=row.object_key,
        upload_url=upload_url,
        revision=row.revision,
        deduplicated=False,
    )


async def complete_upload(
    session: AsyncSession,
    storage: ObjectStorage,
    *,
    user_id: UUID,
    device_id: UUID,
    file_id: UUID,
    request: FileCompleteRequest,
) -> FileDto:
    if not storage.is_configured():
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)

    row = await get_file(session, user_id=user_id, file_id=file_id)
    if row is None or row.deleted_at is not None:
        raise AppError("not_found", "file not found", status_code=404)

    head = storage.head_object(row.object_key)
    if head is None:
        row.upload_status = "failed"
        row.updated_at = _now()
        row.updated_by_device = device_id
        row.revision += 1
        await session.flush()
        await _append_file_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_id=str(row.id),
            operation="upsert",
            revision=row.revision,
            payload=file_payload(row),
        )
        await session.commit()
        raise AppError("upload_incomplete", "object not found in storage", status_code=409)

    expected_size = request.size_bytes if request.size_bytes is not None else row.size_bytes
    if head.size_bytes != expected_size:
        row.upload_status = "failed"
        row.updated_at = _now()
        row.updated_by_device = device_id
        row.revision += 1
        await session.flush()
        await _append_file_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_id=str(row.id),
            operation="upsert",
            revision=row.revision,
            payload=file_payload(row),
        )
        await session.commit()
        raise AppError(
            "size_mismatch",
            f"object size {head.size_bytes} != expected {expected_size}",
            status_code=409,
        )

    if request.sha256:
        row.sha256 = request.sha256.lower()
    row.size_bytes = head.size_bytes
    row.upload_status = "ready"
    row.updated_at = _now()
    row.updated_by_device = device_id
    row.revision += 1
    row.deleted_at = None
    await session.flush()
    await _append_file_change(
        session,
        user_id=user_id,
        device_id=device_id,
        entity_id=str(row.id),
        operation="upsert",
        revision=row.revision,
        payload=file_payload(row),
    )
    await session.commit()
    return to_dto(row)


async def download_url(
    session: AsyncSession,
    storage: ObjectStorage,
    *,
    user_id: UUID,
    file_id: UUID,
    expires_seconds: int = 900,
) -> FileDownloadUrlResponse:
    if not storage.is_configured():
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)
    row = await get_file(session, user_id=user_id, file_id=file_id)
    if row is None or row.deleted_at is not None:
        raise AppError("not_found", "file not found", status_code=404)
    if row.upload_status != "ready":
        raise AppError("not_ready", "file is not ready for download", status_code=409)
    url = storage.presign_get(row.object_key, expires_seconds=expires_seconds)
    return FileDownloadUrlResponse(
        id=row.id,
        download_url=url,
        expires_in_seconds=expires_seconds,
        upload_status=row.upload_status,
    )


async def soft_delete_file(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    file_id: UUID,
) -> FileDto:
    row = await get_file(session, user_id=user_id, file_id=file_id)
    if row is None:
        raise AppError("not_found", "file not found", status_code=404)
    if row.deleted_at is None:
        row.deleted_at = _now()
        row.upload_status = "deleted"
        row.revision += 1
        row.updated_at = _now()
        row.updated_by_device = device_id
        await session.flush()
        await _append_file_change(
            session,
            user_id=user_id,
            device_id=device_id,
            entity_id=str(row.id),
            operation="delete",
            revision=row.revision,
            payload=file_payload(row),
        )
        await session.commit()
    return to_dto(row)
