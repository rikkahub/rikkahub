package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Review mustFix regression (issue #364 round 2): the `/goal` budget charge is a READ-MODIFY-WRITE
 * on a session-scoped reference, racing a user-stop (clear) and a fresh `/goal` (re-arm). A bare
 * `@Volatile var activeGoal = activeGoal?.copy(...)` write-back could resurrect a cleared goal,
 * clobber a newer goal, or carry a stale iteration count into it. [ConversationSession.compareAndSetGoal]
 * makes the transition identity-guarded; these tests pin that contract.
 */
class GoalSpecCasTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    @Test
    fun `charge commits when the goal is unchanged`() {
        val s = session()
        val g0 = GoalSpec("ship it", iterationsUsed = 0)
        s.armGoal(g0)

        val charged = g0.copy(iterationsUsed = g0.iterationsUsed + 1)
        assertTrue("charge must succeed when the goal is still the read instance", s.compareAndSetGoal(g0, charged))
        assertEquals(1, s.activeGoal?.iterationsUsed)
        assertEquals("ship it", s.activeGoal?.condition)
    }

    @Test
    fun `a concurrent clear makes the charge fail and does not resurrect the goal`() {
        val s = session()
        val g0 = GoalSpec("ship it", iterationsUsed = 0)
        s.armGoal(g0)

        // user-stop wins
        s.clearGoal()

        val charged = g0.copy(iterationsUsed = 1)
        assertFalse("charging a cleared goal must fail", s.compareAndSetGoal(g0, charged))
        assertNull("a stale charge must NOT resurrect the cleared goal", s.activeGoal)
    }

    @Test
    fun `a concurrent re-arm makes the charge fail and does not clobber the new goal`() {
        val s = session()
        val g0 = GoalSpec("first goal", iterationsUsed = 3)
        s.armGoal(g0)

        // a fresh /goal replaces it mid-flight
        val g1 = GoalSpec("second goal", iterationsUsed = 0)
        s.armGoal(g1)

        val charged = g0.copy(iterationsUsed = 4)
        assertFalse("charging the old goal must fail after a re-arm", s.compareAndSetGoal(g0, charged))
        assertSame("the newer goal must survive intact", g1, s.activeGoal)
        assertEquals(0, s.activeGoal?.iterationsUsed)
    }

    @Test
    fun `met-clear is identity-guarded so a re-armed goal survives`() {
        val s = session()
        val g0 = GoalSpec("first goal", iterationsUsed = 0)
        s.armGoal(g0)

        val g1 = GoalSpec("second goal", iterationsUsed = 0)
        s.armGoal(g1)

        // the loop judged g0 "met" (slow judge ran while g1 was armed) -> guarded clear
        assertFalse("a stale met-clear must not succeed", s.compareAndSetGoal(g0, null))
        assertSame("the re-armed goal must survive a stale met-clear", g1, s.activeGoal)
    }

    @Test
    fun `arm and clear are unconditional`() {
        val s = session()
        assertNull(s.activeGoal)

        s.armGoal(GoalSpec("g"))
        assertEquals("g", s.activeGoal?.condition)

        s.clearGoal()
        assertNull(s.activeGoal)
    }
}
