from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.tokens import generate_device_token, hash_token
from perry_server.errors import AppError
from perry_server.models.device import Device
from perry_server.services.users import get_or_create_primary_user


async def register_device(session: AsyncSession, *, name: str, pepper: str) -> tuple[Device, str]:
    user = await get_or_create_primary_user(session)
    token = generate_device_token()
    device = Device(
        user_id=user.id,
        name=name.strip(),
        token_hash=hash_token(token, pepper),
        last_seen_at=datetime.now(UTC),
    )
    session.add(device)
    await session.commit()
    await session.refresh(device)
    return device, token


async def list_devices(session: AsyncSession, *, user_id: UUID) -> list[Device]:
    result = await session.execute(
        select(Device).where(Device.user_id == user_id).order_by(Device.created_at.desc())
    )
    return list(result.scalars().all())


async def get_active_device_by_token(
    session: AsyncSession, *, token: str, pepper: str
) -> Device | None:
    token_hash = hash_token(token, pepper)
    result = await session.execute(select(Device).where(Device.token_hash == token_hash))
    device = result.scalar_one_or_none()
    if device is None or device.revoked_at is not None:
        return None
    return device


async def touch_device(session: AsyncSession, device: Device) -> None:
    device.last_seen_at = datetime.now(UTC)
    await session.commit()


async def revoke_device(
    session: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
) -> Device:
    result = await session.execute(
        select(Device).where(Device.id == device_id, Device.user_id == user_id)
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise AppError("device_not_found", "device not found", status_code=404)
    if device.revoked_at is None:
        device.revoked_at = datetime.now(UTC)
        await session.commit()
        await session.refresh(device)
    return device
