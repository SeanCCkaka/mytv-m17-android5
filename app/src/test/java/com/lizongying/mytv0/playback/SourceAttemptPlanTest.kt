package com.lizongying.mytv0.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAttemptPlanTest {
    private fun drain(plan: SourceAttemptPlan): List<SourceAttemptPlan.Attempt> =
        generateSequence { plan.next() }.toList()

    @Test fun triesEveryBackupOnceStartingAtTheSelectedSourceAndWrapping() {
        val urls = listOf("https://a/live.m3u8", "https://b/live.m3u8", "https://c/live.m3u8")
        val plan = SourceAttemptPlan(urls, 1)
        assertEquals(listOf(1, 2, 0), drain(plan).map { it.index })
        assertEquals(3, plan.attemptedCount)
        assertFalse(plan.hasNext)
        assertNull(plan.next())
    }

    @Test fun selectingOneWorkingSourceDoesNotConsumeItsBackups() {
        val plan = SourceAttemptPlan(listOf("a", "b", "c"), 2)
        assertEquals(2, plan.next()?.index)
        assertEquals(1, plan.attemptedCount)
        assertTrue(plan.hasNext)
        // If that working source later fails, only addresses not yet tried remain.
        assertEquals(listOf(0, 1), drain(plan).map { it.index })
    }

    @Test fun repeatedUrlsDoNotCauseRepeatedNetworkAttempts() {
        val plan = SourceAttemptPlan(listOf("a", "b", "a", "b", "c"), 2)
        assertEquals(listOf(2, 3, 4), drain(plan).map { it.index })
        assertEquals(3, plan.totalAttempts)
        assertEquals(3, plan.availableSourceCount)
    }

    @Test fun fiveAttemptLimitBoundsLongBrokenLists() {
        val plan = SourceAttemptPlan((0 until 20).map { "https://host$it/live.m3u8" }, 19)
        assertEquals(listOf(19, 0, 1, 2, 3), drain(plan).map { it.index })
        assertEquals(5, plan.attemptedCount)
        assertTrue(plan.isLimited)
        assertFalse(plan.hasNext)
        repeat(10) { assertNull(plan.next()) }
    }

    @Test fun aLocalDiagnosticSampleIsNeverAutomaticallyRetried() {
        val plan = SourceAttemptPlan(listOf("file:///app/files/compat-sample.mp4"), 0, maxAttempts = 1)
        assertEquals(0, plan.next()?.index)
        assertNull(plan.next())
        assertEquals(1, plan.attemptedCount)
    }

    @Test fun blankOrEmptyListsHaveNoAttempts() {
        listOf(emptyList(), listOf("", " ")).forEach {
            val plan = SourceAttemptPlan(it, 0)
            assertNull(plan.next())
            assertEquals(0, plan.attemptedCount)
            assertFalse(plan.hasNext)
        }
    }

    @Test fun invalidSavedIndicesAreClampedToTheCurrentList() {
        assertEquals(listOf(0, 1), drain(SourceAttemptPlan(listOf("a", "b"), -1)).map { it.index })
        assertEquals(listOf(1, 0), drain(SourceAttemptPlan(listOf("a", "b"), 20)).map { it.index })
    }

    @Test fun changingChannelsCreatesIndependentAttemptBudgets() {
        val first = SourceAttemptPlan(listOf("a", "b"), 0)
        drain(first)
        val second = SourceAttemptPlan(listOf("a", "b"), 1)
        assertFalse(first.hasNext)
        assertEquals(0, second.attemptedCount)
        assertEquals(listOf(1, 0), drain(second).map { it.index })
    }

    @Test fun attemptsUseAnImmutableSnapshotOfTheOriginalAddresses() {
        val addresses = mutableListOf("a", "b")
        val plan = SourceAttemptPlan(addresses, 0)
        addresses[0] = "new-address"
        assertEquals("a", plan.next()?.url)
    }

    @Test fun invalidAttemptLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceAttemptPlan(listOf("a"), 0, 0) }
    }
}
