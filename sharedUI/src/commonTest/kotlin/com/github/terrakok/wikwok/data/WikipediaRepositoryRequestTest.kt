package com.github.terrakok.wikwok.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WikipediaRepositoryRequestTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val payload = """
        {"batchcomplete":true,"query":{"pages":[{"pageid":20332,"ns":0,"title":"Carthage",
        "extract":"Carthage was the capital of Ancient Carthage.",
        "categories":[{"ns":14,"title":"Category:Ancient Carthage"}]}]}}
    """.trimIndent()

    private class Recorder {
        var mainHits: Int = 0
        val mainUrls: MutableList<String> = mutableListOf()
    }

    /**
     * Routes by parameter shape rather than by call order: the article request is the only one that
     * sets redirects, the link request declares prop=links, and everything else is a batch.
     */
    private fun engine(recorder: Recorder, linksPayload: String = EMPTY_LINKS): MockEngine =
        MockEngine { request ->
            val parameters = request.url.parameters
            when {
                parameters["prop"] == "links" -> respond(linksPayload, HttpStatusCode.OK, jsonHeaders)
                parameters["redirects"] == "true" -> {
                    recorder.mainHits++
                    recorder.mainUrls += request.url.toString()
                    respond(payload, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond(payload, HttpStatusCode.OK, jsonHeaders)
            }
        }

    @Test
    fun requestsByPageIdAndCachesTheResult() = runBlocking {
        val recorder = Recorder()
        val repository = WikipediaRepositoryImpl(createWikiHttpClient(engine(recorder)))

        val first = repository.getArticle("en", "en:20332").getOrThrow()
        val second = repository.getArticle("en", "en:20332").getOrThrow()

        assertEquals("en:20332", first.id)
        assertEquals(first, second)
        assertEquals(1, recorder.mainHits, "second call must be served from cache")

        val mainUrl = recorder.mainUrls.single()
        assertTrue(mainUrl.contains("pageids=20332"), "canonical id goes through pageids")
        assertTrue(mainUrl.contains("prop=extracts%7Ccategories%7Cpageimages"), mainUrl)
        assertTrue(mainUrl.contains("exintro=true"))
        assertTrue(mainUrl.contains("explaintext=true"))
    }

    @Test
    fun titleTargetUsesTitlesParameter() = runBlocking {
        val recorder = Recorder()
        WikipediaRepositoryImpl(createWikiHttpClient(engine(recorder)))
            .getArticle("en", "Carthage")
            .getOrThrow()

        assertTrue(recorder.mainUrls.single().contains("titles=Carthage"), recorder.mainUrls.single())
    }

    @Test
    fun titleAndCanonicalIdShareOneCacheEntry() = runBlocking {
        val recorder = Recorder()
        val repository = WikipediaRepositoryImpl(createWikiHttpClient(engine(recorder)))

        repository.getArticle("en", "Carthage").getOrThrow()
        repository.getArticle("en", "en:20332").getOrThrow()

        assertEquals(1, recorder.mainHits, "the canonical id must hit the entry stored by title")
    }

    @Test
    fun httpErrorBecomesFailure() = runBlocking {
        val engine = MockEngine { respond("nope", HttpStatusCode.TooManyRequests) }
        val result = WikipediaRepositoryImpl(createWikiHttpClient(engine)).getArticle("en", "en:1")

        assertTrue(result.isFailure)
        assertEquals("Wikipedia responded with HTTP 429", result.exceptionOrNull()?.message)
    }

    @Test
    fun missingPageBecomesFailure() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"query":{"pages":[{"ns":0,"title":"Nope","missing":true}]}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val result = WikipediaRepositoryImpl(createWikiHttpClient(engine)).getArticle("en", "Nope")

        assertTrue(result.isFailure, "missing page must not produce an article")
    }

    @Test
    fun parsePageIdRecognisesCanonicalIds() {
        assertEquals("20332", parsePageId("en:20332"))
        assertEquals("20332", parsePageId("20332"))
        assertEquals(null, parsePageId("Star Trek: Discovery"))
        assertEquals(null, parsePageId("Carthage"))
    }

    private companion object {
        const val EMPTY_LINKS: String =
            """{"batchcomplete":true,"query":{"pages":[{"pageid":20332,"ns":0,"title":"Carthage"}]}}"""
    }
}
