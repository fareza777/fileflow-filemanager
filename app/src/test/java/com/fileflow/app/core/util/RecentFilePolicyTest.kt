package com.fileflow.app.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFilePolicyTest {
    @Test
    fun `webp is excluded regardless of filename case or mime`() {
        assertFalse(MediaStoreItems.isRecentFile("/storage/emulated/0/Pictures/sticker.webp"))
        assertFalse(MediaStoreItems.isRecentFile("/storage/emulated/0/Pictures/STICKER.WEBP"))
        assertFalse(MediaStoreItems.isRecentFile("/storage/emulated/0/Download/image", "image/webp"))
        assertTrue(MediaStoreItems.isUserFile("/storage/emulated/0/Pictures/sticker.webp"))
    }

    @Test
    fun `regular files remain eligible and caches stay hidden`() {
        listOf("photo.jpg", "image.png", "video.mp4", "report.pdf", "archive.zip", "webp-notes.txt")
            .forEach { assertTrue(it, MediaStoreItems.isRecentFile("/storage/emulated/0/Download/$it")) }
        assertFalse(MediaStoreItems.isRecentFile("/storage/emulated/0/.thumbnails/photo.jpg"))
        assertFalse(MediaStoreItems.isRecentFile("/storage/emulated/0/Android/data/app/photo.jpg"))
    }
}
