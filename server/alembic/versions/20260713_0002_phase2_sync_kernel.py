"""phase2 sync kernel tables

Revision ID: 20260713_0002
Revises: 20260713_0001
Create Date: 2026-07-13

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260713_0002"
down_revision: str | Sequence[str] | None = "20260713_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "settings",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("key", sa.String(length=256), nullable=False),
        sa.Column("value_json", sa.Text(), nullable=False),
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
        sa.UniqueConstraint("user_id", "key", name="uq_settings_user_key"),
    )
    op.create_table(
        "change_log",
        sa.Column("seq", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("entity_type", sa.String(length=64), nullable=False),
        sa.Column("entity_id", sa.String(length=64), nullable=False),
        sa.Column("operation", sa.String(length=16), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column(
            "changed_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column("changed_by_device", sa.Uuid(), nullable=True),
        sa.Column("payload_json", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("seq"),
    )
    op.create_index("ix_change_log_entity_type", "change_log", ["entity_type"])
    op.create_index("ix_change_log_entity_id", "change_log", ["entity_id"])
    op.create_index("ix_change_log_user_seq", "change_log", ["user_id", "seq"])

    op.create_table(
        "mutation_receipts",
        sa.Column("mutation_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("device_id", sa.Uuid(), nullable=True),
        sa.Column("entity_type", sa.String(length=64), nullable=False),
        sa.Column("entity_id", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=True),
        sa.Column("response_json", sa.Text(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("mutation_id"),
    )


def downgrade() -> None:
    op.drop_table("mutation_receipts")
    op.drop_index("ix_change_log_user_seq", table_name="change_log")
    op.drop_index("ix_change_log_entity_id", table_name="change_log")
    op.drop_index("ix_change_log_entity_type", table_name="change_log")
    op.drop_table("change_log")
    op.drop_table("settings")
