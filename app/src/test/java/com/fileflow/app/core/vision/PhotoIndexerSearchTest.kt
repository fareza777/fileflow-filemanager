package com.fileflow.app.core.vision

import android.content.Context
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.db.PhotoIndexDao
import com.fileflow.app.data.db.PhotoIndexEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoIndexerSearchTest {
    private val dao = mockk<PhotoIndexDao>()
    private val indexer = PhotoIndexer(mockk<Context>(), dao, mockk<PhotoText>(), mockk<PhotoThemes>(), mockk<VolumeRepository>())

    private fun row(name: String, labels: String, modified: Long = 1L, text: String = "") =
        PhotoIndexEntity("/photos/$name", name, modified, 100L, text, text.lowercase(), labels, 1L)

    private fun stub(text: List<PhotoIndexEntity> = emptyList(), labels: List<PhotoIndexEntity> = emptyList()) {
        coEvery { dao.searchText(any(), any(), any(), any()) } returns text
        coEvery { dao.count() } returns 8490
        coEvery { dao.searchLabels(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns labels
    }

    @Test
    fun `community work rejects selfies and ranks evidence before recency`() = runTest {
        stub(labels = listOf(
            row("selfie.jpg", "person,smile", 100),
            row("group.jpg", "team,field", 90),
            row("work.jpg", "team,community,field,construction", 1),
        ))
        val hits = indexer.search("kerja bakti")
        assertEquals(listOf("work.jpg", "group.jpg"), hits.map { it.name })
        assertEquals(PhotoMatch.THEME, hits.first().match)
        assertNull(hits.first().snippet)
        coVerify { dao.searchLabels("%,team,%", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), 8490) }
    }

    @Test
    fun `text evidence stays first and duplicate photos appear once`() = runTest {
        val exact = row("receipt.jpg", "receipt,money", 1, "bukti transfer berhasil")
        stub(text = listOf(exact), labels = listOf(row("new.jpg", "receipt", 100), exact))
        val hits = indexer.search("bukti transfer")
        assertEquals(listOf("receipt.jpg", "new.jpg"), hits.map { it.name })
        assertEquals(PhotoMatch.TEXT, hits.first().match)
        assertEquals(PhotoMatch.THEME, hits.last().match)
        assertNull(hits.last().snippet)
    }

    @Test
    fun `filename match is not called recognised text`() = runTest {
        stub(text = listOf(row("makanan.jpg", "", text = "unrelated text")))
        assertEquals(PhotoMatch.NAME, indexer.search("makanan").single().match)
    }

    @Test
    fun `blank query does not query storage`() = runTest {
        assertEquals(emptyList<PhotoHit>(), indexer.search(" "))
        coVerify(exactly = 0) { dao.searchText(any(), any(), any(), any()) }
    }
}
