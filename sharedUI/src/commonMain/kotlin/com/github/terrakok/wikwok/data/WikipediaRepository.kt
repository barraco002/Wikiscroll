package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.Article

/**
 * Read-only access to Wikipedia content.
 *
 * The interface sits in the data layer next to its implementation because it is a boundary, not a
 * domain concept: the domain never asks for "a Wikipedia article", it asks for the next candidate
 * to recommend.
 *
 * Every method returns [Result] instead of throwing. Network and parsing failures are expected
 * outcomes for a feed that must keep scrolling, so the caller decides whether to retry, skip or
 * show a message. Nothing here knows about Android, Compose or the platform the app runs on.
 */
interface WikipediaRepository {

    /**
     * Loads a single article.
     *
     * @param language wiki language code, for example "en" or "es".
     * @param id canonical article id ("en:20332") or a plain title ("Carthage"). The canonical form
     *   is preferred because a title can be renamed while a page id cannot.
     * @return the article on success, or a failure carrying the cause when the page does not exist
     *   or the request could not be completed.
     */
    suspend fun getArticle(language: String, id: String): Result<Article>

    /**
     * Loads articles reachable from [currentArticleId] through internal links.
     *
     * @param language wiki language code.
     * @param currentArticleId canonical article id or plain title of the article being read.
     * @return up to [MAX_RELATED_ARTICLES] articles in link order.
     */
    suspend fun getRelatedArticles(language: String, currentArticleId: String): Result<List<Article>>

    /**
     * Loads one of the day's most viewed articles from the language edition.
     *
     * Used once, to seed the feed when there is no reading history to walk from, and again whenever
     * the link graph runs out. Picking uniformly at random instead produced stubs: a surname, a
     * village, a 1956 census district. The daily ranking is the cheapest proxy for "worth reading"
     * that Wikipedia publishes, and it exists for every language edition.
     *
     * @param language wiki language code.
     * @return the article on success, or a failure when the ranking or the article is unavailable.
     */
    suspend fun getPopularArticle(language: String): Result<Article>

    companion object {
        /** Upper bound on how many links are expanded into articles per call. */
        const val MAX_RELATED_ARTICLES: Int = 5
    }
}
