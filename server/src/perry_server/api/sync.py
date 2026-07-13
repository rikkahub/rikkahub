from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.deps import AuthContext, get_db, require_device
from perry_server.errors import AppError
from perry_server.schemas.sync import (
    BootstrapResponse,
    ChangesResponse,
    MutationsRequest,
    MutationsResponse,
)
from perry_server.services import sync as sync_service

router = APIRouter(prefix="/v1/sync", tags=["sync"])


@router.get("/bootstrap", response_model=BootstrapResponse)
async def sync_bootstrap(
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> BootstrapResponse:
    return await sync_service.bootstrap(session, user_id=auth.user_id)


@router.get("/changes", response_model=ChangesResponse)
async def sync_changes(
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
    cursor: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=500)] = 100,
) -> ChangesResponse:
    return await sync_service.list_changes(
        session, user_id=auth.user_id, cursor=cursor, limit=limit
    )


@router.post("/mutations", response_model=MutationsResponse)
async def sync_mutations(
    body: MutationsRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> MutationsResponse:
    if body.device_id != auth.device_id:
        raise AppError(
            "device_mismatch",
            "device_id does not match authenticated device",
            status_code=403,
        )
    return await sync_service.apply_mutations(
        session,
        user_id=auth.user_id,
        device_id=auth.device_id,
        mutations=body.mutations,
    )
