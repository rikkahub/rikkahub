package me.rerere.rikkahub.data.db.fts

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * Regression test for issue #113: FTS reindexing deleted + reinserted every row for a conversation
 * via per-row [SupportSQLiteDatabase.execSQL] OUTSIDE any explicit transaction, so each statement
 * auto-committed on its own. The invariant being restored: the DELETE and all re-INSERTs commit
 * atomically (a single transaction), so an interrupt can never leave a conversation half-indexed.
 *
 * The real FTS table needs the native sqlite-android / requery JNI plus the `jieba_query` /
 * `simple_snippet` extensions, which are unavailable in this Robolectric-free JVM unit source set
 * (see [me.rerere.rikkahub.data.db.migrations.Migration_21_22Test]). The testable unit is therefore
 * the extracted pure helper [reindexConversationFts], driven against a recording fake
 * [SupportSQLiteDatabase] that logs the transaction lifecycle and the SQL executed — no native DB.
 *
 * On the UNFIXED code this class fails: the old `indexConversation` body emitted the DELETE and the
 * INSERTs with NO begin/setSuccessful/end events, so the ordering and "setSuccessful exactly once"
 * assertions fail (and `reindexConversationFts` did not exist, so it also would not compile).
 */
class MessageFtsTransactionTest {

    private fun textConversation(): Conversation {
        // Two nodes, each with a non-blank text message => exactly two FTS INSERTs expected.
        val n1 = MessageNode.of(UIMessage.user("hello world"))
        val n2 = MessageNode.of(UIMessage.assistant("goodbye world"))
        return Conversation.ofId(id = Uuid.random(), messages = listOf(n1, n2))
    }

    @Test
    fun `reindex wraps delete and inserts in a single committed transaction in order`() {
        val db = RecordingDb()
        reindexConversationFts(db, textConversation())

        val log = db.log
        val begin = log.indexOf(Event.Begin)
        val delete = log.indexOfFirst { it is Event.Sql && it.sql.startsWith("DELETE") }
        val firstInsert = log.indexOfFirst { it is Event.Sql && it.sql.startsWith("INSERT") }
        val success = log.indexOf(Event.SetSuccessful)
        val end = log.indexOf(Event.End)

        assertTrue("begin must be recorded", begin >= 0)
        assertTrue("delete must be recorded", delete >= 0)
        assertTrue("at least one insert must be recorded", firstInsert >= 0)
        assertTrue("setTransactionSuccessful must be recorded", success >= 0)
        assertTrue("endTransaction must be recorded", end >= 0)

        // begin < delete < first insert < setSuccessful < end
        assertTrue("transaction must begin before the DELETE", begin < delete)
        assertTrue("DELETE must run before any INSERT", delete < firstInsert)
        assertTrue("all writes must run before commit", firstInsert < success)
        assertTrue("commit must be the last thing before end", success == log.indexOf(Event.End) - 1)

        // every INSERT happens inside the transaction window (begin..setSuccessful)
        log.forEachIndexed { i, e ->
            if (e is Event.Sql && e.sql.startsWith("INSERT")) {
                assertTrue("INSERT outside transaction window", i in (begin + 1) until success)
            }
        }
    }

    @Test
    fun `setTransactionSuccessful is called exactly once on the happy path`() {
        val db = RecordingDb()
        reindexConversationFts(db, textConversation())
        assertEquals(1, db.log.count { it == Event.SetSuccessful })
        assertEquals(1, db.log.count { it == Event.Begin })
        assertEquals(1, db.log.count { it == Event.End })
    }

    @Test
    fun `failure during insert still ends the transaction and never marks it successful`() {
        val db = RecordingDb(failOnInsertNumber = 1)
        assertThrows(RuntimeException::class.java) {
            reindexConversationFts(db, textConversation())
        }
        // endTransaction runs in finally -> no leaked transaction.
        assertTrue("endTransaction must run on the failure path", db.log.contains(Event.End))
        // The transaction must NOT be committed -> rolls back, no half-written FTS state.
        assertTrue(
            "setTransactionSuccessful must not run when an INSERT fails",
            !db.log.contains(Event.SetSuccessful)
        )
    }

    @Test
    fun `insert count matches non-blank messages and the delete targets the conversation id`() {
        val id = Uuid.random()
        val nodes = listOf(
            MessageNode.of(UIMessage.user("keep me")),
            MessageNode.of(UIMessage.assistant("")),        // blank text -> skipped
            MessageNode.of(UIMessage.assistant("keep me too")),
        )
        val db = RecordingDb()
        reindexConversationFts(db, Conversation.ofId(id = id, messages = nodes))

        val inserts = db.log.filterIsInstance<Event.Sql>().filter { it.sql.startsWith("INSERT") }
        assertEquals("blank-text messages must still be skipped", 2, inserts.size)

        val delete = db.log.filterIsInstance<Event.Sql>().single { it.sql.startsWith("DELETE") }
        assertEquals(
            "DELETE FROM message_fts WHERE conversation_id = ?",
            delete.sql
        )
        assertEquals(
            "DELETE must bind the conversation id",
            id.toString(),
            delete.args?.firstOrNull()
        )
    }

    private sealed interface Event {
        data object Begin : Event
        data object SetSuccessful : Event
        data object End : Event
        data class Sql(val sql: String, val args: Array<out Any?>?) : Event {
            override fun equals(other: Any?) = other is Sql && other.sql == sql
            override fun hashCode() = sql.hashCode()
        }
    }

    /**
     * Records the transaction lifecycle + executed SQL. Only the members [reindexConversationFts]
     * touches are implemented; every other [SupportSQLiteDatabase] member throws so an accidental
     * dependency on real DB behavior fails loudly instead of silently no-opping.
     */
    private class RecordingDb(private val failOnInsertNumber: Int = -1) : SupportSQLiteDatabase {
        val log = mutableListOf<Event>()
        private var insertCount = 0

        override fun beginTransaction() {
            log.add(Event.Begin)
        }

        override fun setTransactionSuccessful() {
            log.add(Event.SetSuccessful)
        }

        override fun endTransaction() {
            log.add(Event.End)
        }

        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            if (sql.startsWith("INSERT")) {
                insertCount++
                if (insertCount == failOnInsertNumber) {
                    throw RuntimeException("simulated INSERT failure")
                }
            }
            log.add(Event.Sql(sql, bindArgs))
        }

        // --- untouched members (Kotlin maps the bean accessors to properties) ---
        override var version: Int
            get() = notUsed()
            set(_) = notUsed()
        override val maximumSize: Long get() = notUsed()
        override var pageSize: Long
            get() = notUsed()
            set(_) = notUsed()
        override val isReadOnly: Boolean get() = notUsed()
        override val isOpen: Boolean get() = notUsed()
        override val path: String? get() = notUsed()
        override val isWriteAheadLoggingEnabled: Boolean get() = notUsed()
        override val attachedDbs: List<android.util.Pair<String, String>>? get() = notUsed()
        override val isDatabaseIntegrityOk: Boolean get() = notUsed()
        override val isDbLockedByCurrentThread: Boolean get() = notUsed()

        override fun execSQL(sql: String): Unit = notUsed()
        override fun compileStatement(sql: String): SupportSQLiteStatement = notUsed()
        override fun beginTransactionNonExclusive(): Unit = notUsed()
        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener): Unit = notUsed()
        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener): Unit = notUsed()
        override fun inTransaction(): Boolean = notUsed()
        override fun yieldIfContendedSafely(): Boolean = notUsed()
        override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = notUsed()
        override fun setMaximumSize(numBytes: Long): Long = notUsed()
        override fun query(query: String): Cursor = notUsed()
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = notUsed()
        override fun query(query: SupportSQLiteQuery): Cursor = notUsed()
        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor = notUsed()
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = notUsed()
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = notUsed()
        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = notUsed()
        override fun needUpgrade(newVersion: Int): Boolean = notUsed()
        override fun setLocale(locale: Locale): Unit = notUsed()
        override fun setMaxSqlCacheSize(cacheSize: Int): Unit = notUsed()
        override fun setForeignKeyConstraintsEnabled(enabled: Boolean): Unit = notUsed()
        override fun enableWriteAheadLogging(): Boolean = notUsed()
        override fun disableWriteAheadLogging(): Unit = notUsed()
        override fun close(): Unit = notUsed()

        private fun notUsed(): Nothing =
            throw NotImplementedError("RecordingDb only implements the FTS-reindex transaction path")
    }
}
