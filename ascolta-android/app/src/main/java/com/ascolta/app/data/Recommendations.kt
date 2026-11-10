package com.ascolta.app.data

import com.ascolta.app.net.DirectoryResult
import com.ascolta.app.net.PodcastDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** A suggested podcast with its estimated level. */
data class Pick(val podcast: DirectoryResult, val level: LevelGuess.Estimate, val isCourse: Boolean = false)

/**
 * Podcast suggestions for a learner: courses for the language and the top podcasts of their
 * favourite themes in that language's store (English shows dropped), each with an estimated
 * CEFR level, those at the learner's level first. Used by onboarding, Personalize and Discover.
 */
object Recommendations {
    suspend fun picks(
        directory: PodcastDirectory,
        feedLanguage: com.ascolta.app.net.FeedLanguage,
        language: String,
        native: String,
        genres: List<Int>,
        band: LevelBand?,
        limit: Int = 30,
    ): List<Pick> = coroutineScope {
        val country = LANGUAGES.firstOrNull { it.code == language }?.country ?: "US"
        val wanted = genres.filter { it != GENRE_LEARNING }.take(4)
        val courses = async { runCatching { courses(directory, language, country) }.getOrDefault(emptyList()) }
        val themed = wanted.map { g ->
            async { g to runCatching { themed(directory, g, language, country, 25) }.getOrDefault(emptyList()) }
        }
        // No theme chosen: local podcasts found by the word "podcast" in the language (for
        // languages whose store charts are dominated by another language). With themes, the
        // themed lists already include those, limited to the theme.
        val local = LanguageCheck.PODCAST_WORD[language]?.takeIf { wanted.isEmpty() }?.let { word ->
            async { 0 to runCatching { directory.search(word, country) }.getOrDefault(emptyList()) }
        }
        val byGenre = themed.awaitAll() + listOfNotNull(local?.await())
        val candidates = merge(
            language = language,
            courses = courses.await(),
            byGenre = byGenre,
            band = band,
            limit = limit * 3,
            // Beginners mostly want courses; others mostly real podcasts.
            coursesFirst = band == LevelBand.BEGINNER || wanted.isEmpty(),
        )
        // Only podcasts really in the language (courses may also be in the learner's own).
        val declared = feedLanguage.ofAll(candidates.map { it.podcast.feedUrl })
        val found = verify(candidates, declared, language, native, feedLanguage::isDead).take(limit)
        if (found.size >= limit / 2) return@coroutineScope found
        // Backup: few found the main way (Hindi shows mostly have Latin-letter titles that the
        // charts don't surface), so search the store for the language's own name.
        val more = backupSources(directory, language, country).map { src -> async { runCatching { src() }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().distinctBy { it.feedUrl }.filter { r -> found.none { it.podcast.feedUrl == r.feedUrl } }.take(60)
        val extra = more.map { Pick(it, LevelGuess.estimate(it.title, it.summary, it.genreIds.firstOrNull(), it.genre)) }
        found + verify(extra, feedLanguage.ofAll(extra.map { it.podcast.feedUrl }), language, native, feedLanguage::isDead)
            .take(limit - found.size)
    }

    /**
     * The theme a typed search asks for ("news podcast", "notizie", "समाचार" → News), so its
     * chart in the learner's country can come first. [extraNames]: the themes' names in the
     * app's language. Null when the search isn't about a theme.
     */
    fun genreFor(query: String, extraNames: Map<Int, String> = emptyMap()): Int? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        val words = q.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()
        fun hit(k: String): Boolean {
            val key = k.lowercase()
            // Words in Latin letters must match a whole word; other alphabets anywhere in the text.
            return if (key.all { it.code < 0x250 || !it.isLetter() }) key in words || (key.contains(' ') && q.contains(key)) else q.contains(key)
        }
        extraNames.entries.firstOrNull { (_, name) -> hit(name) }?.let { return it.key }
        return THEME_WORDS.entries.firstOrNull { (_, keys) -> keys.any(::hit) }?.key
    }

    /** Words for each theme in many languages (Apple genre id → words). */
    private val THEME_WORDS: Map<Int, List<String>> = linkedMapOf(
        1488 to listOf("true crime", "crime", "crimine", "crimen", "krimi", "अपराध", "جنایی", "криминал", "犯罪", "범죄", "suç"),
        1489 to listOf("news", "notizie", "nouvelles", "actualités", "actualite", "noticias", "nachrichten", "notícias", "nieuws", "समाचार", "ख़बर", "खबर", "خبر", "اخبار", "новости", "ニュース", "뉴스", "新闻", "haber", "haberler", "wiadomości", "ახალი ამბები", "լուրեր"),
        1533 to listOf("science", "scienza", "scienze", "ciencia", "ciencias", "wissenschaft", "ciência", "wetenschap", "विज्ञान", "علم", "علمی", "наука", "科学", "과학", "bilim", "nauka", "მეცნიერება", "գիտություն"),
        1487 to listOf("history", "storia", "histoire", "historia", "geschichte", "história", "geschiedenis", "इतिहास", "تاریخ", "история", "歴史", "역사", "历史", "tarih", "ისტორია", "պատմություն"),
        1303 to listOf("comedy", "commedia", "comédie", "comedia", "humor", "humour", "humorismo", "कॉमेडी", "کمدی", "طنز", "комедия", "юмор", "お笑い", "코미디", "喜剧", "komedi"),
        1545 to listOf("sport", "sports", "deportes", "deporte", "esporte", "esportes", "खेल", "ورزش", "ورزشی", "спорт", "スポーツ", "스포츠", "体育", "spor"),
        1321 to listOf("business", "economia", "économie", "economía", "wirtschaft", "economie", "बिज़नेस", "व्यापार", "کسب و کار", "اقتصاد", "бизнес", "экономика", "ビジネス", "비즈니스", "商业", "iş", "ekonomi"),
        1512 to listOf("health", "salute", "santé", "salud", "gesundheit", "saúde", "gezondheid", "स्वास्थ्य", "سلامت", "здоровье", "健康", "건강", "sağlık"),
        1305 to listOf("kids", "children", "bambini", "enfants", "niños", "kinder", "crianças", "बच्चों", "कहानियां बच्चों", "کودک", "کودکان", "дети", "детей", "子ども", "子供", "어린이", "儿童", "çocuk"),
        1309 to listOf("film", "films", "movie", "movies", "cinema", "cinéma", "cine", "kino", "फ़िल्म", "फिल्म", "سینما", "فیلم", "кино", "映画", "영화", "电影", "sinema"),
        1310 to listOf("music", "musica", "musique", "música", "musik", "muziek", "संगीत", "موسیقی", "музыка", "音楽", "음악", "音乐", "müzik"),
        1318 to listOf("tech", "technology", "tecnologia", "technologie", "tecnología", "techniek", "टेक", "तकनीक", "تکنولوژی", "فناوری", "технологии", "テクノロジー", "기술", "科技", "teknoloji"),
        1483 to listOf("fiction", "stories", "story", "storie", "racconti", "histoires", "contes", "cuentos", "geschichten", "kahani", "कहानी", "कहानियां", "कहानियाँ", "داستان", "рассказы", "сказки", "物語", "이야기", "故事", "hikaye"),
        1301 to listOf("art", "arts", "arte", "kunst", "कला", "هنر", "искусство", "アート", "예술", "艺术", "sanat"),
        1324 to listOf("society", "culture", "società", "cultura", "société", "sociedad", "gesellschaft", "kultur", "समाज", "संस्कृति", "جامعه", "فرهنگ", "общество", "культура", "社会", "文化", "사회", "문화", "kültür"),
        1304 to listOf("education", "educazione", "éducation", "educación", "bildung", "educação", "शिक्षा", "آموزش", "образование", "教育", "교육", "eğitim"),
    )

    // ---- Sources for endless lists (PodcastPager) ----------------------------

    /** The language's names to search for: its own ("हिंदी", "فارسی") and in English ("Hindi"). */
    fun searchNames(language: String): List<String> =
        (LanguageCheck.ownNames(language) + languageNames(language)).distinct()

    /**
     * Backup searches: the language's names in its store and the US store, optionally limited
     * to shows passing [keep] (e.g. a theme).
     */
    fun backupSources(
        directory: PodcastDirectory,
        language: String,
        country: String,
        keep: (DirectoryResult) -> Boolean = { true },
    ): List<suspend () -> List<DirectoryResult>> =
        searchNames(language).flatMap { name ->
            listOf(country, "US").distinct().map { store -> suspend { directory.search(name, store, 200).filter(keep) } }
        }

    /** A theme: its chart, then shows found by the theme's name and the word "podcast", then the backup searches. */
    fun themeSources(directory: PodcastDirectory, genreId: Int, language: String, country: String): List<suspend () -> List<DirectoryResult>> {
        val chart = suspend { directory.topByGenre(genreId, country, 200) }
        val byName = suspend {
            genreName(directory.topByGenre(genreId, country, 200), genreId)
                ?.let { inTheme(directory.search(it, country, 200), genreId) }.orEmpty()
        }
        val byWord = suspend {
            LanguageCheck.PODCAST_WORD[language]?.let { inTheme(directory.search(it, country, 200), genreId) }.orEmpty()
        }
        return listOf(chart, byName, byWord) + backupSources(directory, language, country) { genreId in it.genreIds }
    }

    /** Shows for learners: one source per learner search ("slow Italian", "italiano facile"…), then the backup. */
    fun learnerSources(directory: PodcastDirectory, language: String, country: String): List<suspend () -> List<DirectoryResult>> {
        val names = languageNames(language)
        return learnerQueries(language, country).map { (term, store) ->
            suspend { directory.search(term, store, 100).filter { forLearners(it, names) } }
        }
    }

    /**
     * "For you": the favourite themes (charts first, round the themes), then shows for learners,
     * then the backup searches by the language's name and the word "podcast".
     */
    fun forYouSources(directory: PodcastDirectory, language: String, country: String, genres: List<Int>): List<suspend () -> List<DirectoryResult>> {
        val themes = genres.filter { it != GENRE_LEARNING }.ifEmpty { listOf(1324, 1489) } // Society & Culture, News
        val perTheme = themes.map { themeSources(directory, it, language, country).take(3) }
        val interleaved = (0 until 3).flatMap { i -> perTheme.mapNotNull { it.getOrNull(i) } }
        val word = LanguageCheck.PODCAST_WORD[language]?.let { w -> listOf(suspend { directory.search(w, country, 200) }) }.orEmpty()
        return interleaved + learnerSources(directory, language, country) + backupSources(directory, language, country) + word
    }

    /** The page check: feed still working and in the language (courses may be in the learner's own). */
    fun languageCheck(
        feedLanguage: com.ascolta.app.net.FeedLanguage,
        language: String,
        native: String,
        isCourse: Boolean,
    ): suspend (List<DirectoryResult>) -> List<DirectoryResult> = { batch ->
        val declared = feedLanguage.ofAll(batch.map { it.feedUrl })
        batch.filter { !feedLanguage.isDead(it.feedUrl) }
            .filter { LanguageCheck.keep(it.title, it.summary, declared[it.feedUrl], language, native, isCourse) }
            .map { it.copy(language = declared[it.feedUrl]) }
    }

    /** For typed searches: only feeds that still work (any language; the tag says which). */
    fun workingCheck(feedLanguage: com.ascolta.app.net.FeedLanguage): suspend (List<DirectoryResult>) -> List<DirectoryResult> = { batch ->
        val declared = feedLanguage.ofAll(batch.map { it.feedUrl })
        batch.filter { !feedLanguage.isDead(it.feedUrl) }.map { it.copy(language = declared[it.feedUrl]) }
    }

    /**
     * Podcasts of one theme in [country]'s store: Apple's chart for the theme, then searches for
     * the theme's own name in the store's language (e.g. 科学 for Science in Japan) and for the
     * word "podcast", keeping only shows Apple files under the theme. (Apple's search ignores
     * its genre filter, so a plain search for "podcast" used to bring marketing or comedy shows
     * into Science.)
     */
    suspend fun themed(directory: PodcastDirectory, genreId: Int, language: String, country: String, chartSize: Int = 50): List<DirectoryResult> =
        coroutineScope {
            val chart = runCatching { directory.topByGenre(genreId, country, chartSize) }.getOrDefault(emptyList())
            val terms = listOfNotNull(genreName(chart, genreId), LanguageCheck.PODCAST_WORD[language]).distinct()
            val found = terms.map { t -> async { runCatching { directory.search(t, country, 100) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
            (chart + inTheme(found, genreId)).distinctBy { it.feedUrl }
        }

    /** The theme's name in the store's language, read from a chart show whose main genre it is (pure). */
    fun genreName(chart: List<DirectoryResult>, genreId: Int): String? =
        chart.firstOrNull { it.genreIds.firstOrNull() == genreId }?.genre

    /** Only shows filed under [genreId] (as main genre, or a sub-genre of it) (pure). */
    fun inTheme(results: List<DirectoryResult>, genreId: Int): List<DirectoryResult> =
        results.filter { genreId in it.genreIds }

    /** Keeps the picks in the right language and records each one's declared language (pure). */
    fun verify(
        candidates: List<Pick>,
        declared: Map<String, String?>,
        language: String,
        native: String,
        isDead: (String) -> Boolean = { false },
    ): List<Pick> =
        candidates.mapNotNull { p ->
            if (isDead(p.podcast.feedUrl)) return@mapNotNull null // the feed no longer works
            val lang = declared[p.podcast.feedUrl]
            if (LanguageCheck.keep(p.podcast.title, p.podcast.summary, lang, language, native, p.isCourse)) {
                p.copy(podcast = p.podcast.copy(language = lang))
            } else null
        }

    /**
     * Podcasts for learners of [language] (beginner and intermediate): courses, slow and easy
     * shows, stories for learners. Found by learner phrases in English in the US store ("learn
     * Italian", "slow Italian"…) and in the language itself in its own store ("italiano facile",
     * "やさしい日本語"…). Shows must name the language and look made for learners.
     */
    suspend fun courses(directory: PodcastDirectory, language: String, country: String): List<DirectoryResult> = coroutineScope {
        val names = languageNames(language)
        val queries = learnerQueries(language, country)
        val found = queries.map { (term, store) -> async { runCatching { directory.search(term, store, 100) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().distinctBy { it.feedUrl }
        found.filter { r -> forLearners(r, names) }
    }

    /** The searches made for [language]'s learner shows: (term, store) pairs (pure). */
    fun learnerQueries(language: String, country: String): List<Pair<String, String>> {
        val names = languageNames(language)
        val english = names.last()
        val inEnglish = listOf("learn $english", "$english for beginners", "easy $english", "slow $english", "intermediate $english")
            .map { it to "US" }
        val inLanguage = (LEARNER_TERMS[language].orEmpty() + names.first()).distinct().map { it to country }
        return (inEnglish + inLanguage).distinct()
    }

    /**
     * A show for learners of the language named in [names] (pure): it names the language (not
     * "Learn Spanish" when learning Georgian) and looks like lessons, or is estimated below C1.
     */
    fun forLearners(r: DirectoryResult, names: List<String>): Boolean {
        val text = "${r.title} ${r.summary.orEmpty()}"
        if (names.none { n -> text.contains(n, ignoreCase = true) }) return false
        return LevelGuess.looksLikeCourse(text) ||
            LevelGuess.estimate(r.title, r.summary, r.genreIds.firstOrNull(), r.genre).band != LevelBand.ADVANCED
    }

    /**
     * Learner phrases in each language (what shows for learners call themselves): "easy",
     * "slow", "for foreigners", "beginner", "intermediate", "learn".
     */
    private val LEARNER_TERMS = mapOf(
        "it" to listOf("italiano facile", "italiano lento", "italiano per stranieri", "imparare l'italiano", "italiano intermedio"),
        "fr" to listOf("français facile", "français lent", "apprendre le français", "français langue étrangère", "français intermédiaire"),
        "es" to listOf("español fácil", "español lento", "aprender español", "español para extranjeros", "español intermedio"),
        "de" to listOf("einfaches Deutsch", "langsam gesprochen", "Deutsch lernen", "Deutsch für Anfänger", "Deutsch B1"),
        "pt" to listOf("português fácil", "aprender português", "português para estrangeiros", "português intermediário"),
        "nl" to listOf("makkelijk Nederlands", "Nederlands leren", "Nederlands voor beginners"),
        "ja" to listOf("やさしい日本語", "日本語 初級", "日本語 中級", "日本語学習", "にほんご"),
        "zh" to listOf("慢速中文", "中文 初级", "学中文", "汉语 中级"),
        "ko" to listOf("한국어 초급", "한국어 배우기", "쉬운 한국어", "한국어 중급"),
        "ru" to listOf("русский язык для иностранцев", "русский для начинающих", "учим русский", "медленный русский"),
        "uk" to listOf("українська для початківців", "вивчати українську", "українська мова для іноземців"),
        "tr" to listOf("kolay Türkçe", "Türkçe öğren", "yabancılar için Türkçe"),
        "pl" to listOf("polski dla obcokrajowców", "nauka polskiego", "łatwy polski"),
        "sv" to listOf("lätt svenska", "lär dig svenska", "svenska för nybörjare"),
        "el" to listOf("εύκολα ελληνικά", "μάθε ελληνικά"),
        "ar" to listOf("تعلم العربية", "عربي سهل"),
        "he" to listOf("עברית קלה", "ללמוד עברית"),
        "hi" to listOf("हिंदी सीखें", "आसान हिंदी"),
        "ka" to listOf("ქართული ენა", "ქართულის სწავლა"),
    )

    fun languageNames(language: String): List<String> {
        val locale = Locale.forLanguageTag(language)
        return listOf(locale.getDisplayLanguage(locale), locale.getDisplayLanguage(Locale.ENGLISH)).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Combines courses and themed charts (pure, unit-tested): English shows dropped for other
     * languages, duplicates removed, themes interleaved, podcasts at the learner's level first.
     */
    fun merge(
        language: String,
        courses: List<DirectoryResult>,
        byGenre: List<Pair<Int, List<DirectoryResult>>>,
        band: LevelBand?,
        limit: Int,
        coursesFirst: Boolean,
    ): List<Pick> {
        val coursePicks = courses.map { Pick(it, LevelGuess.estimate(it.title, it.summary, GENRE_LEARNING, it.genre), isCourse = true) }
        // Round-robin across themes so one theme doesn't fill the list.
        val lists = byGenre.map { (g, list) ->
            list.filter { language == "en" || !LevelGuess.looksEnglish("${it.title} ${it.summary.orEmpty()}") }
                .map { Pick(it, LevelGuess.estimate(it.title, it.summary, g, it.genre)) }
        }
        val themed = mutableListOf<Pick>()
        var i = 0
        while (lists.any { i < it.size }) {
            lists.forEach { l -> l.getOrNull(i)?.let(themed::add) }
            i++
        }
        val ordered = if (coursesFirst) coursePicks + themed else themed + coursePicks
        val unique = ordered.distinctBy { it.podcast.feedUrl }
        val sorted = if (band == null) unique else unique.sortedBy { if (it.level.band == band) 0 else 1 } // stable
        return sorted.take(limit)
    }

    /** Apple's top-level "Podcasts" genre, listed in every show's genres. */
    private const val GENRE_ALL = 26

    /**
     * The themes to look in for shows like [podcast]: its own main genre first, then the
     * broader ones it belongs to (e.g. Natural Sciences, then Science) (pure).
     */
    fun themesOf(podcast: DirectoryResult): List<Int> = podcast.genreIds.filter { it != GENRE_ALL }.distinct()
}

/**
 * "More like this": each podcast picked during setup brings a few similar ones (same theme,
 * same language), as many times as you like, until the store runs out. Candidates are fetched
 * once per theme and checked for language a few at a time.
 */
class MoreLikeThis(
    private val directory: PodcastDirectory,
    private val feedLanguage: com.ascolta.app.net.FeedLanguage,
    private val language: String,
    private val native: String,
) {
    private val country = LANGUAGES.firstOrNull { it.code == language }?.country ?: "US"
    private val pools = mutableMapOf<Int, ArrayDeque<DirectoryResult>>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Up to [count] podcasts like [podcast] in the language, none of them in [exclude]. */
    suspend fun next(podcast: DirectoryResult, exclude: Set<String>, count: Int = 3): List<Pick> = lock.withLock {
        val out = mutableListOf<Pick>()
        for (g in Recommendations.themesOf(podcast)) {
            val pool = pools.getOrPut(g) {
                ArrayDeque(runCatching { Recommendations.themed(directory, g, language, country, 100) }.getOrDefault(emptyList()))
            }
            while (out.size < count && pool.isNotEmpty()) {
                // Check languages a handful at a time (each one reads the start of a feed).
                val batch = generateSequence { pool.removeFirstOrNull() }
                    .filter { it.feedUrl !in exclude && out.none { o -> o.podcast.feedUrl == it.feedUrl } }
                    .take(count * 3).toList()
                if (batch.isEmpty()) break
                val declared = feedLanguage.ofAll(batch.map { it.feedUrl })
                val picks = batch.map { Pick(it, LevelGuess.estimate(it.title, it.summary, g, it.genre)) }
                val kept = Recommendations.verify(picks, declared, language, native, feedLanguage::isDead)
                // Put back what wasn't needed this time, for the next tap.
                val take = kept.take(count - out.size)
                out += take
                kept.drop(take.size).asReversed().forEach { pool.addFirst(it.podcast) }
            }
            if (out.size >= count) break
        }
        out
    }
}
