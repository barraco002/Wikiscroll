package com.github.terrakok.wikwok.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope returned by the MediaWiki Action API.
 *
 * Only the fields the client consumes are modelled. [WikiJson] ignores unknown keys, so the API can
 * add fields without breaking the parser.
 *
 * @property batchcomplete present when the server has nothing left to paginate.
 * @property continueToken pagination cursor, absent on the final page.
 * @property query payload; absent when the request failed.
 * @property error structured failure, present instead of [query] for invalid requests.
 */
@Serializable
data class WikiQueryResponse(
    val batchcomplete: Boolean? = null,
    @SerialName("continue") val continueToken: WikiContinue? = null,
    val query: WikiQuery? = null,
    val error: WikiApiError? = null,
)

/**
 * Pagination cursor.
 *
 * Two independent cursors exist because categories and links paginate separately when both are
 * requested in one call.
 */
@Serializable
data class WikiContinue(
    @SerialName("clcontinue") val categoriesContinue: String? = null,
    @SerialName("plcontinue") val linksContinue: String? = null,
)

/** Payload of a successful query. */
@Serializable
data class WikiQuery(
    val pages: List<WikiPage> = emptyList(),
    val normalized: List<WikiTitleMapping> = emptyList(),
    val redirects: List<WikiTitleMapping> = emptyList(),
    val mostviewed: List<WikiMostViewedEntry> = emptyList(),
)

/**
 * One entry of the most viewed ranking.
 *
 * [ns] is the namespace id, and only zero is an article: everything else is a portal, a category
 * or a special page. [count] is the number of views in the last 24 hours.
 */
@Serializable
data class WikiMostViewedEntry(
    val ns: Int = -1,
    val title: String = "",
    val count: Long = 0,
)

/**
 * One page.
 *
 * A missing or invalid title is returned as an entry carrying [missing] or [invalid] instead of a
 * transport error, which is why [pageid] is nullable.
 */
@Serializable
data class WikiPage(
    val pageid: Long? = null,
    val ns: Int = 0,
    val title: String = "",
    val extract: String? = null,
    val categories: List<WikiCategory> = emptyList(),
    val links: List<WikiLink> = emptyList(),
    val thumbnail: WikiThumbnail? = null,
    val missing: Boolean? = null,
    val invalid: Boolean? = null,
)

/** Category entry. The namespace is 14 for every real category. */
@Serializable
data class WikiCategory(
    val ns: Int = 14,
    val title: String = "",
)

/** Internal link. Namespace 0 marks article links; other namespaces are files, portals and so on. */
@Serializable
data class WikiLink(
    val ns: Int = 0,
    val title: String = "",
)

/** Lead image reference, sized by the request. */
@Serializable
data class WikiThumbnail(
    val source: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

/** Normalisation or redirect hop, from the requested title to the resolved one. */
@Serializable
data class WikiTitleMapping(
    val from: String = "",
    val to: String = "",
)

/** Structured API error, for example a bad title or a rate-limit notice. */
@Serializable
data class WikiApiError(
    val code: String = "",
    val info: String = "",
)
