package com.github.terrakok.wikwok.domain

/**
 * One Wikipedia article as the feed sees it.
 *
 * The model is intentionally flat. It carries identity, the two fields the engine ranks on
 * ([categories] and, indirectly, the graph edges exposed by [neighbours]) and nothing else, so it
 * survives a network round trip and a persistence layer without a second mapping step.
 *
 * @property id stable identifier formatted as `language:pageId`.
 * @property title display title, already localised by the API.
 * @property categories every category the article belongs to. May be empty.
 * @property neighboursProvider lazy edge source. Kept as a provider instead of an eager list so a
 * cyclic graph can be built without allocating the whole component up front.
 */
data class Article(
    val id: String,
    val title: String,
    val categories: List<String>,
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
    fun withNeighbours(provider: () -> List<Article>): Article =
        Article(id = id, title = title, categories = categories, neighboursProvider = provider)

    /**
     * Hand-written because the generated version would recurse through [neighboursProvider] on a
     * cyclic graph.
     */
    override fun toString(): String =
        "Article(id=$id, title=$title, categories=$categories)"
}
