package com.fileflow.app.core.privileged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The two ways a shell command hangs, with real processes.
 *
 * Both are the kind of bug that only shows up on a device with real data: the
 * pipe-buffer deadlock needs more than 64 KB of output — a folder with a few
 * thousand files — and the timeout path needs a command that never finishes.
 * Running a process here is the only way to be sure the code that runs one on
 * the phone does not wait forever.
 */
class ShellProcessTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * The launcher of the JVM running this test: a real JDK, since the emitter
     * below is run straight from source. Both spellings are tried, because on
     * Windows it is `java.exe` and the obvious guess is wrong.
     */
    private val java: String? = listOf("java", "java.exe")
        .map { File(File(System.getProperty("java.home"), "bin"), it) }
        .firstOrNull { it.isFile }
        ?.absolutePath

    /** A program that prints a lot, or sleeps like a command that never ends. */
    private fun emitter(): String {
        val file = File(temp.root, "Emitter.java")
        file.writeText(
            """
            public class Emitter {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("sleep")) {
                        Thread.sleep(600_000L);
                        return;
                    }
                    int lines = Integer.parseInt(args[0]);
                    for (int i = 0; i < lines; i++) {
                        System.out.println("line " + i + " of output that is long enough to fill a pipe");
                    }
                    System.err.println("and one line of complaint");
                }
            }
            """.trimIndent()
        )
        return file.absolutePath
    }

    @Test
    fun `output larger than a pipe buffer is read, not deadlocked`() {
        assumeTrue("a JDK is needed to run a real process", java != null)

        // 20 000 lines is roughly 1 MB — sixteen times what a pipe holds. The
        // old code waited for the process first, so this call never returned.
        val result = ShellProcess.run(
            listOf(java!!, emitter(), "20000"),
            timeoutSeconds = 60,
        )

        assertEquals("the command should have finished cleanly", 0, result.code)
        assertTrue(
            "expected the whole output, got ${result.stdout.length} characters",
            result.stdout.length > 900_000,
        )
        assertTrue("stderr is kept separate", result.stderr.contains("one line of complaint"))
        assertTrue(result.stdout.contains("line 19999"))
    }

    @Test
    fun `a command that never ends is stopped instead of waited for`() {
        assumeTrue("a JDK is needed to run a real process", java != null)

        val started = System.currentTimeMillis()
        val result = ShellProcess.run(
            listOf(java!!, emitter(), "sleep"),
            timeoutSeconds = 2,
        )
        val took = System.currentTimeMillis() - started

        assertEquals("a timeout is reported as a failure", -1, result.code)
        assertTrue("it should say what happened: ${result.stderr}", result.stderr.contains("timed out"))
        assertTrue("and it should return near the timeout, took ${took}ms", took < 15_000)
    }

    @Test
    fun `a command that does not exist is reported, not thrown`() {
        val result = ShellProcess.run(listOf("fileflow-no-such-binary", "--help"), timeoutSeconds = 5)
        assertEquals(-1, result.code)
        assertTrue(result.stderr.isNotEmpty())
    }
}
