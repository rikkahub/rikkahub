from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends

from perry_server.auth.deps import AuthContext, get_app_settings, require_device
from perry_server.config import Settings
from perry_server.schemas.common import ComponentStatus, ServerInfoResponse

router = APIRouter(prefix="/v1", tags=["server"])


@router.get("/server-info", response_model=ServerInfoResponse)
async def server_info(
    settings: Annotated[Settings, Depends(get_app_settings)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> ServerInfoResponse:
    components: dict[str, ComponentStatus] = {
        "database": ComponentStatus(status="ok"),
        "minio": _component_status(
            configured=bool(settings.minio_endpoint and settings.minio_access_key),
            label="minio",
        ),
        "monel": _component_status(
            configured=bool(settings.monel_base_url and settings.monel_auth_key),
            label="monel",
        ),
    }
    return ServerInfoResponse(
        api_version=settings.perry_api_version,
        min_client_version=settings.perry_min_client_version,
        server_time=datetime.now(UTC).isoformat(),
        features={
            "sync": True,
            "files": False,
            "monel_facade": False,
        },
        components=components,
    )


def _component_status(*, configured: bool, label: str) -> ComponentStatus:
    if not configured:
        return ComponentStatus(status="not_configured", detail=f"{label} not configured")
    # Phase 1 does not probe remote components yet; mark configured as degraded until Phase 7/8.
    return ComponentStatus(status="degraded", detail=f"{label} configured but not probed")
