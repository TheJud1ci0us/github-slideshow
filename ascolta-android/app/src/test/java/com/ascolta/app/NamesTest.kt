package com.ascolta.app

import com.ascolta.app.data.Names
import com.ascolta.app.data.Segment
import com.ascolta.app.data.Word
import com.ascolta.app.data.applyEdits
import com.ascolta.app.data.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {
    private val notesFr = """
        <p>Dans cet épisode, Pamela Ferguson revient sur la visite de Volodymyr Zelensky à Lyon.
        Avec la participation de l'université de Dundee. Retrouvez-nous sur https://exemple.fr/Podcast et
        écrivez à contact@exemple.fr. Musique : Bensound.</p>
        <p>Le Monde, le 3 Mars. Lyon accueille aussi Zelensky.</p>
    """.trimIndent()

    @Test
    fun namesComeFromNotesNotFromSentenceStartsLinksOrCommonWords() {
        val names = Names.extract(listOf("Le journal du jour", "Zelensky à Lyon", notesFr))
        assertTrue(names.containsAll(listOf("Zelensky", "Lyon", "Ferguson", "Pamela", "Volodymyr", "Dundee", "Bensound")))
        // "Dans" starts a sentence, "Mars" is a month, the link and e-mail are removed.
        assertFalse(names.any { it.equals("Dans", true) || it == "Mars" || it.contains("exemple") || it == "Podcast" })
        // Most frequent first.
        assertTrue(names.indexOf("Zelensky") < names.indexOf("Dundee"))
    }

    @Test
    fun wordsAlsoWrittenInLowercaseAreNotNamesAndTheLearnersListComesFirst() {
        val names = Names.extract(listOf("Sur la Route", "on prend la route vers Rome"), extra = listOf("Anna Rossi", ""))
        assertEquals("Anna Rossi", names.first())
        assertTrue("Rome" in names)
        assertFalse("Route" in names)
    }

    @Test
    fun languagesWithoutCapitalsGiveOnlyTheLearnersNames() {
        assertEquals(listOf("山田"), Names.extract(listOf("今日は東京で山田さんに会いました。"), extra = listOf("山田")))
    }

    @Test
    fun correctionFixesNearMissesOnly() {
        val names = listOf("Volodymyr Zelensky", "Lyon", "Ferguson")
        val (text, fixed) = Names.correct("Selensky est arrivé à Lion, dit Fergusson. Le lion dort.", names)
        assertEquals("Zelensky est arrivé à Lion, dit Ferguson. Le lion dort.", text) // "Lion": too short to guess
        assertEquals(mapOf("Selensky" to "Zelensky", "Fergusson" to "Ferguson"), fixed)
        // Ordinary capitalised words and other forms of the same word are left alone.
        assertEquals("Kanzlers Rede", Names.correct("Kanzlers Rede", listOf("Kanzler")).first)
        assertEquals("Cette Ferguson", Names.correct("Cette Ferguson", listOf("Ferguson")).first)
        // Accents: same letters, the name's spelling.
        assertEquals("Ménard parle", Names.correct("Menard parle", listOf("Ménard")).first)
        // Lowercase words are never touched.
        assertEquals("fergusson", Names.correct("fergusson", listOf("Ferguson")).first)
    }

    @Test
    fun whisperPromptStaysShortAndEndsWithThePreviousSentences() {
        val many = (1..200).map { "Personne$it" }
        val p = Names.prompt(many, "La phrase d'avant.")!!
        assertTrue(p.length <= 430)
        assertTrue(p.startsWith("Personne1, Personne2"))
        assertTrue(p.endsWith("La phrase d'avant."))
        assertEquals("La phrase d'avant.", Names.prompt(emptyList(), "La phrase d'avant."))
        assertEquals(null, Names.prompt(emptyList(), null))
    }

    @Test
    fun hotwordsIncludeTheNamesAndTheirParts() {
        assertEquals("Volodymyr Zelensky/Volodymyr/Zelensky/Lyon", Names.hotwords(listOf("Volodymyr Zelensky", "Lyon", "lyon")))
    }

    @Test
    fun whisperRepeatingItsHintIsRecognised() {
        val names = listOf("Volodymyr Zelensky", "Lyon", "Ferguson")
        assertTrue(Names.echoesPrompt("Volodymyr Zelensky, Lyon, Ferguson.", names))
        assertFalse(Names.echoesPrompt("Zelensky est arrivé à Lyon hier soir.", names))
        assertFalse(Names.echoesPrompt("Zelensky.", names))
        // Ordinary short sentences are never dropped, even made of words found in the notes.
        assertFalse(Names.echoesPrompt("Sa femme.", listOf("Femme", "Sa", "Enfer")))
        assertFalse(Names.echoesPrompt("Zelensky Lyon Ferguson", names))
    }

    @Test
    fun titlesInCapitalsGiveNoNames() {
        val title = "FEMME TRANSGENRE : ELLE REGRETTE SA TRANSITION ET VIT UN ENFER DEPUIS SON CHANGEMENT DE S*XE"
        val notes = "Guillaume Pley reçoit Lou, qui raconte sa transition. Avec le soutien de la SNCF."
        val names = Names.extract(listOf("Legend", title, notes))
        listOf("FEMME", "SA", "VIT", "ENFER", "SON", "UN", "ELLE").forEach { assertFalse(it, it in names) }
        assertTrue(names.containsAll(listOf("Pley", "Lou", "SNCF")))
        // "Femme" at the start of a sentence stays as it is.
        assertEquals("Femme et mari.", Names.correct("Femme et mari.", names).first)
    }

    @Test
    fun aConversationFullOfSlipsIsRedoneWhole() {
        assertTrue(com.ascolta.app.work.TranscriptionPlan.redoWholeBlock(wrong = 4, total = 15))
        assertFalse(com.ascolta.app.work.TranscriptionPlan.redoWholeBlock(wrong = 2, total = 15))
        assertFalse(com.ascolta.app.work.TranscriptionPlan.redoWholeBlock(wrong = 1, total = 2))
    }

    @Test
    fun editsReplaceTheSentenceAndKeepWordTimesWhenTheyCan() {
        val s = Segment(1_000, 3_000, "Selensky est là", "Selensky is here",
            listOf(Word(1_000, "Selensky"), Word(1_800, "est"), Word(2_200, "là")))
        val t = Transcript("fr", Transcript.SOURCE_WHISPER, listOf(s))
        val same = applyEdits(t, mapOf(1_000L to "Zelensky est là")).segments.single()
        assertEquals("Zelensky est là", same.text)
        assertEquals(null, same.translation) // translated again
        assertEquals(listOf(1_000L, 1_800L, 2_200L), same.words!!.map { it.startMs })
        assertEquals("Zelensky", same.words!!.first().text)
        val longer = applyEdits(t, mapOf(1_000L to "Volodymyr Zelensky est là")).segments.single()
        assertEquals(4, longer.words!!.size)
        assertEquals(listOf(1_000L, 1_500L, 2_000L, 2_500L), longer.words!!.map { it.startMs })
        assertEquals(t, applyEdits(t, mapOf(9_999L to "ailleurs")))
    }
}
