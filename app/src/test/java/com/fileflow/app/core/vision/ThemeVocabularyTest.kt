package com.fileflow.app.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table is the search, so the table is what gets tested.
 *
 * Three things have to hold: every theme names only labels the model can
 * actually emit (otherwise the query can never match), the words people type in
 * Indonesian reach the right English labels, and the score puts the photo with
 * the words in it above the photo that merely looks similar.
 */
class ThemeVocabularyTest {

    @Test
    fun `every theme names a label the model has`() {
        // The failure this catches is a silent one: a theme pointing at a label
        // the model never emits, which is a search that returns nothing forever.
        val unknown = ThemeVocabulary.themeLabels.filterNot { it in ThemeVocabulary.KNOWN_LABELS }
        assertTrue("themes naming labels the model does not have: $unknown", unknown.isEmpty())
    }

    @Test
    fun `the known label list is the model's, not a stub`() {
        // A trimmed list would make the check above pass for the wrong reason.
        assertTrue("expected 400+ labels, got ${ThemeVocabulary.KNOWN_LABELS.size}", ThemeVocabulary.KNOWN_LABELS.size >= 400)
        listOf("receipt", "team", "community", "screenshot", "money", "crowd", "construction")
            .forEach { assertTrue("$it missing from the model list", it in ThemeVocabulary.KNOWN_LABELS) }
    }

    @Test
    fun `bukti transfer finds receipt photos`() {
        val labels = ThemeVocabulary.labelsFor("bukti transfer")
        assertTrue("expected receipt in $labels", "receipt" in labels)
        assertTrue("expected money in $labels", "money" in labels)
    }

    @Test
    fun `kerja bakti finds the labels for working together`() {
        val labels = ThemeVocabulary.labelsFor("kerja bakti")
        assertTrue("expected team in $labels", "team" in labels)
        assertTrue("expected community in $labels", "community" in labels)
        assertTrue("expected construction in $labels", "construction" in labels)
    }

    @Test
    fun `a phrase with extra words still matches`() {
        // People type sentences, not table keys.
        val labels = ThemeVocabulary.labelsFor("foto kerja bakti di sekolah")
        assertTrue("expected team in $labels", "team" in labels)
        assertTrue("expected school in $labels", "school" in labels)
    }

    @Test
    fun `a label typed in english still works`() {
        assertEquals(listOf("receipt"), ThemeVocabulary.labelsFor("receipt"))
        assertEquals(listOf("cake"), ThemeVocabulary.labelsFor("Cake"))
    }

    @Test
    fun `a word is not a substring of another word`() {
        // "malam" contains "alam", and an earlier version of this matched on
        // substrings — so a search for a night photo returned nature photos.
        assertTrue(ThemeVocabulary.labelsFor("malam").isEmpty())
        assertTrue("alam should still match", ThemeVocabulary.labelsFor("alam").isNotEmpty())
    }

    @Test
    fun `an unknown query matches nothing rather than everything`() {
        assertTrue(ThemeVocabulary.labelsFor("zeppelin terbang malam").isEmpty())
        assertTrue(ThemeVocabulary.labelsFor("").isEmpty())
    }

    @Test
    fun `themes for a label read back as the words a person would type`() {
        val themes = ThemeVocabulary.themesFor(listOf("receipt", "money"))
        assertTrue("expected bukti transfer in $themes", "bukti transfer" in themes)
        assertTrue("expected uang in $themes", "uang" in themes)
    }

    @Test
    fun `display names are indonesian where we have them and readable where we do not`() {
        assertEquals(listOf("Struk / bukti"), ThemeVocabulary.display(listOf("receipt")))
        assertEquals(listOf("Bento"), ThemeVocabulary.display(listOf("bento")))
        assertFalse(ThemeVocabulary.display(listOf("bento")).first().contains("bento"))
    }
}
