package com.fileflow.app.core.semantic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resume, which is the difference between a download that finishes and one that
 * never does.
 *
 * A 397 MB file over a phone connection is interrupted; if every attempt starts
 * at zero it can be interrupted forever. The three answers a server can give —
 * "carry on", "I ignored your range, here is the whole thing", "you already have
 * it" — are decided here, because getting any of them wrong produces a file of
 * the right size that is not the model.
 */
class ResumeDecisionTest {

    private val total = 397L * 1024 * 1024

    @Test
    fun `a partial file plus a 206 carries on from where it stopped`() {
        assertEquals(
            ResumeDecision.Action.APPEND,
            ResumeDecision.forResponse(206, alreadyOnDisk = 120L * 1024 * 1024, expected = total),
        )
    }

    @Test
    fun `a 200 means the range was ignored, so the partial file is replaced`() {
        // Appending here would glue the start of one response onto the end of
        // another and produce a file that loads and is nonsense.
        assertEquals(
            ResumeDecision.Action.RESTART,
            ResumeDecision.forResponse(200, alreadyOnDisk = 120L * 1024 * 1024, expected = total),
        )
    }

    @Test
    fun `a file already at full size needs no request`() {
        assertEquals(
            ResumeDecision.Action.COMPLETE,
            ResumeDecision.forResponse(200, alreadyOnDisk = total, expected = total),
        )
        assertEquals(
            ResumeDecision.Action.COMPLETE,
            ResumeDecision.forResponse(206, alreadyOnDisk = total + 1024, expected = total),
        )
    }

    @Test
    fun `a first attempt with nothing on disk starts from the beginning`() {
        assertEquals(
            ResumeDecision.Action.RESTART,
            ResumeDecision.forResponse(200, alreadyOnDisk = 0, expected = total),
        )
        // A server that answers a range-less request with 206 is still sending
        // the object from zero, and appending nothing is the same thing.
        assertEquals(
            ResumeDecision.Action.RESTART,
            ResumeDecision.forResponse(206, alreadyOnDisk = 0, expected = total),
        )
    }

    @Test
    fun `an unknown size never claims the file is complete`() {
        // expected == 0 would otherwise make every file look finished.
        assertEquals(
            ResumeDecision.Action.RESTART,
            ResumeDecision.forResponse(200, alreadyOnDisk = 500, expected = 0),
        )
    }
}
