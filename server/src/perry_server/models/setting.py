from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from perry_server.db.base import Base


class Setting(Base):
    __tablename__ = "settings"
    __table_args__ = (UniqueConstraint("user_id", "key", name="uq_settings_user_key"),)

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    key: Mapped[str] = mapped_column(String(256), nullable=False)
    value_json: Mapped[str] = mapped_column(Text, nullable=False, default="null")
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
