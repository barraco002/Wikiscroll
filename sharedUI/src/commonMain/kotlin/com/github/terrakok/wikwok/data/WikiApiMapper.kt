package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.Article

/**
 * Converts an API page into the domain [Article].
 *
 * The mapper is total on the API side: a page without an id (missing or invalid title) yields null
 * instead of an exception, and every collection is normalised so the domain never receives nulls.
 *
 * @param language wiki language code, used to build the stable article identifier.
 * @param neighboursProvider edges for the random walk. Defaults to an empty graph, which is the
 * correct value when only the article itself was requested.
 * @return the article, or null when the page has no id.
 */
fun WikiPage.toArticle(
    language: String,
    neighboursProvider: () -> List<Article> = { emptyList() },
): Article? {
    val pageId = pageid ?: return null
    if (missing == true || invalid == true) return null

    return Article(
        id = "$language:$pageId",
        title = title,
        categories = categories.mapNotNull { it.cleanCategoryName() }.distinct(),
        neighboursProvider = neighboursProvider,
    )
}

/**
 * Strips the namespace prefix from a category title.
 *
 * The prefix is localised ("Category:", "Categoria:", "Kategorie:"), so the split is done on the
 * first colon rather than on a hard-coded English string.
 *
 * @return the bare category name, or null when nothing meaningful remains.
 */
internal fun WikiCategory.cleanCategoryName(): String? =
    title.substringAfter(':', missingDelimiterValue = title)
        .trim()
        .takeIf { it.isNotEmpty() }

/** Article links only, in page order, de-duplicated. Other namespaces are discarded. */
internal fun WikiPage.articleLinkTitles(): List<String> =
    links.asSequence()
        .filter { it.ns == 0 }
        .map { it.title.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

/**
 * Picks the first usable page out of a response.
 *
 * The API answers a one-title query with a one-element list, but a redirect can add hops, so the
 * lookup is by presence rather than by index.
 */
internal fun WikiQueryResponse.firstPage(): WikiPage? =
    query?.pages?.firstOrNull { it.missing != true && it.invalid != true && it.pageid != null }

/** True when the response carries a structured API error. */
internal fun WikiQueryResponse.apiError(): WikiApiError? = error
