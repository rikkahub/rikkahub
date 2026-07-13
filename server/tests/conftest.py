import os
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

# Defaults so create_app can import; tests pass explicit Settings into create_app.
os.environ.setdefault("PERRY_BOOTSTRAP_TOKEN", "test-bootstrap-token-32chars-min")
os.environ.setdefault("PERRY_TOKEN_PEPPER", "test-token-pepper-32chars-minimum")
os.environ.setdefault("PERRY_ENV", "test")

from perry_server.config import Settings
from perry_server.main import create_app


@pytest.fixture
def tmp_db_path(tmp_path: Path) -> Path:
    return tmp_path / "perry-test.db"


@pytest.fixture
def settings(tmp_db_path: Path) -> Settings:
    return Settings(
        perry_env="test",
        perry_database_url=f"sqlite+aiosqlite:///{tmp_db_path.as_posix()}",
        perry_bootstrap_token="test-bootstrap-token-32chars-min",
        perry_token_pepper="test-token-pepper-32chars-minimum",
        perry_public_base_url="http://test",
    )


@pytest_asyncio.fixture
async def client(settings: Settings) -> AsyncIterator[AsyncClient]:
    app = create_app(settings)
    transport = ASGITransport(app=app)
    async with app.router.lifespan_context(app):
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac


@pytest.fixture
def bootstrap_headers() -> dict[str, str]:
    return {"X-Bootstrap-Token": "test-bootstrap-token-32chars-min"}
