package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.Article
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Failure surfaced by the Wikipedia data layer.
 *
 * A single exception type keeps the [Result] contract predictable: callers either treat every
 * failure the same way or inspect the message, and no platform exception leaks through.
 */
class WikipediaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * [WikipediaRepository] backed by the MediaWiki Action API.
 *
 * The class owns four concerns and nothing else:
 * - request shaping (which parameters produce a usable payload);
 * - an in-memory cache for articles and for already resolved neighbourhoods, so a feed that
 *   revisits an article does not pay for a second round trip;
 * - a two-step neighbourhood lookup: links first, then one batched request for the selected five;
 * - translation of transport and API failures into [Result.failure].
 *
 * The caches are guarded by a [Mutex] rather than left bare: the feed issues overlapping requests
 * from several coroutines, and an unguarded [MutableMap] corrupts under concurrent writes on the
 * native targets.
 *
 * @param client HTTP client, injected so tests can supply a mock engine.
 * @param random source of the shuffle that picks neighbours. Injected so a test can pin the order.
 */
class WikipediaRepositoryImpl(
    private val client: HttpClient = createWikiHttpClient(),
    private val random: Random = Random.Default,
) : WikipediaRepository {

    private val articleCache: MutableMap<String, Article> = mutableMapOf()

    private val relatedCache: MutableMap<String, List<Article>> = mutableMapOf()

    private val cacheMutex: Mutex = Mutex()

    /**
     * Loads an article and attaches its neighbourhood in the same call.
     *
     * Neighbour resolution is best effort. A feed entry with no edges is still readable, so a
     * failure on the second or third request degrades the graph instead of failing the article.
     */
    override suspend fun getArticle(language: String, id: String): Result<Article> = runCatching {
        val key = cacheKey(language, id)
        cacheMutex.withLock { articleCache[key] }?.let { cached -> return@runCatching cached }

        val page = requestPages(language, id).firstPage()
            ?: throw WikipediaException(describeMissing(id))

        val base = page.toArticle(language) ?: throw WikipediaException("Page for $id has no id")

        val neighbours = runCatching { relatedOf(language, base.id) }.getOrDefault(emptyList())
        val article = base.withNeighbours { neighbours }

        cacheMutex.withLock {
            articleCache[key] = article
            articleCache[article.id] = article
        }

        article
    }

    override suspend fun getRelatedArticles(language: String, currentArticleId: String): Result<List<Article>> =
        runCatching { relatedOf(language, currentArticleId) }

    /**
     * Seeds the feed with an arbitrary article.
     *
     * A single request: the random generator feeds the same property set the feed renders, so the
     * article and its metadata arrive together. The neighbourhood is resolved afterwards, exactly
     * as in [getArticle], because the engine needs edges before the first swipe.
     */
    override suspend fun getRandomArticle(language: String): Result<Article> = runCatching {
        val page = queryPages(language) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", 2)
            parameter("generator", "random")
            parameter("grnnamespace", 0)
            parameter("grnlimit", 1)
            parameter("prop", "extracts|categories|pageimages")
            parameter("pithumbsize", THUMBNAIL_WIDTH)
            parameter("exintro", true)
            parameter("explaintext", true)
            parameter("exlimit", "max")
        }.firstPage() ?: throw WikipediaException("Random lookup returned no page")

        val base = page.toArticle(language) ?: throw WikipediaException("Random page has no id")

        val neighbours = runCatching { relatedOf(language, base.id) }.getOrDefault(emptyList())

        base.withNeighbours { neighbours }
    }

    /**
     * Resolves the neighbourhood of one article.
     *
     * Two requests, never more: the link list of the current page, then a single batched request
     * that expands the sampled titles. The sample exists to keep the feed unpredictable; the batch
     * exists because asking for five articles one by one is five round trips and a rate-limit risk.
     */
    private suspend fun relatedOf(language: String, id: String): List<Article> {
        val key = cacheKey(language, id)
        cacheMutex.withLock { relatedCache[key] }?.let { cached -> return cached }

        val links = fetchLinkTitles(language, id)
        val sample = links.take(LINK_SAMPLE_SIZE)
            .shuffled(random)
            .take(WikipediaRepository.MAX_RELATED_ARTICLES)
        val related = if (sample.isEmpty()) emptyList() else fetchArticles(language, sample)

        cacheMutex.withLock {
            relatedCache[key] = related
            related.forEach { article -> articleCache[article.id] = article }
        }

        return related
    }

    /**
     * Internal links of a page, restricted to namespace 0.
     *
     * Only the first response page is used. [PL_LIMIT] already returns up to five hundred links,
     * far more than the fifteen that enter the shuffle, so following the continuation cursor would
     * cost requests without changing the outcome.
     */
    private suspend fun fetchLinkTitles(language: String, id: String): List<String> =
        queryPages(language) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", 2)
            parameter("prop", "links")
            parameter("plnamespace", 0)
            parameter("pllimit", PL_LIMIT)
            parameter("redirects", true)
            target(id)
        }.firstPage()?.articleLinkTitles().orEmpty()

    /**
     * Expands several titles in one request.
     *
     * The titles are joined with a pipe, which is the API's list separator. A title can never
     * contain that character, so no escaping is needed.
     */
    private suspend fun fetchArticles(language: String, titles: List<String>): List<Article> =
        queryPages(language) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", 2)
            parameter("prop", "extracts|categories|pageimages")
            parameter("pithumbsize", THUMBNAIL_WIDTH)
            parameter("exintro", true)
            parameter("explaintext", true)
            parameter("exlimit", "max")
            parameter("titles", titles.joinToString("|"))
        }.query?.pages.orEmpty().mapNotNull { page -> page.toArticle(language) }

    /**
     * Queries the pages endpoint for one target.
     *
     * Only the properties the feed renders are requested: the intro extract as plain text, the
     * categories used by the recommendation engine, and the lead image.
     */
    private suspend fun requestPages(language: String, id: String): WikiQueryResponse =
        queryPages(language) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", 2)
            parameter("prop", "extracts|categories|pageimages")
            parameter("pithumbsize", THUMBNAIL_WIDTH)
            parameter("exintro", true)
            parameter("explaintext", true)
            parameter("exlimit", "max")
            parameter("redirects", true)
            target(id)
        }

    /** Runs one request and decodes the envelope, mapping a non-success status to a failure. */
    private suspend fun queryPages(
        language: String,
        block: HttpRequestBuilder.() -> Unit,
    ): WikiQueryResponse {
        val response = client.get(wikiApiUrl(language), block)

        if (!response.status.isSuccess()) {
            throw WikipediaException("Wikipedia responded with HTTP ${response.status.value}")
        }

        return response.body()
    }

    /**
     * Adds the selector for a target.
     *
     * A canonical id ("en:20332") is resolved through pageids, which survives renames; anything
     * else is sent through titles. Mixing the two in one request is not allowed by the API, which
     * is why exactly one of them is set.
     */
    private fun HttpRequestBuilder.target(id: String) {
        val pageId = parsePageId(id)
        if (pageId != null) {
            parameter("pageids", pageId)
        } else {
            parameter("titles", id)
        }
    }

    private fun describeMissing(id: String): String = "Article not found: $id"

    /**
     * Cache key for a request.
     *
     * Both "20332" and "en:20332" resolve to the same entry, so a caller that mixes the two forms
     * still hits the cache.
     */
    internal fun cacheKey(language: String, id: String): String =
        if (id.startsWith("$language:")) id else "$language:$id"

    companion object {
        /** How many of the current article's links enter the shuffle. */
        const val LINK_SAMPLE_SIZE: Int = 15

        /** Value that asks the API for the maximum page size. */
        const val PL_LIMIT: String = "max"

        /**
         * Width, in pixels, requested for the lead image.
         *
         * The API default is 320 px, which a full-bleed card stretches across a 1080 px phone and
         * turns into mush. The parameter is a hint, not a demand: an original narrower than this
         * comes back at its own width, so the request never invents detail.
         */
        const val THUMBNAIL_WIDTH: Int = 1080
    }
}

/**
 * Extracts the numeric page id from a canonical article id.
 *
 * Returns null when the value is not a page id: "en:20332" yields "20332" while "Star Trek:
 * Discovery" yields null, because the segment after the last colon is not numeric.
 */
internal fun parsePageId(id: String): String? {
    val tail = id.substringAfterLast(':')
    return tail.takeIf { candidate -> candidate.isNotEmpty() && candidate.all { it.isDigit() } }
}
