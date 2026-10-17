package dev.cybercore.holodrive.photos

import android.content.Context
import java.security.SecureRandom

/** Local implementation of Cybercore Keysmith's passphrase/v1 contract. */
object KeysmithPassphrase {
    const val API_VERSION = "passphrase/v1"
    private const val WORD_COUNT = 7776
    private val secureRandom = SecureRandom()
    @Volatile private var cachedWords: List<String>? = null

    fun generate(context: Context, count: Int = 6): String {
        require(count in 3..12) { "Choose between 3 and 12 words." }
        val words = cachedWords ?: synchronized(this) {
            cachedWords ?: context.applicationContext.assets.open("keysmith/eff-large.txt")
                .bufferedReader(Charsets.UTF_8).use { it.readLines().filter(String::isNotEmpty) }
                .also { loaded ->
                    check(loaded.size == WORD_COUNT && loaded.toSet().size == WORD_COUNT) {
                        "Keysmith wordlist failed its integrity check."
                    }
                    cachedWords = loaded
                }
        }
        return generateFromWords(words, count) { bound -> secureRandom.nextInt(bound) }
    }

    internal fun generateFromWords(words: List<String>, count: Int, chooseIndex: (Int) -> Int): String {
        require(count in 3..12) { "Choose between 3 and 12 words." }
        require(words.size == WORD_COUNT && words.none(String::isBlank) && words.toSet().size == WORD_COUNT) {
            "Keysmith wordlist failed its integrity check."
        }
        return List(count) {
            val index = chooseIndex(words.size)
            check(index in words.indices) { "Keysmith random source returned an invalid index." }
            words[index]
        }.joinToString("-")
    }
}
