package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.Article
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiApiMediaMappingTest {

    private fun page(json: String) = assertNotNull(
        WikiJson.decodeFromString<WikiQueryResponse>(json).firstPage(),
        "page should decode",
    )

    @Test
    fun mapsThumbnailSourceToImageUrl() {
        val json = """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Rome","thumbnail":{"source":"https://upload.wikimedia.org/rome.jpg","width":320,"height":213}}]}}"""
        val article = assertNotNull(page(json).toArticle("en"))

        assertEquals("https://upload.wikimedia.org/rome.jpg", article.imageUrl)
    }

    @Test
    fun mapsAndTrimsTheExtract() {
        val json = """{"query":{"pages":[{"pageid":2,"ns":0,"title":"Rome","extract":"  A city in Italy.  "}]}}"""
        val article = assertNotNull(page(json).toArticle("en"))

        assertEquals("A city in Italy.", article.extract)
    }

    @Test
    fun aPageWithoutThumbnailCarriesNoImage() {
        val json = """{"query":{"pages":[{"pageid":3,"ns":0,"title":"Rome","categories":[]}]}}"""
        val article = assertNotNull(page(json).toArticle("en"))

        assertNull(article.imageUrl)
        assertEquals("", article.extract)
    }

    @Test
    fun aBlankThumbnailSourceIsTreatedAsAbsent() {
        val json = """{"query":{"pages":[{"pageid":4,"ns":0,"title":"Rome","thumbnail":{"source":"   "}}]}}"""
        val article = assertNotNull(page(json).toArticle("en"))

        assertNull(article.imageUrl, "an empty source would render a broken image")
    }

    @Test
    fun attachingNeighboursKeepsTheMediaFields() {
        val json = """{"query":{"pages":[{"pageid":5,"ns":0,"title":"Rome","extract":"A city.","thumbnail":{"source":"https://upload.wikimedia.org/rome.jpg"}}]}}"""
        val article = assertNotNull(page(json).toArticle("en"))

        val linked = article.withNeighbours {
            listOf(Article(id = "en:6", title = "Ostia", categories = emptyList()))
        }

        assertEquals("A city.", linked.extract)
        assertEquals("https://upload.wikimedia.org/rome.jpg", linked.imageUrl)
        assertTrue(linked.neighbours().isNotEmpty())
    }
}
