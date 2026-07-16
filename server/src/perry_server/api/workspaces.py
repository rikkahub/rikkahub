from __future__ import annotations

import base64
import binascii
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.deps import AuthContext, get_app_settings, get_db, require_device
from perry_server.config import Settings
from perry_server.errors import AppError
from perry_server.schemas.workspaces import (
    WorkspaceCommandResultDto,
    WorkspaceCreateRequest,
    WorkspaceDto,
    WorkspaceExecuteRequest,
    WorkspaceFileEntryDto,
    WorkspaceFileListResponse,
    WorkspaceListResponse,
    WorkspaceMoveRequest,
    WorkspaceStatusResponse,
    WorkspaceUpdateRequest,
)
from perry_server.services import workspaces as workspace_service
from perry_server.services.workspace_runtime import PodmanWorkspaceRuntime, RuntimeFileEntry

router = APIRouter(prefix="/v1/workspaces", tags=["workspaces"])


def get_runtime(request: Request) -> PodmanWorkspaceRuntime:
    runtime = getattr(request.app.state, "workspace_runtime", None)
    if not isinstance(runtime, PodmanWorkspaceRuntime):
        raise AppError("workspace_unavailable", "workspace runtime is unavailable", status_code=503)
    return runtime


def file_dto(entry: RuntimeFileEntry) -> WorkspaceFileEntryDto:
    return WorkspaceFileEntryDto(
        path=entry.path,
        name=entry.name,
        is_directory=entry.is_directory,
        size_bytes=entry.size_bytes,
        updated_at_ms=entry.updated_at_ms,
    )


@router.get("", response_model=WorkspaceListResponse)
async def list_workspaces(
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> WorkspaceListResponse:
    rows = await workspace_service.list_workspaces(session, user_id=auth.user_id)
    return WorkspaceListResponse(items=[workspace_service.to_dto(row) for row in rows])


@router.post("", response_model=WorkspaceDto, status_code=201)
async def create_workspace(
    body: WorkspaceCreateRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    settings: Annotated[Settings, Depends(get_app_settings)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
) -> WorkspaceDto:
    row = await workspace_service.create_workspace(
        session, runtime, settings, user_id=auth.user_id, request=body
    )
    return workspace_service.to_dto(row)


@router.get("/{workspace_id}", response_model=WorkspaceDto)
async def get_workspace(
    workspace_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> WorkspaceDto:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    return workspace_service.to_dto(row)


@router.patch("/{workspace_id}", response_model=WorkspaceDto)
async def update_workspace(
    workspace_id: UUID,
    body: WorkspaceUpdateRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> WorkspaceDto:
    row = await workspace_service.update_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id, request=body
    )
    return workspace_service.to_dto(row)


@router.delete("/{workspace_id}", status_code=204)
async def delete_workspace(
    workspace_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
) -> Response:
    await workspace_service.delete_workspace(
        session, runtime, user_id=auth.user_id, workspace_id=workspace_id
    )
    return Response(status_code=204)


@router.post("/{workspace_id}/execute", response_model=WorkspaceCommandResultDto)
async def execute_command(
    workspace_id: UUID,
    body: WorkspaceExecuteRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
) -> WorkspaceCommandResultDto:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    stdin: bytes | None = None
    if body.stdin_base64 is not None:
        try:
            stdin = base64.b64decode(body.stdin_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise AppError("invalid_stdin", "stdin_base64 is invalid", status_code=400) from exc
    result = await runtime.execute(
        row.id,
        row.container_name,
        row.image,
        body.command,
        body.cwd,
        body.timeout_ms,
        stdin,
    )
    await workspace_service.touch_workspace(session, row)
    return WorkspaceCommandResultDto(
        exit_code=result.exit_code,
        stdout=result.stdout,
        stderr=result.stderr,
        timed_out=result.timed_out,
        truncated=result.truncated,
    )


@router.get("/{workspace_id}/files", response_model=WorkspaceFileListResponse)
async def list_files(
    workspace_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
    path: Annotated[str, Query()] = "/workspace",
) -> WorkspaceFileListResponse:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    entries = await runtime.list_files(row.id, row.container_name, row.image, path)
    return WorkspaceFileListResponse(items=[file_dto(entry) for entry in entries])


@router.get("/{workspace_id}/files/stat", response_model=WorkspaceFileEntryDto)
async def stat_file(
    workspace_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
    path: Annotated[str, Query()],
) -> WorkspaceFileEntryDto:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    return file_dto(await runtime.stat_file(row.id, row.container_name, row.image, path))


@router.get("/{workspace_id}/files/content")
async def read_file(
    workspace_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
    path: Annotated[str, Query()],
) -> Response:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    data = await runtime.read_file(row.id, row.container_name, row.image, path)
    return Response(content=data, media_type="application/octet-stream")


@router.put("/{workspace_id}/files/content", response_model=WorkspaceFileEntryDto)
async def write_file(
    workspace_id: UUID,
    request: Request,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
    path: Annotated[str, Query()],
    overwrite: Annotated[bool, Query()] = True,
) -> WorkspaceFileEntryDto:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    entry = await runtime.write_file(
        row.id, row.container_name, row.image, path, await request.body(), overwrite
    )
    return file_dto(entry)


@router.post("/{workspace_id}/files/move", response_model=WorkspaceFileEntryDto)
async def move_file(
    workspace_id: UUID,
    body: WorkspaceMoveRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
) -> WorkspaceFileEntryDto:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    entry = await runtime.move_file(
        row.id, row.container_name, row.image, body.source, body.target, body.overwrite
    )
    return file_dto(entry)


@router.delete("/{workspace_id}/files", response_model=WorkspaceStatusResponse)
async def delete_file(
    workspace_id: UUID,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    runtime: Annotated[PodmanWorkspaceRuntime, Depends(get_runtime)],
    path: Annotated[str, Query()],
    recursive: Annotated[bool, Query()] = False,
) -> WorkspaceStatusResponse:
    row = await workspace_service.require_workspace(
        session, user_id=auth.user_id, workspace_id=workspace_id
    )
    deleted = await runtime.delete_file(
        row.id, row.container_name, row.image, path, recursive
    )
    return WorkspaceStatusResponse(status="deleted" if deleted else "not_found")
