from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.auth.deps import get_db
from perry_server.db.session import check_database
from perry_server.errors import AppError
from perry_server.schemas.common import HealthLiveResponse, HealthReadyResponse

router = APIRouter(tags=["health"])


@router.get("/health/live", response_model=HealthLiveResponse)
async def health_live() -> HealthLiveResponse:
    return HealthLiveResponse()


@router.get("/health/ready", response_model=HealthReadyResponse)
async def health_ready(
    request: Request,
    session: Annotated[AsyncSession, Depends(get_db)],
) -> HealthReadyResponse:
    try:
        await check_database(session)
    except Exception as exc:  # noqa: BLE001 - surface readiness failure
        raise AppError(
            "not_ready",
            "database unavailable",
            status_code=503,
            details={"reason": str(exc)},
        ) from exc
    return HealthReadyResponse(status="ok", database="ok")
