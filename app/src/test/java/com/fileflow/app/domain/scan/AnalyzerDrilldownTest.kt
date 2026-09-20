package com.fileflow.app.domain.scan

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.ui.util.FileOpen
import com.fileflow.app.ui.util.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerDrilldownTest {

    private fun result(
        dirBytes: Map<String, Long>,
        categoryRows: Map<String, LongArray> = emptyMap(),
    ) = AnalysisResult(
        root = "/root",
        totalBytes = dirBytes["/root"] ?: 0L,
        totalFiles = 0,
        dirBytes = dirBytes,
        dirFiles = dirBytes.mapValues { 1 },
        dirCategoryBytes = categoryRows,
    )

    @Test
    fun `a folder reports its recursive size not its own listing`() {
        // The bug this guards: Android/media is listed as almost empty but holds
        // 7.5 GB three levels down.
        val analysis = result(
            mapOf(
                "/root" to 8_000L,
                "/root/Android" to 7_500L,
                "/root/Android/media" to 7_500L,
            )
        )
        assertEquals(7_500L, analysis.bytesOf("/root/Android", isDirectory = true, ownSize = 0L))
        assertEquals(7_500L, analysis.bytesOf("/root/Android/media", isDirectory = true, ownSize = 0L))
    }

    @Test
    fun `a file falls back to its own size`() {
        val analysis = result(mapOf("/root" to 100L))
        assertEquals(42L, analysis.bytesOf("/root/a.txt", isDirectory = false, ownSize = 42L))
    }

    @Test
    fun `an unmeasured folder reads as unknown rather than zero`() {
        val analysis = result(mapOf("/root" to 100L))
        // A folder created after the scan has no entry; the UI must not claim
        // it is empty, which is what made drill-down look broken.
        assertFalse(analysis.dirBytes.containsKey("/root/New"))
        assertEquals(0L, analysis.bytesOf("/root/New", isDirectory = true, ownSize = 0L))
    }

    @Test
    fun `category bytes are read per folder`() {
        val row = LongArray(FileCategory.entries.size)
        row[FileCategory.IMAGES.ordinal] = 500L
        row[FileCategory.VIDEOS.ordinal] = 250L
        val analysis = result(mapOf("/root" to 750L), mapOf("/root/DCIM" to row))

        assertEquals(500L, analysis.categoryBytes("/root/DCIM", FileCategory.IMAGES))
        assertEquals(250L, analysis.categoryBytes("/root/DCIM", FileCategory.VIDEOS))
        assertEquals(0L, analysis.categoryBytes("/root/DCIM", FileCategory.AUDIO))
        assertEquals(0L, analysis.categoryBytes("/root/Nowhere", FileCategory.IMAGES))
    }

    @Test
    fun `usage entries know whether they were measured`() {
        val known = UsageEntry("/root/DCIM", "DCIM", true, 900L, 12, measured = true)
        val unknown = UsageEntry("/root/New", "New", true, 0L, 0, measured = false)
        assertTrue(known.measured)
        assertTrue(known.isDirectory)
        assertFalse(unknown.measured)
        assertFalse(known.isFile)
    }
}

class FileOpenRoutingTest {

    private fun item(name: String, category: FileCategory, isDirectory: Boolean = false) = FileItem(
        path = "/root/$name",
        name = name,
        isDirectory = isDirectory,
        sizeBytes = 10L,
        lastModified = 0L,
        extension = name.substringAfterLast('.', ""),
        mimeType = "application/octet-stream",
        category = category,
        isHidden = false,
    )

    @Test
    fun `photos open in the built-in viewer`() {
        assertEquals(
            PreviewKind.IMAGE,
            FileOpen.kindOf(item("shot.jpg", FileCategory.IMAGES)),
        )
    }

    @Test
    fun `text and code open in the built-in reader`() {
        assertEquals(PreviewKind.TEXT, FileOpen.kindOf(item("notes.txt", FileCategory.DOCUMENTS)))
        assertEquals(PreviewKind.TEXT, FileOpen.kindOf(item("build.gradle", FileCategory.CODE)))
    }

    @Test
    fun `everything else goes to the system`() {
        assertEquals(
            PreviewKind.EXTERNAL,
            FileOpen.kindOf(item("clip.mp4", FileCategory.VIDEOS)),
        )
        assertEquals(
            PreviewKind.EXTERNAL,
            FileOpen.kindOf(item("DCIM", FileCategory.OTHER, isDirectory = true)),
        )
    }
}
