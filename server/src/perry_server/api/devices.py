from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.deps import (
    AuthContext,
    get_app_settings,
    get_db,
    require_bootstrap_token,
    require_device,
)
from perry_server.config import Settings
from perry_server.errors import ensure_uuid
from perry_server.schemas.devices import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    DeviceResponse,
)
from perry_server.services import devices as device_service

router = APIRouter(prefix="/v1/devices", tags=["devices"])


@router.post(
    "/register",
    response_model=DeviceRegisterResponse,
    dependencies=[Depends(require_bootstrap_token)],
)
async def register_device(
    body: DeviceRegisterRequest,
    session: Annotated[AsyncSession, Depends(get_db)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> DeviceRegisterResponse:
    device, token = await device_service.register_device(
        session, name=body.name, pepper=settings.perry_token_pepper
    )
    return DeviceRegisterResponse(
        device_id=device.id,
        user_id=device.user_id,
        name=device.name,
        device_token=token,
        created_at=device.created_at,
    )


@router.get("", response_model=list[DeviceResponse])
async def list_devices(
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> list[DeviceResponse]:
    devices = await device_service.list_devices(session, user_id=auth.user_id)
    return [
        DeviceResponse(
            id=d.id,
            name=d.name,
            created_at=d.created_at,
            last_seen_at=d.last_seen_at,
            revoked_at=d.revoked_at,
            is_current=d.id == auth.device_id,
        )
        for d in devices
    ]


@router.delete("/{device_id}", response_model=DeviceResponse)
async def revoke_device(
    device_id: str,
    session: Annotated[AsyncSession, Depends(get_db)],
    auth: Annotated[AuthContext, Depends(require_device)],
) -> DeviceResponse:
    device_uuid: UUID = ensure_uuid(device_id, "device_id")
    device = await device_service.revoke_device(
        session, user_id=auth.user_id, device_id=device_uuid
    )
    return DeviceResponse(
        id=device.id,
        name=device.name,
        created_at=device.created_at,
        last_seen_at=device.last_seen_at,
        revoked_at=device.revoked_at,
        is_current=device.id == auth.device_id,
    )
