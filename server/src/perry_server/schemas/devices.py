from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class DeviceRegisterRequest(BaseModel):
    name: str = Field(min_length=1, max_length=128)


class DeviceRegisterResponse(BaseModel):
    device_id: UUID
    user_id: UUID
    name: str
    device_token: str
    created_at: datetime


class DeviceResponse(BaseModel):
    id: UUID
    name: str
    created_at: datetime
    last_seen_at: datetime | None
    revoked_at: datetime | None
    is_current: bool = False
