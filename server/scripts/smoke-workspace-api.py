from __future__ import annotations

import asyncio
from uuid import UUID, uuid4

import httpx
from sqlalchemy import delete

from perry_server.config import Settings
from perry_server.db.session import create_engine, create_session_factory
from perry_server.models.device import Device


async def main() -> None:
    settings = Settings()
    device_id: str | None = None
    workspace_id = str(uuid4())
    token: str | None = None
    try:
        async with httpx.AsyncClient(
            base_url="http://127.0.0.1:8787", timeout=60, trust_env=False
        ) as client:
            registered = await client.post(
                "/v1/devices/register",
                json={"name": "workspace-api-smoke"},
                headers={"X-Bootstrap-Token": settings.perry_bootstrap_token},
            )
            registered.raise_for_status()
            device_id = registered.json()["device_id"]
            token = registered.json()["device_token"]
            headers = {"Authorization": f"Bearer {token}"}
            try:
                created = await client.post(
                    "/v1/workspaces",
                    json={"id": workspace_id, "name": f"Smoke {workspace_id[:8]}"},
                    headers=headers,
                )
                created.raise_for_status()
                executed = await client.post(
                    f"/v1/workspaces/{workspace_id}/execute",
                    json={
                        "command": "python --version",
                        "cwd": "/workspace",
                        "timeout_ms": 30_000,
                    },
                    headers=headers,
                )
                executed.raise_for_status()
                assert executed.json()["exit_code"] == 0, executed.text
                print("workspace API smoke test passed")
            finally:
                await client.delete(f"/v1/workspaces/{workspace_id}", headers=headers)
    finally:
        if device_id is not None:
            engine = create_engine(settings)
            session_factory = create_session_factory(engine)
            try:
                async with session_factory() as session:
                    await session.execute(delete(Device).where(Device.id == UUID(device_id)))
                    await session.commit()
            finally:
                await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
