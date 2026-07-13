from functools import lru_cache
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

    @property
    def is_sqlite(self) -> bool:
        return self.perry_database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    return Settings()
