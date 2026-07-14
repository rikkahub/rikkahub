"""phase7 files metadata for MinIO

Revision ID: 20260714_0009
Revises: 20260714_0008
Create Date: 2026-07-14

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260714_0009"
down_revision: str | Sequence[str] | None = "20260714_0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _table_exists(name: str) -> bool:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    return name in inspector.get_table_names()


def _index_names(table: str) -> set[str]:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if table not in inspector.get_table_names():
        return set()
    return {idx["name"] for idx in inspector.get_indexes(table) if idx.get("name")}


def upgrade() -> None:
    # Dev DBs may already have `files` (created out-of-band or partial apply).
    if not _table_exists("files"):
        op.create_table(
            "files",
            sa.Column("id", sa.Uuid(), nullable=False),
            sa.Column("user_id", sa.Uuid(), nullable=False),
            sa.Column("folder", sa.String(length=64), nullable=False),
            sa.Column("display_name", sa.String(length=512), nullable=False),
            sa.Column("mime_type", sa.String(length=255), nullable=False),
            sa.Column("size_bytes", sa.BigInteger(), nullable=False),
            sa.Column("sha256", sa.String(length=64), nullable=False),
            sa.Column("object_key", sa.String(length=1024), nullable=False),
            sa.Column("upload_status", sa.String(length=32), nullable=False),
            sa.Column("revision", sa.Integer(), nullable=False),
            sa.Column("payload_schema_version", sa.Integer(), nullable=False),
            sa.Column(
                "created_at",
                sa.DateTime(timezone=True),
                server_default=sa.text("(CURRENT_TIMESTAMP)"),
                nullable=False,
            ),
            sa.Column(
                "updated_at",
                sa.DateTime(timezone=True),
                server_default=sa.text("(CURRENT_TIMESTAMP)"),
                nullable=False,
            ),
            sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("updated_by_device", sa.Uuid(), nullable=True),
            sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
            sa.PrimaryKeyConstraint("id"),
        )

    existing = _index_names("files")
    if "ix_files_user_id" not in existing:
        op.create_index("ix_files_user_id", "files", ["user_id"])
    if "ix_files_user_sha256" not in existing:
        op.create_index("ix_files_user_sha256", "files", ["user_id", "sha256"])
    if "ix_files_user_status" not in existing:
        op.create_index("ix_files_user_status", "files", ["user_id", "upload_status"])


def downgrade() -> None:
    if not _table_exists("files"):
        return
    existing = _index_names("files")
    if "ix_files_user_status" in existing:
        op.drop_index("ix_files_user_status", table_name="files")
    if "ix_files_user_sha256" in existing:
        op.drop_index("ix_files_user_sha256", table_name="files")
    if "ix_files_user_id" in existing:
        op.drop_index("ix_files_user_id", table_name="files")
    op.drop_table("files")
