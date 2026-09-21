package com.fileflow.app.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything that goes into a privileged command, and everything that comes
 * back out of one.
 *
 * The quoting is a security boundary, not a formatting detail: this app builds
 * shell commands out of paths that came from a filesystem, and a path
 * containing a quote or a semicolon would otherwise turn "copy this file" into
 * "run this command" — as the shell's user, which on a device with Shizuku or
 * root means as the system. The listing parser is the other half: a misread
 * column is a file manager that reports the wrong size for a file the user
 * cannot otherwise check.
 */
class PrivilegedShellTextTest {

    @Test
    fun `a plain path is quoted as one word`() {
        assertEquals("'/storage/emulated/0/Download'", ShellText.quote("/storage/emulated/0/Download"))
    }

    @Test
    fun `a quote in a path cannot end the quoting`() {
        // The classic escape: closing the quote, injecting a command, and
        // reopening. Single quotes have no escape character, so the only way
        // out is to end the literal, emit an escaped quote, and start again.
        val hostile = "/tmp/it's here; rm -rf /sdcard"
        val quoted = ShellText.quote(hostile)

        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertTrue("the embedded quote must be escaped", quoted.contains("'\\''"))
        // The semicolon stays inside a literal, so the shell never sees it as a
        // command separator.
        assertTrue(quoted.contains("; rm -rf /sdcard"))
    }

    @Test
    fun `a dollar sign in a path is not expanded`() {
        val quoted = ShellText.quote("/tmp/\$HOME/secrets")
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
    }

    @Test
    fun `a listing line becomes an entry with its size and time`() {
        val output = buildString {
            append("f\t1024\t1700000000\tnotes.txt\n")
            append("d\t0\t1700000100\tDocuments\n")
            append("f\t0\t0\tempty.bin\n")
        }
        val entries = ShellText.parseListing(output, "/data/data/com.example")

        assertEquals(3, entries.size)
        // Directories first, then names.
        assertEquals("Documents", entries.first().name)
        assertTrue(entries.first().isDirectory)

        val notes = entries.first { it.name == "notes.txt" }
        assertEquals(1024L, notes.sizeBytes)
        assertEquals(1_700_000_000_000L, notes.lastModified)
        assertEquals("/data/data/com.example/notes.txt", notes.path)
    }

    @Test
    fun `a name with tabs or spaces keeps its full text`() {
        val output = "f\t42\t1700000000\tmy file\twith tab.txt\n"
        val entries = ShellText.parseListing(output, "/sdcard")
        assertEquals("my file\twith tab.txt", entries.single().name)
    }

    @Test
    fun `junk lines are skipped rather than guessed at`() {
        val output = buildString {
            append("garbage without tabs\n")
            append("f\tnot-a-number\t1700000000\tweird.txt\n")
            append("f\t10\t1700000000\tgood.txt\n")
            append("\n")
        }
        val entries = ShellText.parseListing(output, "/sdcard")

        // The unparseable row still becomes an entry — with a size of zero —
        // because hiding a file is worse than being wrong about its size. The
        // garbage line and the blank one are dropped.
        assertEquals(listOf("good.txt", "weird.txt"), entries.map { it.name }.sorted())
        assertEquals(0L, entries.first { it.name == "weird.txt" }.sizeBytes)
    }

    @Test
    fun `dot entries are dropped`() {
        val output = "d\t0\t0\t.\nd\t0\t0\t..\nf\t1\t0\treal.txt\n"
        val entries = ShellText.parseListing(output, "/sdcard")
        assertEquals(listOf("real.txt"), entries.map { it.name })
    }

    @Test
    fun `du output in kilobytes becomes bytes`() {
        assertEquals(4096L, ShellText.parseDu("4\t/sdcard/Download"))
        assertEquals(1_048_576L, ShellText.parseDu("1024\t/data/local/tmp"))
        assertEquals(null, ShellText.parseDu(""))
        assertEquals(null, ShellText.parseDu("du: not found"))
    }

    @Test
    fun `a child path joins without doubling the separator`() {
        assertEquals("/a/b/c", ShellText.remoteChild("/a/b", "c"))
        assertEquals("/a/b/c", ShellText.remoteChild("/a/b/", "c"))
    }
}
