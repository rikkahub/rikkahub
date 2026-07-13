"""phase5 conversation folders

Revision ID: 20260713_0005
Revises: 20260713_0004
Create Date: 2026-07-13

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260713_0005"
down_revision: str | Sequence[str] | None = "20260713_0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "conversation_folders",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("assistant_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=256), nullable=False),
        sa.Column("sort_index", sa.Integer(), nullable=False),
        sa.Column("create_at_ms", sa.BigInteger(), nullable=False),
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
    op.create_index("ix_conversation_folders_user_id", "conversation_folders", ["user_id"])
    op.create_index(
        "ix_conversation_folders_user_assistant",
        "conversation_folders",
        ["user_id", "assistant_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_conversation_folders_user_assistant", table_name="conversation_folders")
    op.drop_index("ix_conversation_folders_user_id", table_name="conversation_folders")
    op.drop_table("conversation_folders")
