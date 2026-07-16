"""cloud workspaces

Revision ID: 20260715_0010
Revises: 20260714_0009
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260715_0010"
down_revision: str | None = "20260714_0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "workspaces",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=128), nullable=False),
        sa.Column("container_name", sa.String(length=128), nullable=False),
        sa.Column("image", sa.String(length=256), nullable=False),
        sa.Column("shell_status", sa.String(length=32), nullable=False),
        sa.Column("tool_approvals_json", sa.Text(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("last_access_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("container_name"),
        sa.UniqueConstraint("user_id", "name", name="uq_workspaces_user_name"),
    )
    op.create_index("ix_workspaces_user_id", "workspaces", ["user_id"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_workspaces_user_id", table_name="workspaces")
    op.drop_table("workspaces")
