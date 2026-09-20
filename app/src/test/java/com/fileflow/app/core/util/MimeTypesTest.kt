package com.fileflow.app.core.util

import com.fileflow.app.core.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `extension is lowercased and stripped of the dot`() {
        assertEquals("pdf", MimeTypes.normalizeExtension(".PDF"))
        assertEquals("jpg", MimeTypes.normalizeExtension("JPG"))
        assertEquals("", MimeTypes.normalizeExtension(null))
    }

    @Test
    fun `extension of a name ignores a trailing number`() {
        assertEquals("", MimeTypes.extensionOf("backup 2023"))
        assertEquals("pdf", MimeTypes.extensionOf("invoice.pdf"))
        assertEquals("", MimeTypes.extensionOf("no-extension"))
        assertEquals("", MimeTypes.extensionOf("weird."))
    }

    @Test
    fun `a long dotted tail is not treated as an extension`() {
        assertEquals("", MimeTypes.extensionOf("something.verylongsuffix"))
    }

    @Test
    fun `double extensions use the last one`() {
        assertEquals("gz", MimeTypes.extensionOf("archive.tar.gz"))
    }

    @Test
    fun `categories cover the common families`() {
        assertEquals(FileCategory.IMAGES, MimeTypes.categoryOf("jpg"))
        assertEquals(FileCategory.VIDEOS, MimeTypes.categoryOf("mkv"))
        assertEquals(FileCategory.AUDIO, MimeTypes.categoryOf("flac"))
        assertEquals(FileCategory.DOCUMENTS, MimeTypes.categoryOf("docx"))
        assertEquals(FileCategory.ARCHIVES, MimeTypes.categoryOf("7z"))
        assertEquals(FileCategory.APPS, MimeTypes.categoryOf("apk"))
        assertEquals(FileCategory.CODE, MimeTypes.categoryOf("kt"))
        assertEquals(FileCategory.OTHER, MimeTypes.categoryOf("xyz"))
    }

    @Test
    fun `mime lookup falls back to octet stream`() {
        assertEquals("application/pdf", MimeTypes.mimeFor("pdf"))
        assertEquals("application/octet-stream", MimeTypes.mimeFor("nonsense"))
    }

    @Test
    fun `png signature is detected`() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("png", MimeTypes.signatureExtension(header))
    }

    @Test
    fun `jpeg signature is detected`() {
        assertEquals(
            "jpg",
            MimeTypes.signatureExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
    }

    @Test
    fun `pdf signature is detected`() {
        assertEquals(
            "pdf",
            MimeTypes.signatureExtension("%PDF-1.7".toByteArray()),
        )
    }

    @Test
    fun `an android manifest inside a zip means apk`() {
        val header = ("PK\u0003\u0004" + "AndroidManifest.xml").toByteArray(Charsets.ISO_8859_1)
        assertEquals("apk", MimeTypes.signatureExtension(header))
    }

    @Test
    fun `a word folder inside a zip means docx`() {
        val header = ("PK\u0003\u0004" + "word/document.xml").toByteArray(Charsets.ISO_8859_1)
        assertEquals("docx", MimeTypes.signatureExtension(header))
    }

    @Test
    fun `plain zip stays a zip`() {
        val header = ("PK\u0003\u0004" + "notes.txt").toByteArray(Charsets.ISO_8859_1)
        assertEquals("zip", MimeTypes.signatureExtension(header))
    }

    @Test
    fun `an unknown header yields nothing`() {
        assertFalse(MimeTypes.signatureExtension(byteArrayOf(1, 2, 3, 4)) != null)
    }

    @Test
    fun `an empty header is safe`() {
        assertTrue(MimeTypes.signatureExtension(ByteArray(0)) == null)
    }
}
