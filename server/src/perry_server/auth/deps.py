from collections.abc import AsyncGenerator
from dataclasses import dataclass
from typing import Annotated
from uuid import UUID

from fastapi import Depends, Header, Request
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from perry_server.config import Settings
from perry_server.errors import AppError
from perry_server.models.device import Device
from perry_server.services import devices as device_service


@dataclass(slots=True)
class AuthContext:
    device: Device
    user_id: UUID
    device_id: UUID


def get_app_settings(request: Request) -> Settings:
    settings = request.app.state.settings
    if not isinstance(settings, Settings):
        raise RuntimeError("application settings are not configured")
    return settings


async def get_db(request: Request) -> AsyncGenerator[AsyncSession, None]:
    session_factory = request.app.state.session_factory
    if not isinstance(session_factory, async_sessionmaker):
        raise RuntimeError("database session factory is not configured")
    async with session_factory() as session:
        yield session


async def require_bootstrap_token(
    settings: Annotated[Settings, Depends(get_app_settings)],
    x_bootstrap_token: Annotated[str | None, Header(alias="X-Bootstrap-Token")] = None,
) -> None:
    if not x_bootstrap_token or x_bootstrap_token != settings.perry_bootstrap_token:
        raise AppError("invalid_bootstrap_token", "invalid bootstrap token", status_code=401)


def _extract_bearer(authorization: str | None) -> str:
    if not authorization:
        raise AppError("unauthorized", "missing authorization", status_code=401)
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise AppError("unauthorized", "invalid authorization scheme", status_code=401)
    return token.strip()


async def require_device(
    request: Request,
    session: Annotated[AsyncSession, Depends(get_db)],
    settings: Annotated[Settings, Depends(get_app_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> AuthContext:
    token = _extract_bearer(authorization)
    device = await device_service.get_active_device_by_token(
        session, token=token, pepper=settings.perry_token_pepper
    )
    if device is None:
        raise AppError("unauthorized", "invalid or revoked device token", status_code=401)
    await device_service.touch_device(session, device)
    request.state.device_id = str(device.id)
    return AuthContext(device=device, user_id=device.user_id, device_id=device.id)
