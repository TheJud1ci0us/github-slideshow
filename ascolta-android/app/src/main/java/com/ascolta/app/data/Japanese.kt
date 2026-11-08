package com.ascolta.app.data

import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How Japanese transcripts are shown. */
enum class JapaneseScript(val key: String) {
    /** As written (kanji and kana). */
    KANJI("kanji"),
    /** Kanji with their reading in small hiragana above (furigana). */
    FURIGANA("furigana"),
    /** Latin letters (Hepburn romaji) instead of Japanese script. */
    ROMAJI("romaji");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: FURIGANA
    }
}

/**
 * A piece of a Japanese sentence: [surface] as written at [start] in the text, its [reading]
 * in hiragana and in [romaji]. [isWord] is false for punctuation and spaces.
 */
data class JaToken(
    val start: Int,
    val surface: String,
    val reading: String,
    val romaji: String,
    val isWord: Boolean,
) {
    val end: Int get() = start + surface.length
    /** True if the written form contains kanji, i.e. needs furigana. */
    val hasKanji: Boolean get() = surface.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
}

/**
 * Japanese readings, offline: the Kuromoji morphological analyzer (IPADIC dictionary) splits
 * a sentence into words and gives each its reading, from which furigana and romaji are made.
 * The dictionary takes a second or two to load, so it is loaded once, on first use.
 */
object Japanese {
    @Volatile private var tokenizer: Tokenizer? = null
    private val loading = Mutex()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    suspend fun load() = withContext(Dispatchers.Default) {
        loading.withLock {
            if (tokenizer == null) {
                tokenizer = Tokenizer()
                _ready.value = true
            }
        }
    }

    /** Tokens of [text], or null while the dictionary isn't loaded yet. */
    fun tokens(text: String): List<JaToken>? {
        val t = tokenizer ?: return null
        return t.tokenize(text).map { tok ->
            val surface = tok.surface
            val katakana = tok.reading.takeIf { it != "*" && it.isNotBlank() } ?: surface
            val particle = tok.partOfSpeechLevel1 == "助詞"
            val isWord = surface.any { it.isLetterOrDigit() }
            JaToken(
                start = tok.position,
                surface = surface,
                reading = toHiragana(katakana),
                romaji = if (isWord) Romaji.of(katakana, particle) else surface,
                isWord = isWord,
            )
        }
    }

    /** "てんき" for "テンキ" (other characters unchanged). */
    fun toHiragana(text: String): String = buildString {
        for (c in text) append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
    }

    /** The reading of a whole word or phrase, e.g. for a saved word: "天気" → "てんき". */
    fun reading(text: String): String? = tokens(text)?.joinToString("") { it.reading }?.takeIf { it != text }

    fun romaji(text: String): String? = tokens(text)?.joinToString(" ") { it.romaji }
}

/** Kana → Hepburn romaji (as in textbooks: shi, chi, tsu, fu, ji; long vowels written out). */
object Romaji {
    private val digraphs = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo", "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo", "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo", "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo", "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        "シェ" to "she", "ジェ" to "je", "チェ" to "che", "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du",
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo", "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
        "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo", "ツァ" to "tsa", "ツォ" to "tso",
    )
    private val single = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヰ' to "i", 'ヱ' to "e", 'ヲ' to "o", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'ヴ' to "vu", 'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo", 'ヮ' to "wa",
    )

    /** Romaji for a katakana (or hiragana) reading; [particle] reads は/へ/を as wa/e/o. */
    fun of(reading: String, particle: Boolean = false): String {
        val kata = buildString { for (c in reading) append(if (c in 'ぁ'..'ゖ') (c + 0x60) else c) }
        if (particle) when (kata) {
            "ハ" -> return "wa"
            "ヘ" -> return "e"
            "ヲ" -> return "o"
        }
        val out = StringBuilder()
        var i = 0
        var doubleNext = false
        while (i < kata.length) {
            val c = kata[i]
            if (c == 'ッ') { doubleNext = true; i++; continue }
            if (c == 'ー') { out.lastOrNull()?.takeIf { it in "aeiou" }?.let { out.append(it) }; i++; continue }
            val pair = if (i + 1 < kata.length) digraphs[kata.substring(i, i + 2)] else null
            val syllable = pair ?: single[c] ?: c.toString()
            i += if (pair != null) 2 else 1
            if (doubleNext) {
                // っ doubles the next consonant (っち → tchi).
                out.append(if (syllable.startsWith("ch")) "t" else syllable.firstOrNull()?.takeIf { it !in "aeiou" }?.toString().orEmpty())
                doubleNext = false
            }
            // ん before a vowel or y is written n' (kin'en), so it isn't misread.
            if (out.endsWith("n") && i - (if (pair != null) 2 else 1) > 0 && kata[i - (if (pair != null) 2 else 1) - 1] == 'ン' &&
                (syllable.first() in "aeiouy")
            ) out.append('\'')
            out.append(syllable)
        }
        return out.toString()
    }
}
