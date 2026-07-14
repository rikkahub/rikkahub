"""phase6 message nodes

Revision ID: 20260713_0006
Revises: 20260713_0005
Create Date: 2026-07-13

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260713_0006"
down_revision: str | Sequence[str] | None = "20260713_0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "message_nodes",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("conversation_id", sa.Uuid(), nullable=False),
        sa.Column("node_index", sa.Integer(), nullable=False),
        sa.Column("messages_json", sa.Text(), nullable=False),
        sa.Column("select_index", sa.Integer(), nullable=False),
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
        sa.ForeignKeyConstraint(["conversation_id"], ["conversations.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_message_nodes_user_id", "message_nodes", ["user_id"])
    op.create_index(
        "ix_message_nodes_conversation_index",
        "message_nodes",
        ["conversation_id", "node_index"],
    )
    op.create_index(
        "ix_message_nodes_conversation_revision",
        "message_nodes",
        ["conversation_id", "revision"],
    )
    # Soft-deleted rows may keep the same node_index; uniqueness only for live rows.
    op.execute(
        "CREATE UNIQUE INDEX uq_message_nodes_live_conversation_index "
        "ON message_nodes (conversation_id, node_index) "
        "WHERE deleted_at IS NULL"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS uq_message_nodes_live_conversation_index")
    op.drop_index("ix_message_nodes_conversation_revision", table_name="message_nodes")
    op.drop_index("ix_message_nodes_conversation_index", table_name="message_nodes")
    op.drop_index("ix_message_nodes_user_id", table_name="message_nodes")
    op.drop_table("message_nodes")
