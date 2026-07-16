from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, ForeignKey, String, Text, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from perry_server.db.base import Base


class Workspace(Base):
    __tablename__ = "workspaces"
    __table_args__ = (UniqueConstraint("user_id", "name", name="uq_workspaces_user_name"),)

    id: Mapped[UUID] = mapped_column(primary_key=True)
    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    container_name: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    image: Mapped[str] = mapped_column(String(256), nullable=False)
    shell_status: Mapped[str] = mapped_column(String(32), nullable=False, default="INSTALLING")
    tool_approvals_json: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
    last_access_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
