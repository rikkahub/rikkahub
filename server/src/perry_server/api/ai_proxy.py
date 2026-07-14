from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from perry_server.auth.deps import AuthContext, require_device
from perry_server.errors import AppError
from perry_server.services.monel import MonelClient

router = APIRouter(prefix="/v1/ai", tags=["ai"])


def get_monel(request: Request) -> MonelClient:
    client = getattr(request.app.state, "monel_client", None)
    if client is None:
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
    return client  # type: ignore[no-any-return]


@router.api_route(
    "/{provider_id}/{path:path}",
    methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"],
)
async def ai_proxy(
    provider_id: str,
    path: str,
    request: Request,
    monel: Annotated[MonelClient, Depends(get_monel)],
    _auth: Annotated[AuthContext, Depends(require_device)],
) -> StreamingResponse:
    """Stream-proxy OpenAI-compatible traffic to Monel.

    Android uses device Bearer token only. Perry injects MONEL_AUTH_KEY server-side.
    Never proxies /admin/* or /setup*.
    """
    if not monel.is_configured():
        raise AppError("monel_not_configured", "Monel is not configured", status_code=503)

    lower_path = path.lower().lstrip("/")
    if lower_path.startswith("admin") or "/admin" in f"/{lower_path}" or lower_path.startswith("setup"):
        raise AppError("forbidden_path", "admin/setup paths are not proxied", status_code=403)

    body = await request.body()
    incoming = {k: v for k, v in request.headers.items()}
    status, headers, stream = await monel.stream_chat(
        provider_id=provider_id,
        path=path,
        method=request.method,
        query=request.url.query,
        headers=incoming,
        body=body,
    )
    media = headers.get("content-type") or headers.get("Content-Type")
    return StreamingResponse(
        stream,
        status_code=status,
        headers=headers,
        media_type=media,
    )
