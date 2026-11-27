package com.guiamed.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadBudgetTest {
    @Test fun focusCaptureAndOcrShareDeadlineAndReserveHttp() {
        var now = 100L
        val budget = ReadBudget(24_000) { now }
        now += 1_400 + 4_000
        assertEquals(18_600L, budget.remainingMillis)
        now += 8_000
        assertEquals(5_600L, budget.allowance(6_000, ReadBudget.HTTP_MILLIS))
        now += 5_600
        assertEquals(5_000L, budget.remainingMillis)
        now += 5_000
        assertEquals(0L, budget.allowance(8_000))
    }

    @Test fun exhaustedBudgetNeverStartsAnotherPass() = runBlocking {
        var started = false
        assertNull(ReadBudget(0).run(5_000) { started = true; "unexpected" })
        assertFalse(started)
    }
}
