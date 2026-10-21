package com.github.terrakok.wikwok.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WikipediaRepositoryBatchingTest {

    private val linkTitles: List<String> = (1..15).map { "Link$it" }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun linksPayload(): String {
        val links = linkTitles.joinToString(",") { """{"ns":0,"title":"$it"}""" }
        return """{"batchcomplete":true,"query":{"pages":[{"pageid":100,"ns":0,"title":"Rome","links":[$links]}]}}"""
    }

    private fun mainPayload(): String =
        """{"batchcomplete":true,"query":{"pages":[{"pageid":100,"ns":0,"title":"Rome","extract":"Rome is the capital.","categories":[{"ns":14,"title":"Category:Capitals in Europe"}]}]}}"""

    private fun batchPayload(titles: String): String {
        val pages = titles.split("|").mapIndexed { index, title ->
            """{"pageid":${1000 + index},"ns":0,"title":"$title","extract":"Text about $title.","categories":[{"ns":14,"title":"Category:Ancient Rome"}]}"""
        }.joinToString(",")
        return """{"batchcomplete":true,"query":{"pages":[$pages]}}"""
    }

    private class Recorder {
        var hits: Int = 0
        val batchTitles: MutableList<String> = mutableListOf()
        val linkQueries: MutableList<String> = mutableListOf()
    }

    private fun engine(
        recorder: Recorder,
        links: Boolean = true,
    ): MockEngine = MockEngine { request ->
        recorder.hits++
        val parameters = request.url.parameters
        when {
            parameters["prop"] == "links" -> {
                recorder.linkQueries += parameters["titles"] ?: parameters["pageids"].orEmpty()
                respond(if (links) linksPayload() else EMPTY_LINKS, HttpStatusCode.OK, jsonHeaders)
            }
            parameters["titles"] != null -> {
                val titles = parameters["titles"].orEmpty()
                recorder.batchTitles += titles
                respond(batchPayload(titles), HttpStatusCode.OK, jsonHeaders)
            }
            else -> respond(mainPayload(), HttpStatusCode.OK, jsonHeaders)
        }
    }

    private fun repository(recorder: Recorder, links: Boolean = true) =
        WikipediaRepositoryImpl(createWikiHttpClient(engine(recorder, links)), Random(7))

    @Test
    fun relatedArticlesCostExactlyTwoRequests() = runBlocking {
        val recorder = Recorder()
        val related = repository(recorder).getRelatedArticles("en", "en:100").getOrThrow()

        assertEquals(2, recorder.hits, "links plus one batch, nothing else")
        assertEquals(5, related.size)
        assertEquals(1, recorder.batchTitles.size, "one batched request, not five")
    }

    @Test
    fun batchJoinsFiveDistinctSampledTitlesWithApiPipe() = runBlocking {
        val recorder = Recorder()
        repository(recorder).getRelatedArticles("en", "en:100").getOrThrow()

        val titles = recorder.batchTitles.single().split("|")
        assertEquals(5, titles.size)
        assertEquals(5, titles.distinct().size, "no repeated titles in the batch")
        assertTrue(titles.all { it in linkTitles }, "sampled titles must come from the article links")
    }

    @Test
    fun shuffleChangesTheSelectionBetweenInstances() = runBlocking {
        fun sample(seed: Int): List<String> {
            val recorder = Recorder()
            val repository = WikipediaRepositoryImpl(createWikiHttpClient(engine(recorder)), Random(seed))
            runBlocking { repository.getRelatedArticles("en", "en:100").getOrThrow() }
            return recorder.batchTitles.single().split("|")
        }

        assertTrue(sample(1) != sample(2), "different seeds must produce different neighbourhoods")
    }

    @Test
    fun mainArticleCarriesItsFiveNeighbours() = runBlocking {
        val recorder = Recorder()
        val article = repository(recorder).getArticle("en", "en:100").getOrThrow()

        assertEquals(5, article.neighbours().size, "the engine must not need a second call per swipe")
        assertEquals(3, recorder.hits, "main page, links, batch")
        assertTrue(article.neighbours().all { it.id.startsWith("en:") })
    }

    @Test
    fun articleWithoutLinksIsNotAnError() = runBlocking {
        val recorder = Recorder()
        val result = repository(recorder, links = false).getRelatedArticles("en", "en:100")

        assertTrue(result.isSuccess, "a dead end is a legal answer")
        assertEquals(emptyList(), result.getOrThrow())
        assertEquals(1, recorder.hits, "no batch is issued when there is nothing to expand")
    }

    @Test
    fun neighbourhoodIsCachedAcrossCalls() = runBlocking {
        val recorder = Recorder()
        val repository = repository(recorder)

        repository.getRelatedArticles("en", "en:100").getOrThrow()
        repository.getRelatedArticles("en", "en:100").getOrThrow()

        assertEquals(2, recorder.hits, "the second call is served from the related cache")
    }

    private companion object {
        const val EMPTY_LINKS: String =
            """{"batchcomplete":true,"query":{"pages":[{"pageid":100,"ns":0,"title":"Rome"}]}}"""
    }
}
