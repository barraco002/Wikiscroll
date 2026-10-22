package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.data.claude.CLAUDE_MODEL
import com.github.terrakok.wikwok.data.claude.ClaudeSummarizer
import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.SummaryOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Transport behaviour of the summarizer, against a mock engine.
 *
 * The three properties worth protecting are: no key means no request, the key never reaches a
 * message the reader can see, and every failure arrives as a value rather than as an exception.
 */
class ClaudeSummarizerTest {

    private val article = Article(
        id = "en:1",
        title = "Photolithography",
        categories = listOf("Semiconductor fabrication"),
        extract = "Photolithography is the process of using light to transfer a pattern onto a substrate.",
    )

    @Test
    fun aMissingKeyMakesNoRequest() = runTest {
        val recorder = Recorder()
        val summarizer = ClaudeSummarizer(apiKey = null, engine = recorder.engine())

        val outcome = summarizer.summarize(article, "en")

        assertTrue(outcome is SummaryOutcome.Unconfigured)
        assertEquals(0, recorder.requests.size, "an unconfigured client must not open a connection")
    }

    @Test
    fun aBlankKeyIsTreatedAsMissing() = runTest {
        val recorder = Recorder()
        val summarizer = ClaudeSummarizer(apiKey = "   ", engine = recorder.engine())

        assertTrue(summarizer.summarize(article, "en") is SummaryOutcome.Unconfigured)
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun theRequestCarriesTheKeyAndTheApiVersion() = runTest {
        val recorder = Recorder(
            status = HttpStatusCode.OK,
            body = ResponseWithThreeBullets,
        )
        val summarizer = ClaudeSummarizer(apiKey = "sk-ant-test", engine = recorder.engine())

        summarizer.summarize(article, "en")

        val request = recorder.requests.single()
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        val body = recorder.bodies.single()
        assertTrue(body.contains(CLAUDE_MODEL), "the request names the model")
        assertTrue(body.contains("Photolithography"), "the request carries the title")
        assertTrue(body.contains("max_tokens"), "the request carries an output budget")
    }

    @Test
    fun threeBulletsBecomeReady() = runTest {
        val recorder = Recorder(status = HttpStatusCode.OK, body = ResponseWithThreeBullets)
        val summarizer = ClaudeSummarizer(apiKey = "sk-ant-test", engine = recorder.engine())

        val outcome = summarizer.summarize(article, "en")

        assertIs<SummaryOutcome.Ready>(outcome)
        assertEquals(3, outcome.bullets.size)
        assertEquals("Uses light to transfer a pattern", outcome.bullets.first())
        assertEquals(CLAUDE_MODEL, outcome.model)
    }

    @Test
    fun aRejectedKeyBecomesAFailureWithoutLeakingTheKey() = runTest {
        val recorder = Recorder(status = HttpStatusCode.Unauthorized, body = "{}")
        val summarizer = ClaudeSummarizer(apiKey = "sk-ant-secret-value", engine = recorder.engine())

        val outcome = summarizer.summarize(article, "en")

        assertIs<SummaryOutcome.Failed>(outcome)
        assertFalse(outcome.reason.contains("sk-ant"), "the reader-facing copy must not carry the key")
        assertFalse(outcome.reason.contains("secret"))
    }

    @Test
    fun aRateLimitIsReportedAsSuch() = runTest {
        val recorder = Recorder(status = HttpStatusCode.TooManyRequests, body = "{}")
        val summarizer = ClaudeSummarizer(apiKey = "k", engine = recorder.engine())

        val outcome = withContext(Dispatchers.Default) { summarizer.summarize(article, "en") }

        assertIs<SummaryOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("rate limiting"), outcome.reason)
    }

    @Test
    fun aServerErrorIsReportedAsUnavailable() = runTest {
        val recorder = Recorder(status = HttpStatusCode.InternalServerError, body = "{}")
        val summarizer = ClaudeSummarizer(apiKey = "k", engine = recorder.engine())

        val outcome = withContext(Dispatchers.Default) { summarizer.summarize(article, "en") }

        assertIs<SummaryOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("unavailable"), outcome.reason)
    }

    @Test
    fun anAnswerWithoutTextIsAFailure() = runTest {
        val recorder = Recorder(status = HttpStatusCode.OK, body = """{"content":[]}""")
        val summarizer = ClaudeSummarizer(apiKey = "k", engine = recorder.engine())

        assertTrue(summarizer.summarize(article, "en") is SummaryOutcome.Failed)
    }

    @Test
    fun anArticleWithoutExtractIsNotSent() = runTest {
        val recorder = Recorder(status = HttpStatusCode.OK, body = ResponseWithThreeBullets)
        val summarizer = ClaudeSummarizer(apiKey = "k", engine = recorder.engine())

        val outcome = summarizer.summarize(article.copy(extract = "  "), "en")

        assertTrue(outcome is SummaryOutcome.Failed)
        assertEquals(0, recorder.requests.size, "an empty extract has nothing to summarize")
    }

    @Test
    fun theExtractIsTruncatedBeforeItIsSent() = runTest {
        val recorder = Recorder(status = HttpStatusCode.OK, body = ResponseWithThreeBullets)
        val summarizer = ClaudeSummarizer(apiKey = "k", engine = recorder.engine())
        val long = article.copy(extract = "x".repeat(20_000))

        summarizer.summarize(long, "en")

        val body = recorder.bodies.single()
        assertTrue(body.length < 6_000, "the prompt is bounded, not the article")
    }

    /**
     * Mock engine that records what was sent.
     *
     * Bodies are captured as text because the client serialises them before the engine sees them,
     * which is also the only place the prompt can be inspected without a real socket.
     */
    private class Recorder(
        private val status: HttpStatusCode = HttpStatusCode.OK,
        private val body: String = ResponseWithThreeBullets,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()

        fun engine(): MockEngine = MockEngine { request ->
            requests += request
            bodies += (request.body as? TextContent)?.text ?: request.body.toString()
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }
}

private const val ResponseWithThreeBullets: String =
    """{"content":[{"type":"text","text":"- Uses light to transfer a pattern
- Needs a photosensitive resist
- Underpins modern chip fabrication"}]}"""
