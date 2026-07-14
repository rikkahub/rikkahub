from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from perry_server.db.base import Base


class Favorite(Base):
    __tablename__ = "favorites"
    __table_args__ = (
        UniqueConstraint("user_id", "ref_key", name="uq_favorites_user_ref_key"),
    )

    # Client-assigned string id (often equals ref_key for node favorites).
    id: Mapped[str] = mapped_column(String(256), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    type: Mapped[str] = mapped_column(String(32), nullable=False, default="node")
    ref_key: Mapped[str] = mapped_column(String(512), nullable=False, default="")
    ref_json: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    snapshot_json: Mapped[str] = mapped_column(Text, nullable=False, default="")
    meta_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    updated_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    payload_schema_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    updated_by_device: Mapped[UUID | None] = mapped_column(nullable=True)
