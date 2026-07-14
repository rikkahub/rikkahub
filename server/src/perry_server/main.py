from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import cast
from uuid import uuid4

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from perry_server import __version__
from perry_server import models as _models  # noqa: F401
from perry_server.api import build_api_router
from perry_server.config import Settings, get_settings
from perry_server.db.base import Base
from perry_server.db.session import create_engine, create_session_factory
from perry_server.errors import (
    AppError,
    app_error_handler,
    http_error_handler,
    validation_error_handler,
)
from perry_server.logging_config import setup_logging
from perry_server.services.storage import create_object_storage


def _ensure_sqlite_parent(settings: Settings) -> None:
    if not settings.is_sqlite:
        return
    # sqlite+aiosqlite:///./data/perry.db or sqlite+aiosqlite:////abs/path.db
    raw = settings.perry_database_url.split("sqlite+aiosqlite:///", 1)[-1]
    if raw.startswith("/") and not raw.startswith("//"):
        path = Path(raw)
    else:
        path = Path(raw)
    if path.parent and str(path.parent) not in {"", "."}:
        path.parent.mkdir(parents=True, exist_ok=True)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings
    _ensure_sqlite_parent(settings)
    engine = create_engine(settings)
    session_factory = create_session_factory(engine)
    app.state.engine = engine
    app.state.session_factory = session_factory
    app.state.object_storage = create_object_storage(settings)

    # Phase 1 convenience: create tables if missing. Prefer Alembic in real deploys.
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    yield
    await engine.dispose()


def create_app(settings: Settings | None = None) -> FastAPI:
    setup_logging()
    resolved = settings or get_settings()
    app = FastAPI(
        title="Perry API",
        version=__version__,
        lifespan=lifespan,
    )
    app.state.settings = resolved
    exc_handler = Callable[[Request, Exception], Awaitable[Response]]
    app.add_exception_handler(AppError, cast(exc_handler, app_error_handler))
    app.add_exception_handler(
        StarletteHTTPException,
        cast(exc_handler, http_error_handler),
    )
    app.add_exception_handler(
        RequestValidationError,
        cast(exc_handler, validation_error_handler),
    )

    @app.middleware("http")
    async def request_id_middleware(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        request_id = request.headers.get("X-Request-Id") or str(uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        return response

    app.include_router(build_api_router())
    return app


def get_app() -> FastAPI:
    """Factory for uvicorn: `uvicorn perry_server.main:get_app --factory`."""
    return create_app()


# Default ASGI app for `uvicorn perry_server.main:app`.
# Fail loudly if settings/.env are missing — never export app=None (causes
# TypeError: 'NoneType' object is not callable under uvicorn).
app = create_app()
