from typing import Any

from pydantic import BaseModel, Field


class ErrorDetail(BaseModel):
    code: str
    message: str
    request_id: str
    details: dict[str, Any] | None = None


class ErrorResponse(BaseModel):
    error: ErrorDetail


class HealthLiveResponse(BaseModel):
    status: str = "ok"


class HealthReadyResponse(BaseModel):
    status: str
    database: str


class ComponentStatus(BaseModel):
    status: str
    detail: str | None = None


class ServerInfoResponse(BaseModel):
    api_version: str
    min_client_version: str
    server_time: str
    features: dict[str, bool] = Field(default_factory=dict)
    components: dict[str, ComponentStatus] = Field(default_factory=dict)
