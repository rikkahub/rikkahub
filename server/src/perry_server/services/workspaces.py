from __future__ import annotations

import json
import logging
from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.config import Settings
from perry_server.errors import AppError
from perry_server.models.workspace import Workspace
from perry_server.schemas.workspaces import (
    WorkspaceCreateRequest,
    WorkspaceDto,
    WorkspaceUpdateRequest,
)
from perry_server.services.workspace_runtime import PodmanWorkspaceRuntime

logger = logging.getLogger(__name__)


def container_name(workspace_id: UUID) -> str:
    return f"perry-ws-{workspace_id.hex}"


def to_dto(row: Workspace) -> WorkspaceDto:
    try:
        approvals = json.loads(row.tool_approvals_json)
        if not isinstance(approvals, dict):
            approvals = {}
    except json.JSONDecodeError:
        approvals = {}
    return WorkspaceDto(
        id=row.id,
        name=row.name,
        image=row.image,
        shell_status=row.shell_status,
        tool_approvals={str(key): bool(value) for key, value in approvals.items()},
        created_at_ms=int(row.created_at.timestamp() * 1000),
        updated_at_ms=int(row.updated_at.timestamp() * 1000),
        last_access_at_ms=(
            int(row.last_access_at.timestamp() * 1000) if row.last_access_at else None
        ),
    )


async def list_workspaces(session: AsyncSession, *, user_id: UUID) -> list[Workspace]:
    result = await session.execute(
        select(Workspace)
        .where(Workspace.user_id == user_id)
        .order_by(Workspace.updated_at.desc(), Workspace.id.desc())
    )
    return list(result.scalars().all())


async def get_workspace(
    session: AsyncSession, *, user_id: UUID, workspace_id: UUID
) -> Workspace | None:
    result = await session.execute(
        select(Workspace).where(Workspace.user_id == user_id, Workspace.id == workspace_id)
    )
    return result.scalar_one_or_none()


async def require_workspace(
    session: AsyncSession, *, user_id: UUID, workspace_id: UUID
) -> Workspace:
    row = await get_workspace(session, user_id=user_id, workspace_id=workspace_id)
    if row is None:
        raise AppError("not_found", "workspace not found", status_code=404)
    return row


async def create_workspace(
    session: AsyncSession,
    runtime: PodmanWorkspaceRuntime,
    settings: Settings,
    *,
    user_id: UUID,
    request: WorkspaceCreateRequest,
) -> Workspace:
    name = request.name.strip()
    duplicate = await session.execute(
        select(Workspace.id).where(Workspace.user_id == user_id, Workspace.name == name)
    )
    if duplicate.scalar_one_or_none() is not None:
        raise AppError("workspace_name_exists", "workspace name already exists", status_code=409)
    workspace_id = request.id or uuid4()
    existing = await get_workspace(session, user_id=user_id, workspace_id=workspace_id)
    if existing is not None:
        raise AppError("workspace_exists", "workspace id already exists", status_code=409)
    image = request.image or settings.perry_workspace_default_image
    row = Workspace(
        id=workspace_id,
        user_id=user_id,
        name=name,
        container_name=container_name(workspace_id),
        image=image,
        shell_status="INSTALLING",
        tool_approvals_json=json.dumps(request.tool_approvals, separators=(",", ":")),
    )
    session.add(row)
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise AppError(
            "workspace_exists",
            "workspace id or name already exists",
            status_code=409,
        ) from exc
    workspace_id = row.id
    workspace_container_name = row.container_name
    try:
        await runtime.ensure(row.id, row.container_name, row.image)
        row.shell_status = "READY"
        row.updated_at = datetime.now(UTC)
        await session.commit()
    except BaseException:
        await _cleanup_failed_workspace(
            session,
            runtime,
            workspace_id=workspace_id,
            workspace_container_name=workspace_container_name,
        )
        raise
    return row


async def _cleanup_failed_workspace(
    session: AsyncSession,
    runtime: PodmanWorkspaceRuntime,
    *,
    workspace_id: UUID,
    workspace_container_name: str,
) -> None:
    await session.rollback()
    try:
        await runtime.delete(workspace_id, workspace_container_name)
    except Exception:
        logger.exception("could not clean up failed workspace container %s", workspace_id)
    persisted = await session.get(Workspace, workspace_id)
    if persisted is not None:
        await session.delete(persisted)
        await session.commit()


async def update_workspace(
    session: AsyncSession,
    *,
    user_id: UUID,
    workspace_id: UUID,
    request: WorkspaceUpdateRequest,
) -> Workspace:
    row = await require_workspace(session, user_id=user_id, workspace_id=workspace_id)
    if request.name is not None:
        name = request.name.strip()
        duplicate = await session.execute(
            select(Workspace.id).where(
                Workspace.user_id == user_id,
                Workspace.name == name,
                Workspace.id != workspace_id,
            )
        )
        if duplicate.scalar_one_or_none() is not None:
            raise AppError(
                "workspace_name_exists", "workspace name already exists", status_code=409
            )
        row.name = name
    if request.tool_approvals is not None:
        row.tool_approvals_json = json.dumps(request.tool_approvals, separators=(",", ":"))
    row.updated_at = datetime.now(UTC)
    await session.commit()
    return row


async def touch_workspace(session: AsyncSession, row: Workspace) -> None:
    row.last_access_at = datetime.now(UTC)
    await session.commit()


async def delete_workspace(
    session: AsyncSession,
    runtime: PodmanWorkspaceRuntime,
    *,
    user_id: UUID,
    workspace_id: UUID,
) -> None:
    row = await require_workspace(session, user_id=user_id, workspace_id=workspace_id)
    await runtime.delete(row.id, row.container_name)
    await session.delete(row)
    await session.commit()
