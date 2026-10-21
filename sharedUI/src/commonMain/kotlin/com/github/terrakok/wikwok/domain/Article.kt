package com.github.terrakok.wikwok.domain

import androidx.compose.runtime.Immutable

/**
 * One Wikipedia article as the feed sees it.
 *
 * The model is intentionally flat. It carries identity, the fields the card renders and the fields
 * the engine ranks on ([categories] and, indirectly, the graph edges exposed by [neighbours]), so it
 * survives a network round trip and a persistence layer without a second mapping step.
 *
 * @property id stable identifier formatted as language:pageId.
 * @property title display title, already localised by the API.
 * @property categories every category the article belongs to. May be empty.
 * @property extract plain text summary. May be empty when the API returns no introduction.
 * @property imageUrl lead image URL, or null when the article has no thumbnail. Off-screen sizes
 *   are not requested: the card crops whatever the API offers.
 * @property isPopular true when the article appears in the day's most viewed ranking. The engine
 *   reads it as an authority signal: a landmark article is a better next step than an obscure
 *   neighbour, even when both match the reader's interests.
 * @property neighboursProvider lazy edge source. Kept as a provider instead of an eager list so a
 * cyclic graph can be built without allocating the whole component up front.
 *
 * The class is annotated [Immutable] on purpose. Every property is a val and the provider is a
 * final function reference, so an instance never changes after construction. Without the
 * annotation the compiler treats the type as unstable: a function-typed constructor parameter has
 * no equality the compose runtime can trust, and an unstable parameter makes every composable that
 * receives an article impossible to skip. That is the difference between a like tap repainting one
 * button and re-running every card on screen.
 */
@Immutable
data class Article(
    val id: String,
    val title: String,
    val categories: List<String>,
    val extract: String = "",
    val imageUrl: String? = null,
    val isPopular: Boolean = false,
    private val neighboursProvider: () -> List<Article> = { emptyList() },
) {

    /**
     * Time the reader needs to finish the text this card actually shows, in milliseconds.
     *
     * A duration means nothing on its own: eight seconds is a finished card when the extract is 150
     * characters and a fast swipe when it is 900. The estimate converts a stopwatch reading into a
     * fraction of the card, which is what the engine compares against.
     *
     * The character count is clamped at both ends on purpose. At the top because the card renders at
     * most five lines of extract, roughly [MAX_VISIBLE_CHARS] characters on a phone: a 6,000
     * character extract does not ask for four minutes of attention, and without the ceiling a long
     * article would quietly stop producing dwell signals altogether. At the bottom because a short
     * stub is still a card the reader has to parse, so it is never assumed readable in two seconds.
     */
    val expectedReadMs: Long
        get() = extract.length
            .coerceIn(MIN_VISIBLE_CHARS, MAX_VISIBLE_CHARS)
            .toLong() * MILLIS_PER_SECOND / CHARS_PER_SECOND

    /**
     * Articles reachable from this one through internal links.
     *
     * The engine treats this as the candidate set for the random walk. An empty list is a legal
     * answer and means the article is a dead end, which the engine handles explicitly.
     */
    fun neighbours(): List<Article> = neighboursProvider()

    /**
     * Returns a copy carrying [provider] as the neighbourhood.
     *
     * Used by the data layer to attach edges it has already resolved, so the engine never has to
     * hit the network while a swipe is in flight. The provider stays lazy and is evaluated on each
     * [neighbours] call.
     */
    fun withNeighbours(provider: () -> List<Article>): Article = copy(neighboursProvider = provider)

    /**
     * Hand-written because the generated version would recurse through [neighboursProvider] on a
     * cyclic graph.
     */
    override fun toString(): String =
        "Article(id=$id, title=$title, categories=$categories)"

    companion object {

        /** Reading speed the estimate assumes: 15 characters per second, about 200 words a minute. */
        const val CHARS_PER_SECOND: Int = 15

        /** Shortest extract the estimate will assume is on screen. */
        const val MIN_VISIBLE_CHARS: Int = 240

        /**
         * Longest extract the estimate will assume is on screen.
         *
         * Five lines of body text on a phone is roughly this many characters. Past it the estimate
         * saturates, because the card does not show more.
         */
        const val MAX_VISIBLE_CHARS: Int = 450

        /** Estimate for the shortest readable card, used when a caller does not know the article. */
        val DEFAULT_EXPECTED_READ_MS: Long = MIN_VISIBLE_CHARS.toLong() * MILLIS_PER_SECOND / CHARS_PER_SECOND
    }
}

/** Milliseconds in one second, named so the reading estimate reads as arithmetic instead of magic. */
private const val MILLIS_PER_SECOND: Long = 1_000L
