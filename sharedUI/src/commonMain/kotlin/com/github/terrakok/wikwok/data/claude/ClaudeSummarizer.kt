package com.github.terrakok.wikwok.data.claude

import com.github.terrakok.wikwok.data.createWikiHttpClient
import com.github.terrakok.wikwok.domain.Article
import com.github.terrakok.wikwok.domain.Summarizer
import com.github.terrakok.wikwok.domain.SummaryOutcome
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Model the summarizer asks for. Fast and cheap, which is the whole requirement here. */
internal const val CLAUDE_MODEL: String = "claude-3-haiku-20240307"

private const val ClaudeEndpoint = "https://api.anthropic.com/v1/messages"
private const val ClaudeApiVersion = "2023-06-01"
private const val ApiKeyHeader = "x-api-key"
private const val AnthropicVersionHeader = "anthropic-version"

/**
 * Output budget per summary.
 *
 * Three bullets of eighteen words are well under a hundred tokens; the ceiling exists so a
 * misbehaving model cannot bill an essay to the key that pays for it.
 */
private const val MaxTokens = 512

/** Hard ceiling on the extract sent upstream. Four thousand characters is roughly a thousand tokens. */
private const val MaxExtractChars = 4_000

/**
 * A summary is generated text, not a slow query. The shared client's twenty second request timeout
 * is tuned for JSON that comes back in one hop, so this call overrides it for the generation round
 * trip only.
 */
private const val GenerationTimeoutMs = 60_000L

/** How many bullets the reader gets. Three fits on screen without scrolling. */
internal const val BULLET_COUNT: Int = 3

private const val TextBlockType = "text"
private const val MissingKeyReason = "No Anthropic API key is configured for this build."
private const val EmptyExtractReason = "This article has no text to summarize."
private const val UnreadableResponseReason = "Claude returned a response this build cannot read."
private const val EmptySummaryReason = "Claude returned an empty summary."
private const val TransportReason = "Could not reach the Claude API."

/**
 * Summarizes articles with the Anthropic Messages API.
 *
 * The key is injected rather than read here: this class runs in the data layer, and a data layer
 * that reaches for a global secret is one that cannot be tested without it. A null or blank key is
 * not an error state, it is the shipped state of a public repository, so the first thing this class
 * does is refuse to make a request that would be rejected anyway.
 *
 * @param apiKey credential, or null when the build has none.
 * @param model model identifier to request.
 * @param engine optional Ktor engine, used by tests; production resolves the platform engine.
 */
class ClaudeSummarizer(
    private val apiKey: String?,
    private val model: String = CLAUDE_MODEL,
    engine: HttpClientEngine? = null,
) : Summarizer {

    // Built on first use so a build without a key never allocates a client for a call it will not
    // make. The shared factory already carries the JSON settings, the timeouts and the retry policy.
    private val client: HttpClient by lazy { createWikiHttpClient(engine) }

    override suspend fun summarize(article: Article, language: String): SummaryOutcome {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty()) return SummaryOutcome.Unconfigured(MissingKeyReason)

        val extract = article.extract.trim()
        if (extract.isEmpty()) return SummaryOutcome.Failed(EmptyExtractReason)

        val request = ClaudeRequest(
            model = model,
            maxTokens = MaxTokens,
            system = SystemPrompt,
            messages = listOf(
                ClaudeMessage(role = "user", content = userPrompt(article.title, extract, language))
            ),
        )

        val response = runCatching {
            client.post(ClaudeEndpoint) {
                header(ApiKeyHeader, key)
                header(AnthropicVersionHeader, ClaudeApiVersion)
                setBody(request)
                timeout { requestTimeoutMillis = GenerationTimeoutMs }
            }
        }.getOrElse { error -> return transportFailure(error) }

        // The shared client runs with expectSuccess off, so every status code arrives here and the
        // reader gets a sentence instead of an exception.
        if (response.status != HttpStatusCode.OK) return SummaryOutcome.Failed(describe(response.status))

        val payload = runCatching { response.body<ClaudeResponse>() }.getOrNull()
            ?: return SummaryOutcome.Failed(UnreadableResponseReason)

        val text = payload.content
            .filter { it.type == TextBlockType }
            .joinToString(separator = "\n") { it.text }
            .trim()

        val bullets = parseSummaryBullets(text)
        if (bullets.isEmpty()) return SummaryOutcome.Failed(EmptySummaryReason)
        return SummaryOutcome.Ready(bullets = bullets, model = model)
    }

    /**
     * Maps a transport exception onto copy the panel can show.
     *
     * Cancellation is rethrown: a coroutine that was cancelled must not be turned into a reader-facing
     * failure, because the panel it belongs to is already gone.
     */
    private fun transportFailure(error: Throwable): SummaryOutcome.Failed {
        if (error is CancellationException) throw error
        return SummaryOutcome.Failed(TransportReason)
    }

    private fun describe(status: HttpStatusCode): String = when {
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            "Claude rejected the configured API key."
        status == HttpStatusCode.TooManyRequests ->
            "Claude is rate limiting this key. Try again in a minute."
        status.value >= ServerErrorFloor -> "Claude is unavailable right now."
        else -> "Claude returned HTTP ${status.value}."
    }
}

/** Status code at which the failure is Claude's, not the caller's. */
private const val ServerErrorFloor = 500

/**
 * The contract the response has to satisfy.
 *
 * Worded to make the parsing cheap: one line per bullet, one marker per line. The parser still
 * tolerates a model that answers with a numbered list or with no markers at all, because a prompt is
 * a request rather than a guarantee.
 */
private const val SystemPrompt: String =
    "You summarize encyclopedia articles for a reader scrolling a feed on a phone. " +
        "Answer with exactly three bullet points, one per line, each starting with a hyphen and a space. " +
        "Each bullet is at most eighteen words. No preamble, no heading, no closing line. " +
        "Write the bullets in the language of the extract."

/**
 * Prompt body for one article.
 *
 * The extract is truncated at [MaxExtractChars] rather than at a paragraph boundary: the tail of a
 * long lead paragraph says less about the article than its opening, and a token budget spent on the
 * least informative part of the text is a budget spent twice.
 */
private fun userPrompt(title: String, extract: String, language: String): String {
    val body = if (extract.length > MaxExtractChars) extract.take(MaxExtractChars) else extract
    return buildString {
        append("Article title: ").append(title).append('\n')
        append("Wikipedia language edition: ").append(language).append('\n')
        append("\n")
        append(body)
    }
}

/**
 * Extracts up to [BULLET_COUNT] bullets from a model answer.
 *
 * Prefixed lines win: anything a model writes before or after the list is commentary, and mixing it
 * into the bullets would make the reader read the model's throat-clearing as content. When no line
 * carries a marker the first non-empty lines are used as they are, which is the better failure mode
 * than an empty card.
 */
internal fun parseSummaryBullets(text: String): List<String> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()

    val marked = lines.mapNotNull(::stripBulletMarker)
    val chosen = marked.ifEmpty { lines }
    return chosen.take(BULLET_COUNT)
}

/**
 * Removes a leading list marker, or returns null when the line does not start with one.
 *
 * Handles the hyphen, the asterisk, the plus, the bullet character and both numbered forms. Written
 * by hand rather than with a regular expression so the behaviour is visible in one screenful and no
 * Unicode escape is needed to read it.
 */
private fun stripBulletMarker(line: String): String? {
    if (line.length < 2) return null
    val first = line[0]
    val afterFirst = line.substring(1)
    if (afterFirst.startsWith(" ")) {
        if (first == '-' || first == '*' || first == '+' || first.code == BulletCharacter) {
            return afterFirst.trim().ifEmpty { null }
        }
    }
    if (!first.isDigit()) return null

    val separator = line.indexOfFirst { it == '.' || it == ')' }
    if (separator <= 0 || separator >= line.lastIndex) return null
    if (!line.substring(0, separator).all(Char::isDigit)) return null
    return line.substring(separator + 1).trim().ifEmpty { null }
}

/** Unicode bullet, kept as a numeric constant so the source stays ASCII. */
private const val BulletCharacter = 0x2022

/** Wire format of one request. */
@Serializable
internal data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ClaudeMessage>,
)

/** One turn of the conversation. */
@Serializable
internal data class ClaudeMessage(
    val role: String,
    val content: String,
)

/** Wire format of a successful response. */
@Serializable
internal data class ClaudeResponse(
    val content: List<ClaudeContentBlock> = emptyList(),
)

/** One block of a response. Only [text] blocks carry prose. */
@Serializable
internal data class ClaudeContentBlock(
    val type: String = "",
    val text: String = "",
)
