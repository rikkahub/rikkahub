from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request

from perry_server.auth.deps import AuthContext, get_app_settings, require_device
from perry_server.config import Settings
from perry_server.schemas.common import ComponentStatus, ServerInfoResponse
from perry_server.services.monel import MonelClient
from perry_server.services.storage import ObjectStorage

router = APIRouter(prefix="/v1", tags=["server"])


@router.get("/server-info", response_model=ServerInfoResponse)
async def server_info(
    request: Request,
    settings: Annotated[Settings, Depends(get_app_settings)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> ServerInfoResponse:
    storage = getattr(request.app.state, "object_storage", None)
    monel = getattr(request.app.state, "monel_client", None)
    minio_status = _probe_storage(storage)
    monel_status = await _probe_monel(monel)
    files_enabled = minio_status.status == "ok"
    monel_enabled = monel_status.status == "ok"
    components: dict[str, ComponentStatus] = {
        "database": ComponentStatus(status="ok"),
        "minio": minio_status,
        "monel": monel_status,
    }
    return ServerInfoResponse(
        api_version=settings.perry_api_version,
        min_client_version=settings.perry_min_client_version,
        server_time=datetime.now(UTC).isoformat(),
        features={
            "sync": True,
            "files": files_enabled,
            "monel_facade": monel_enabled,
        },
        components=components,
    )


def _probe_storage(storage: ObjectStorage | None) -> ComponentStatus:
    if storage is None:
        return ComponentStatus(status="not_configured", detail="minio not configured")
    status, detail = storage.probe()
    return ComponentStatus(status=status, detail=detail)


async def _probe_monel(monel: MonelClient | None) -> ComponentStatus:
    if monel is None:
        return ComponentStatus(status="not_configured", detail="monel not configured")
    status, detail = await monel.probe()
    return ComponentStatus(status=status, detail=detail)
