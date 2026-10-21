package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.Article
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiApiMapperTest {

    private val payload = """
        {
          "batchcomplete": true,
          "query": {
            "normalized": [ { "from": "carthage", "to": "Carthage" } ],
            "pages": [
              {
                "pageid": 20332,
                "ns": 0,
                "title": "Carthage",
                "extract": "Carthage was the capital city of Ancient Carthage.",
                "thumbnail": { "source": "https://upload.wikimedia.org/carthage.jpg", "width": 320, "height": 213 },
                "categories": [
                  { "ns": 14, "title": "Category:Ancient Carthage" },
                  { "ns": 14, "title": "Category:Former countries in Africa" },
                  { "ns": 14, "title": "Category:Ancient Carthage" }
                ],
                "links": [
                  { "ns": 0, "title": "Tunisia" },
                  { "ns": 0, "title": "Tunisia" },
                  { "ns": 4, "title": "Wikipedia:Manual of Style" },
                  { "ns": 0, "title": "Mediterranean Sea" }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun decodesPageAndMapsToArticle() {
        val response = WikiJson.decodeFromString<WikiQueryResponse>(payload)
        val page = assertNotNull(response.firstPage(), "page should decode")
        val article = assertNotNull(page.toArticle("en"))

        assertEquals("en:20332", article.id)
        assertEquals("Carthage", article.title)
        assertEquals(listOf("Ancient Carthage", "Former countries in Africa"), article.categories)
        assertTrue(article.neighbours().isEmpty(), "graph defaults to empty")
    }

    @Test
    fun stripsLocalisedCategoryPrefix() {
        val response = WikiJson.decodeFromString<WikiQueryResponse>(
            """{"query":{"pages":[{"pageid":7,"ns":0,"title":"Catedral","categories":[{"ns":14,"title":"Categoria:Arquitectura"}]}]}}"""
        )
        val article = assertNotNull(response.firstPage()?.toArticle("es"))
        assertEquals(listOf("Arquitectura"), article.categories)
    }

    @Test
    fun filtersNamespacesAndDuplicatesFromLinks() {
        val page = assertNotNull(WikiJson.decodeFromString<WikiQueryResponse>(payload).firstPage())
        assertEquals(listOf("Tunisia", "Mediterranean Sea"), page.articleLinkTitles())
    }

    @Test
    fun missingPageYieldsNoArticle() {
        val response = WikiJson.decodeFromString<WikiQueryResponse>(
            """{"query":{"pages":[{"ns":0,"title":"Nope","missing":true}]}}"""
        )
        assertNull(response.firstPage())
        assertEquals(emptyList(), response.query?.pages?.mapNotNull { it.toArticle("en") })
    }

    @Test
    fun apiErrorIsParsed() {
        val response = WikiJson.decodeFromString<WikiQueryResponse>(
            """{"error":{"code":"badvalue","info":"Unrecognized value for parameter"}}"""
        )
        assertEquals("badvalue", response.apiError()?.code)
        assertNull(response.query)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val response = WikiJson.decodeFromString<WikiQueryResponse>(
            """{"query":{"pages":[{"pageid":1,"ns":0,"title":"A","newfield":{"deep":[1,2]}}]},"servedby":"mw1"}"""
        )
        assertEquals("en:1", response.firstPage()?.toArticle("en")?.id)
    }

    @Test
    fun neighbourProviderIsWired() {
        val article = assertNotNull(
            WikiJson.decodeFromString<WikiQueryResponse>(payload).firstPage()!!
                .toArticle("en") { listOf(Article("en:9", "Tunisia", emptyList())) }
        )
        assertEquals(listOf("en:9"), article.neighbours().map { it.id })
    }
}