from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request

from perry_server.auth.deps import AuthContext, require_device
from perry_server.errors import AppError
from perry_server.services.monel import MonelClient

router = APIRouter(prefix="/v1/catalog", tags=["catalog"])


def get_monel(request: Request) -> MonelClient:
    client = getattr(request.app.state, "monel_client", None)
    if client is None:
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
    return client  # type: ignore[no-any-return]


@router.get("/providers")
async def catalog_providers(
    monel: Annotated[MonelClient, Depends(get_monel)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> list[dict[str, Any]]:
    if not monel.is_configured():
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
    return await monel.list_providers()


@router.get("/models")
async def catalog_models(
    monel: Annotated[MonelClient, Depends(get_monel)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> list[dict[str, Any]]:
    if not monel.is_configured():
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
    return await monel.list_models()


@router.get("/models/by-provider")
async def catalog_models_by_provider(
    monel: Annotated[MonelClient, Depends(get_monel)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> list[dict[str, Any]]:
    if not monel.is_configured():
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
    return await monel.list_models_by_provider()


@router.get("/models/by-provider/{provider_id}")
async def catalog_models_for_provider(
    provider_id: str,
    monel: Annotated[MonelClient, Depends(get_monel)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> dict[str, Any]:
    if not monel.is_configured():
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
    return await monel.list_models_for_provider(provider_id)
