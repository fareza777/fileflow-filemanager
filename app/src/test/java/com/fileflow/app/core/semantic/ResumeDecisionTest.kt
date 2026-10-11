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

    @Test
    fun `416 means the range is past the end, not that something went wrong`() {
        // This is the answer that broke the download: the file was complete, our
        // guess at its size was too big, and every retry asked for a range past
        // the end of it — five attempts, then an error, forever.
        assertEquals(
            ResumeDecision.Action.REFUSED,
            ResumeDecision.forResponse(416, alreadyOnDisk = 340L * 1024 * 1024, expected = total),
        )
        // With nothing on disk there is nothing to keep, so it is a real failure.
        assertEquals(
            ResumeDecision.Action.RESTART,
            ResumeDecision.forResponse(416, alreadyOnDisk = 0, expected = total),
        )
    }

    // ------------------------------------------------------- the real size

    @Test
    fun `the server's own size beats the guess in the code`() {
        val real = 340L * 1024 * 1024
        assertEquals(
            real,
            ResumeDecision.totalSize(
                linkedSize = real.toString(),
                contentRange = null,
                contentLength = -1,
                appending = false,
                alreadyOnDisk = 0,
                fallback = total,
            ),
        )
    }

    @Test
    fun `content-range gives the whole file, not the chunk`() {
        // "bytes 100-199/1234": the part after the slash is the file.
        assertEquals(
            1234L,
            ResumeDecision.totalSize(
                linkedSize = null,
                contentRange = "bytes 100-199/1234",
                contentLength = 100,
                appending = true,
                alreadyOnDisk = 100,
                fallback = total,
            ),
        )
    }

    @Test
    fun `content-length is the chunk when appending and the file when not`() {
        assertEquals(
            1000L,
            ResumeDecision.totalSize(null, null, contentLength = 1000, appending = false, alreadyOnDisk = 0, fallback = 0),
        )
        assertEquals(
            1100L,
            ResumeDecision.totalSize(null, null, contentLength = 100, appending = true, alreadyOnDisk = 1000, fallback = 0),
        )
    }

    @Test
    fun `an unknowable size falls back to the guess rather than to zero`() {
        // Zero would make every download look finished the moment it started.
        assertEquals(
            total,
            ResumeDecision.totalSize(null, null, contentLength = -1, appending = false, alreadyOnDisk = 0, fallback = total),
        )
        // A wildcard total is not a size.
        assertEquals(
            total,
            ResumeDecision.totalSize(null, "bytes 0-99/*", contentLength = -1, appending = false, alreadyOnDisk = 0, fallback = total),
        )
    }
}
