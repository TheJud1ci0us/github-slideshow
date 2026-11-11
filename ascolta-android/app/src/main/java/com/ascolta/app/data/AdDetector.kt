package com.ascolta.app.data

/**
 * Finds the ads in a transcript from their wording (pure, unit-tested): promo codes, web
 * addresses, "rendez-vous sur", "-20 %", "sponsorisé"… Hosts such as Acast insert different
 * ads in every copy, so they can't be known in advance; the transcript of this copy can.
 *
 * A sentence with a strong sign (a promo code, a web address, "sponsored by") or two weaker
 * ones counts as an ad sentence; ad sentences less than [JOIN_MS] apart, with what is between
 * them, form an ad. A sentence in another language ([foreign]) is a weak sign (ads inserted
 * for the listener's country). Ads shorter than 8 s or longer than 3 min are ignored.
 */
object AdDetector {
    data class Ad(val startMs: Long, val endMs: Long)

    private const val JOIN_MS = 40_000L
    private const val MIN_MS = 8_000L
    private const val MAX_MS = 180_000L

    private val STRONG = listOf(
        Regex("""(?U)\b(code|codice|código|gutscheincode|rabattcode|cupom|cupón)\s+(promo|promocional|sconto|descuento)?\s*[A-Z0-9]{3,}"""),
        Regex("""(?U)\b(code promo|promo code|coupon code|discount code|codice sconto|código de descuento|código promocional|cupom de desconto|gutscheincode|rabattcode)\b""", RegexOption.IGNORE_CASE),
        Regex("""(?U)\b[\w-]+\s?(\.|point|dot|punto|ponto|punkt)\s?(com|fr|it|es|de|pt|io|co|net|org)\b(?!\w)""", RegexOption.IGNORE_CASE),
        Regex("""(?U)\b(sponsoris[ée]|sponsored by|sponsorizzat|patrocinad|gesponsert|en partenariat avec|in collaborazione con|brought to you by|cet épisode est (présenté|soutenu)|este episodio (está )?patrocinado)\b""", RegexOption.IGNORE_CASE),
    )

    private val WEAK = listOf(
        Regex("""(?U)\d+\s?(%|pour ?cent|percent|per ?cento|por ?ciento|prozent|por cento)""", RegexOption.IGNORE_CASE),
        Regex("""(?U)\b(rendez-vous sur|rendez vous sur|allez sur|visit|visitez|vai su|visita|besuche|acesse|téléchargez|download|scarica|descarga|lade|baixe)\b""", RegexOption.IGNORE_CASE),
        Regex("""(?U)\b(offre|offert|gratuit|essai|réduction|remise|livraison|abonnement|offer|free trial|discount|subscription|shipping|offerta|gratis|sconto|prova|oferta|descuento|envío|angebot|kostenlos|rabatt|versand|desconto|frete|grátis)\b""", RegexOption.IGNORE_CASE),
        Regex("""(?U)\b(application|appli|app)\b""", RegexOption.IGNORE_CASE),
        Regex("""(?U)\b(euros?|dollars?|€|\$)\b""", RegexOption.IGNORE_CASE),
    )

    /** How ad-like one sentence is: 2 = strong sign, 1 per weaker sign. */
    fun score(text: String, foreign: Boolean = false): Int {
        if (STRONG.any { it.containsMatchIn(text) }) return 2
        return WEAK.count { it.containsMatchIn(text) } + if (foreign) 1 else 0
    }

    /**
     * The ads among [segments] (start, end, text). [foreign] says whether a sentence is in
     * another language than the episode's.
     */
    fun detect(segments: List<Segment>, foreign: (String) -> Boolean = { false }): List<Ad> {
        val marked = segments.indices.filter { score(segments[it].text, foreign(segments[it].text)) >= 2 }
        if (marked.isEmpty()) return emptyList()
        val ads = mutableListOf<Ad>()
        var first = marked.first()
        var last = first
        var count = 1
        fun close() {
            val start = segments[first].startMs
            val end = segments[last].endMs
            // A single ad sentence is only an ad when it's a long one (a host read): otherwise
            // it's the show mentioning a website.
            val enough = count >= 2 || end - start >= 12_000
            if (enough && end - start in MIN_MS..MAX_MS) ads += Ad(start, end)
        }
        for (i in marked.drop(1)) {
            if (segments[i].startMs - segments[last].endMs <= JOIN_MS) {
                last = i; count++
            } else {
                close(); first = i; last = i; count = 1
            }
        }
        close()
        // Stretch each ad to the silence or music around it (no sentences there).
        return ads.map { ad ->
            val before = segments.lastOrNull { it.endMs <= ad.startMs }
            val after = segments.firstOrNull { it.startMs >= ad.endMs }
            Ad(
                if (before == null) 0 else maxOf(before.endMs, ad.startMs - 3_000).coerceAtMost(ad.startMs),
                after?.let { minOf(it.startMs, ad.endMs + 3_000).coerceAtLeast(ad.endMs) } ?: ad.endMs,
            )
        }
    }

    /** The ad playing at [positionMs], if any. */
    fun at(ads: List<Ad>, positionMs: Long): Ad? = ads.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 500 }
}
