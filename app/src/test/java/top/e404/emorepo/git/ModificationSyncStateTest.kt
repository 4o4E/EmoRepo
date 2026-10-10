package top.e404.emorepo.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModificationSyncStateTest {
    @Test
    fun anotherModificationExtendsQuietWindowInsteadOfUsingFirstDeadline() {
        val state = ModificationSyncState()
            .afterModification(1_000L)
            .afterModification(9_000L)

        assertEquals(8_000L, state.remainingDelayMillis(11_000L))
        assertEquals(1L, state.remainingDelayMillis(18_999L))
        assertEquals(0L, state.remainingDelayMillis(19_000L))
        assertEquals(0L, state.remainingDelayMillis(20_000L))
    }

    @Test
    fun completingRunningSyncLeavesLaterModificationsPending() {
        val first = ModificationSyncState().afterModification(1_000L)
        val runningGeneration = first.generation
        val changedDuringSync = first.afterModification(20_000L).afterModification(25_000L)
        val completed = changedDuringSync.syncedThrough(runningGeneration)

        assertTrue(completed.hasPendingChanges)
        assertEquals(5_000L, completed.remainingDelayMillis(30_000L))
        assertEquals(0L, completed.remainingDelayMillis(35_000L))
        assertFalse(completed.syncedThrough(changedDuringSync.generation).hasPendingChanges)
    }

    @Test
    fun completedWindowDoesNotSwallowTheNextModificationOrReopenOnOldCompletion() {
        val first = ModificationSyncState().afterModification(1_000L)
        val second = first.afterModification(2_000L)
        val completed = second.syncedThrough(second.generation).syncedThrough(first.generation)
        assertFalse(completed.hasPendingChanges)

        val next = completed.afterModification(40_000L)
        assertTrue(next.hasPendingChanges)
        assertEquals(1L, next.remainingDelayMillis(49_999L))
        assertEquals(0L, next.remainingDelayMillis(50_000L))
    }
}
