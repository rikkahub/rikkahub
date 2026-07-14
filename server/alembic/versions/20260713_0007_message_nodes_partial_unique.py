"""message nodes live unique index

Revision ID: 20260713_0007
Revises: 20260713_0006
Create Date: 2026-07-14

"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260713_0007"
down_revision: str | Sequence[str] | None = "20260713_0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # DBs that applied the original table-level unique constraint need this fix.
    # SQLite: recreate table without UNIQUE, then add partial unique index.
    op.execute("DROP INDEX IF EXISTS uq_message_nodes_live_conversation_index")
    op.execute(
        """
        CREATE TABLE message_nodes_new (
            id CHAR(32) NOT NULL,
            user_id CHAR(32) NOT NULL,
            conversation_id CHAR(32) NOT NULL,
            node_index INTEGER NOT NULL,
            messages_json TEXT NOT NULL,
            select_index INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            payload_schema_version INTEGER NOT NULL,
            created_at DATETIME DEFAULT (CURRENT_TIMESTAMP) NOT NULL,
            updated_at DATETIME DEFAULT (CURRENT_TIMESTAMP) NOT NULL,
            deleted_at DATETIME,
            updated_by_device CHAR(32),
            PRIMARY KEY (id),
            FOREIGN KEY(conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
            FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
        )
        """
    )
    op.execute(
        """
        INSERT INTO message_nodes_new (
            id, user_id, conversation_id, node_index, messages_json, select_index,
            revision, payload_schema_version, created_at, updated_at, deleted_at,
            updated_by_device
        )
        SELECT
            id, user_id, conversation_id, node_index, messages_json, select_index,
            revision, payload_schema_version, created_at, updated_at, deleted_at,
            updated_by_device
        FROM message_nodes
        """
    )
    op.execute("DROP TABLE message_nodes")
    op.execute("ALTER TABLE message_nodes_new RENAME TO message_nodes")
    op.execute("CREATE INDEX ix_message_nodes_user_id ON message_nodes (user_id)")
    op.execute(
        "CREATE INDEX ix_message_nodes_conversation_index "
        "ON message_nodes (conversation_id, node_index)"
    )
    op.execute(
        "CREATE INDEX ix_message_nodes_conversation_revision "
        "ON message_nodes (conversation_id, revision)"
    )
    op.execute(
        "CREATE UNIQUE INDEX uq_message_nodes_live_conversation_index "
        "ON message_nodes (conversation_id, node_index) "
        "WHERE deleted_at IS NULL"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS uq_message_nodes_live_conversation_index")
