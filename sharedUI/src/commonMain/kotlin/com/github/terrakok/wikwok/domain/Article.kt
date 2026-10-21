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
}
