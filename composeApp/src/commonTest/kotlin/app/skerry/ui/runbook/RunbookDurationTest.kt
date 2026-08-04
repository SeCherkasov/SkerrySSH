package app.skerry.ui.runbook

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How long a step took, as the run screen reads it out. Split from the wording so the units stay
 * translatable: the numbers are decided here, the sentence in the resources.
 */
class RunbookDurationTest {

    @Test
    fun `a step under a minute reads in seconds with one decimal`() {
        assertEquals(RunbookDuration.Seconds("0.4"), runbookDuration(400))
        assertEquals(RunbookDuration.Seconds("1.1"), runbookDuration(1_100))
        assertEquals(RunbookDuration.Seconds("9.7"), runbookDuration(9_712))
    }

    @Test
    fun `a step of a minute or more drops the decimal and counts minutes`() {
        assertEquals(RunbookDuration.Minutes(1, 12), runbookDuration(72_000))
        assertEquals(RunbookDuration.Minutes(1, 4), runbookDuration(64_400))
        assertEquals(RunbookDuration.Minutes(12, 0), runbookDuration(720_000))
    }

    @Test
    fun `an instant step still reads as a number rather than nothing`() {
        assertEquals(RunbookDuration.Seconds("0.0"), runbookDuration(0))
    }

    @Test
    fun `a negative reading is treated as zero rather than shown`() {
        // The clock can only go backwards through a system time change; a "-3.0 s" step would read
        // as a bug in the run rather than in the clock.
        assertEquals(RunbookDuration.Seconds("0.0"), runbookDuration(-3_000))
    }

    @Test
    fun `seconds round rather than truncate`() {
        assertEquals(RunbookDuration.Seconds("1.0"), runbookDuration(950))
        assertEquals(RunbookDuration.Minutes(1, 0), runbookDuration(59_960))
    }
}
