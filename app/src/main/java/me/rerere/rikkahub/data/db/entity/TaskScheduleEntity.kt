package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted task schedule (SPEC.md M2): the mutable operational state that decides WHEN to spawn
 * a sub-task against [targetAssistantId] — once ([kind] == ONE_SHOT) or on a repeating cadence
 * ([kind] == RECURRING). Schedules live in Room (not on `Assistant`/DataStore) because they carry
 * per-row firing/running/retention/concurrency lifecycle, unlike the immutable declarative settings
 * a serialized `Assistant` blob holds.
 *
 * Like `task_runs`/`work_items`, this entity declares NO `foreignKeys` block: a conversation delete
 * does not cascade through Room; the schedule rows are cleaned explicitly inside
 * `deleteConversation`'s transaction (SPEC.md M6). This row is a dumb carrier — every legality and
 * atomic-claim invariant (target spawnable, caps, minimum interval, single-claim, advance/disable)
 * is enforced in the repository layer, the one path UI and tools share, never here.
 *
 * The enum-valued columns ([owner], [kind], [misfirePolicy]) persist the corresponding
 * `me.rerere.ai.runtime.contract` enum's `name`; renaming an entry is a data-format break and is
 * forbidden without a migration. [recurrenceSpec] is the already-serialized JSON of the pure
 * `RecurrenceSpec`, a passthrough TEXT column the repository parses via the recurrence module.
 */
@Entity(
    tableName = "task_schedules",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["next_fire_at"]),
    ],
)
data class TaskScheduleEntity(
    /** Stable schedule id (uuid). A recurring schedule keeps the SAME id across re-fires. */
    @PrimaryKey
    val id: String,
    /** Mandatory parent: scopes UI, retention, cleanup, list and delete to one conversation. */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** Must resolve to an existing `spawnable` Assistant at create time (gate in the repository). */
    @ColumnInfo("target_assistant_id")
    val targetAssistantId: String,
    /** The bounded prompt that fires against the target assistant (length gate in the repository). */
    val prompt: String,
    /** Persisted name of a `ScheduleOwner` (USER | AGENT); the cap is computed per owner class. */
    val owner: String,
    /** Persisted name of a `ScheduleKind` (ONE_SHOT | RECURRING). */
    val kind: String,
    /** JSON `RecurrenceSpec`; non-null iff [kind] == RECURRING, null for ONE_SHOT. */
    @ColumnInfo("recurrence_spec")
    val recurrenceSpec: String? = null,
    /** IANA zone id; recurrence is derived in this zone so a "daily 09:00" survives DST. */
    @ColumnInfo("time_zone_id")
    val timeZoneId: String,
    /** Epoch millis (wall clock) of the first (or only) fire; the recurrence grid's anchor. */
    @ColumnInfo("first_fire_at")
    val firstFireAt: Long,
    /** Epoch millis (wall clock) of the next fire; the rescheduler/claim due-key. Indexed. */
    @ColumnInfo("next_fire_at")
    val nextFireAt: Long,
    /** A one-shot flips this false after it fires; the claim filters on it. */
    val enabled: Boolean,
    /** The last `task_runs.id` this schedule produced; null until the first fire finishes. */
    @ColumnInfo("last_task_run_id")
    val lastTaskRunId: String? = null,
    /** Non-null while a fire is in flight: the claim sets it, finishRun clears it. */
    @ColumnInfo("running_task_run_id")
    val runningTaskRunId: String? = null,
    /** Epoch millis of the most recent claim; null until the first fire. */
    @ColumnInfo("last_fired_at")
    val lastFiredAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    /** Persisted name of a `MisfirePolicy`; default FIRE_ONCE_AND_COALESCE. */
    @ColumnInfo("misfire_policy", defaultValue = "FIRE_ONCE_AND_COALESCE")
    val misfirePolicy: String = "FIRE_ONCE_AND_COALESCE",
)
