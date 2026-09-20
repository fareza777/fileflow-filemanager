package com.fileflow.app.core.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollisionResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a free name is returned unchanged`() {
        val directory = temp.newFolder("d")
        assertEquals("photo.jpg", CollisionResolver.unique(directory, "photo.jpg"))
    }

    @Test
    fun `an existing name gains a counter`() {
        val directory = temp.newFolder("d2")
        File(directory, "photo.jpg").writeText("x")
        assertEquals("photo (2).jpg", CollisionResolver.unique(directory, "photo.jpg"))
    }

    @Test
    fun `the counter keeps climbing while names are taken`() {
        val directory = temp.newFolder("d3")
        File(directory, "report.pdf").writeText("x")
        File(directory, "report (2).pdf").writeText("x")
        File(directory, "report (3).pdf").writeText("x")
        assertEquals("report (4).pdf", CollisionResolver.unique(directory, "report.pdf"))
    }

    @Test
    fun `a name with no extension still gets a counter`() {
        val directory = temp.newFolder("d4")
        File(directory, "README").writeText("x")
        assertEquals("README (2)", CollisionResolver.unique(directory, "README"))
    }

    /**
     * The important case: two files in the same batch must not be handed the
     * same destination just because neither exists on disk yet.
     */
    @Test
    fun `a batch cannot claim the same name twice`() {
        val directory = temp.newFolder("d5")
        val claimed = mutableSetOf<String>()

        val first = CollisionResolver.unique(directory, "clip.mp4", claimed)
        val second = CollisionResolver.unique(directory, "clip.mp4", claimed)
        val third = CollisionResolver.unique(directory, "clip.mp4", claimed)

        assertEquals("clip.mp4", first)
        assertEquals("clip (2).mp4", second)
        assertEquals("clip (3).mp4", third)
    }

    @Test
    fun `claiming is case insensitive so Android does not collide`() {
        val directory = temp.newFolder("d6")
        val claimed = mutableSetOf<String>()

        val first = CollisionResolver.unique(directory, "Photo.JPG", claimed)
        val second = CollisionResolver.unique(directory, "photo.jpg", claimed)

        assertNotEquals(first.lowercase(), second.lowercase())
    }

    @Test
    fun `different folders do not interfere with each other`() {
        val a = temp.newFolder("a")
        val b = temp.newFolder("b")
        val claimed = mutableSetOf<String>()

        assertEquals("same.txt", CollisionResolver.unique(a, "same.txt", claimed))
        assertEquals("same.txt", CollisionResolver.unique(b, "same.txt", claimed))
    }

    @Test
    fun `when every candidate is taken it still terminates with a unique name`() {
        val directory = temp.newFolder("d7")
        val claimed = mutableSetOf<String>()
        // A pessimistic existence check: nothing is ever free. The resolver
        // must give up gracefully rather than loop forever.
        val result = CollisionResolver.unique(directory, "x.txt", claimed) { true }
        assertTrue(result.endsWith(".txt"))
        assertNotEquals("x.txt", result)
    }
}
