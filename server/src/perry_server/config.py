from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    perry_env: Literal["development", "production", "test"] = "development"
    perry_host: str = "127.0.0.1"
    perry_port: int = 8787
    perry_database_url: str = "sqlite+aiosqlite:///./data/perry.db"
    perry_bootstrap_token: str = Field(..., min_length=16)
    perry_token_pepper: str = Field(..., min_length=16)
    perry_public_base_url: str = "http://127.0.0.1:8787"
    perry_api_version: str = "0.1.0"
    perry_min_client_version: str = "2.4.1"

    minio_endpoint: str | None = None
    minio_access_key: str | None = None
    minio_secret_key: str | None = None
    minio_bucket: str = "haruhomedev"
    minio_secure: bool = False

    monel_base_url: str | None = None
    monel_auth_key: str | None = None

    perry_workspace_enabled: bool = False
    perry_workspace_podman_binary: str = "podman"
    perry_workspace_data_root: str = "./data/workspaces"
    perry_workspace_default_image: str = "docker.io/library/python:3.12-slim-bookworm"
    perry_workspace_memory: str = "2g"
    perry_workspace_cpus: float = Field(default=2.0, gt=0)
    perry_workspace_pids_limit: int = Field(default=512, ge=32)
    perry_workspace_max_file_bytes: int = Field(default=8 * 1024 * 1024, ge=1024)
    perry_workspace_max_output_bytes: int = Field(default=128 * 1024, ge=4096)

    @property
    def is_sqlite(self) -> bool:
        return self.perry_database_url.startswith("sqlite")

    @property
    def workspace_data_root(self) -> Path:
        return Path(self.perry_workspace_data_root).expanduser().resolve()


@lru_cache
def get_settings() -> Settings:
    return Settings()
