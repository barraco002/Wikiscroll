package com.github.terrakok.wikwok.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Identifying User-Agent required by the Wikimedia API policy.
 *
 * Requests without a descriptive agent are throttled or blocked outright, so this header is part of
 * the transport contract rather than a nicety.
 */
const val WIKISCROLL_USER_AGENT: String =
    "Wikiscroll/1.5.0 (+https://github.com/barraco002/Wikiscroll)"

/**
 * JSON configuration shared by every request.
 *
 * [Json.ignoreUnknownKeys] keeps the client working when Wikimedia adds fields, and
 * [Json.explicitNulls] off lets absent optional fields decode into their default values.
 */
internal val WikiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Builds the HTTP client used by the Wikipedia data layer.
 *
 * The engine is resolved per platform by Ktor, so the same factory works on Android, iOS, JVM and
 * Wasm as long as the platform engine artifact is on the classpath.
 *
 * Notes on the configuration:
 * - [HttpClient.expectSuccess] is disabled: the repository interprets status codes so it can return
 * a domain failure instead of throwing from the transport layer;
 * - timeouts are explicit because a mobile network can hang a request forever;
 * - the User-Agent and the accepted content type are set once, in a default request block, so no
 * call site can forget them.
 */
fun createWikiHttpClient(engine: HttpClientEngine? = null): HttpClient =
    if (engine == null) {
        HttpClient { configureWikiClient() }
    } else {
        HttpClient(engine) { configureWikiClient() }
    }

/**
 * Applies the data layer configuration to any client builder.
 *
 * Split out from [createWikiHttpClient] so tests can build a client on a mock engine and still get
 * the same JSON settings and headers as production.
 */
fun HttpClientConfig<*>.configureWikiClient() {
    expectSuccess = false

    install(ContentNegotiation) {
        json(WikiJson)
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 20_000
        requestTimeoutMillis = 20_000
    }

    // A mobile network drops requests that a second attempt would have served. Server errors are
    // the only ones retried: a 4xx is an answer, and retrying a timeout triples the time the reader
    // spends looking at a spinner before the failure they were always going to get.
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        exponentialDelay(base = 2.0, maxDelayMs = 4_000)
    }

    defaultRequest {
        header(HttpHeaders.UserAgent, WIKISCROLL_USER_AGENT)
        accept(ContentType.Application.Json)
        contentType(ContentType.Application.Json)
    }
}

/** Base URL of the Action API for a language edition. */
fun wikiApiUrl(language: String): String = "https://$language.wikipedia.org/w/api.php"
