from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path
from uuid import uuid4

from perry_server.config import Settings
from perry_server.services.workspace_runtime import PodmanWorkspaceRuntime


async def main() -> None:
    data_root = Path(tempfile.mkdtemp(prefix="perry-workspace-smoke-"))
    settings = Settings().model_copy(
        update={
            "perry_workspace_enabled": True,
            "perry_workspace_data_root": str(data_root),
            "perry_workspace_memory": "256m",
            "perry_workspace_cpus": 0.5,
            "perry_workspace_pids_limit": 64,
        }
    )
    runtime = PodmanWorkspaceRuntime(settings)
    workspace_id = uuid4()
    container_name = f"perry-smoke-{workspace_id.hex[:12]}"
    image = settings.perry_workspace_default_image
    try:
        await runtime.ensure(workspace_id, container_name, image)
        await runtime.write_file(
            workspace_id,
            container_name,
            image,
            "/workspace/hello.txt",
            b"cloud workspace ok\n",
            overwrite=True,
        )
        command = (
            'python -c "from pathlib import Path; '
            "print(Path('/workspace/hello.txt').read_text().strip())\""
        )
        result = await runtime.execute(
            workspace_id,
            container_name,
            image,
            command,
            "/workspace",
            30_000,
            None,
        )
        assert result.exit_code == 0, result.stderr
        assert result.stdout.strip() == "cloud workspace ok", result.stdout
        assert await runtime.read_file(
            workspace_id, container_name, image, "/workspace/hello.txt"
        ) == b"cloud workspace ok\n"
        print("workspace runtime smoke test passed")
    finally:
        await runtime.delete(workspace_id, container_name)
        data_root.rmdir()


if __name__ == "__main__":
    asyncio.run(main())
