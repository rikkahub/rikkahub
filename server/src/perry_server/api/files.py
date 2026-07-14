from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.deps import AuthContext, get_db, require_device
from perry_server.errors import AppError
from perry_server.schemas.files import (
    FileCompleteRequest,
    FileDownloadUrlResponse,
    FileDto,
    FileInitRequest,
    FileInitResponse,
)
from perry_server.services import files as files_service
from perry_server.services.storage import InMemoryObjectStorage, ObjectStorage

router = APIRouter(prefix="/v1/files", tags=["files"])


def get_storage(request: Request) -> ObjectStorage:
    storage = getattr(request.app.state, "object_storage", None)
    if storage is None:
        raise AppError("minio_not_configured", "object storage is not configured", status_code=503)
    return storage  # type: ignore[no-any-return]


@router.post("/init", response_model=FileInitResponse)
async def init_file(
    body: FileInitRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    storage: Annotated[ObjectStorage, Depends(get_storage)],
) -> FileInitResponse:
    return await files_service.init_upload(
        session,
        storage,
        user_id=auth.user_id,
        device_id=auth.device_id,
        request=body,
    )


@router.post("/{file_id}/complete", response_model=FileDto)
async def complete_file(
    file_id: UUID,
    body: FileCompleteRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    storage: Annotated[ObjectStorage, Depends(get_storage)],
) -> FileDto:
    return await files_service.complete_upload(
        session,
        storage,
        user_id=auth.user_id,
        device_id=auth.device_id,
        file_id=file_id,
        request=body,
    )


@router.get("/{file_id}", response_model=FileDto)
async def get_file(
    file_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> FileDto:
    row = await files_service.get_file(session, user_id=auth.user_id, file_id=file_id)
    if row is None or row.deleted_at is not None:
        raise AppError("not_found", "file not found", status_code=404)
    return files_service.to_dto(row)


@router.get("/{file_id}/download-url", response_model=FileDownloadUrlResponse)
async def get_download_url(
    file_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    storage: Annotated[ObjectStorage, Depends(get_storage)],
) -> FileDownloadUrlResponse:
    return await files_service.download_url(
        session,
        storage,
        user_id=auth.user_id,
        file_id=file_id,
    )


@router.delete("/{file_id}", response_model=FileDto)
async def delete_file(
    file_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> FileDto:
    return await files_service.soft_delete_file(
        session,
        user_id=auth.user_id,
        device_id=auth.device_id,
        file_id=file_id,
    )


@router.put("/{file_id}/content")
async def put_file_content(
    file_id: UUID,
    request: Request,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    storage: Annotated[ObjectStorage, Depends(get_storage)],
) -> dict[str, str]:
    """Compatibility / test upload path when clients cannot reach MinIO directly.

    For in-memory storage this is the real data plane. For MinIO it still works
    as a proxy put, then clients call /complete.
    """
    row = await files_service.get_file(session, user_id=auth.user_id, file_id=file_id)
    if row is None or row.deleted_at is not None:
        raise AppError("not_found", "file not found", status_code=404)
    data = await request.body()
    if not data:
        raise AppError("empty_body", "file body is empty", status_code=400)
    storage.put_object(row.object_key, data, content_type=row.mime_type)
    return {"status": "uploaded", "bytes": str(len(data))}


@router.get("/{file_id}/content")
async def get_file_content(
    file_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    storage: Annotated[ObjectStorage, Depends(get_storage)],
) -> Response:
    row = await files_service.get_file(session, user_id=auth.user_id, file_id=file_id)
    if row is None or row.deleted_at is not None:
        raise AppError("not_found", "file not found", status_code=404)
    if row.upload_status != "ready":
        raise AppError("not_ready", "file is not ready", status_code=409)
    if isinstance(storage, InMemoryObjectStorage):
        data = storage.get_object_bytes(row.object_key)
        if data is None:
            raise AppError("not_found", "object missing", status_code=404)
        return Response(content=data, media_type=row.mime_type or "application/octet-stream")
    # For MinIO prefer presigned GET; proxy not implemented for large objects in v1.
    raise AppError(
        "use_download_url",
        "use GET /v1/files/{id}/download-url for MinIO objects",
        status_code=400,
    )
