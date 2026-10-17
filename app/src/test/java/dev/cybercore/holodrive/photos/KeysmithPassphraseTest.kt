package dev.cybercore.holodrive.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeysmithPassphraseTest {
    private val words = (0 until 7776).map { "word$it" }

    @Test fun contractProducesRequestedWordsWithStableSeparator() {
        var next = 0
        val phrase = KeysmithPassphrase.generateFromWords(words, 4) { bound -> (next++ % bound) }
        assertEquals("word0-word1-word2-word3", phrase)
    }

    @Test fun contractRejectsInvalidCountsAndWordlists() {
        assertThrows(IllegalArgumentException::class.java) {
            KeysmithPassphrase.generateFromWords(words, 2) { 0 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeysmithPassphrase.generateFromWords(words.dropLast(1), 6) { 0 }
        }
    }

    @Test fun contractRejectsOutOfRangeRandomIndexes() {
        assertThrows(IllegalStateException::class.java) {
            KeysmithPassphrase.generateFromWords(words, 3) { it }
        }
    }
}
