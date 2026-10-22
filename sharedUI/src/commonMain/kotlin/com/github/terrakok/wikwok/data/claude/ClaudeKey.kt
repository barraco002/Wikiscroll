package com.github.terrakok.wikwok.data.claude

/** Environment variable the desktop and Android builds look for. */
internal const val ANTHROPIC_KEY_ENVIRONMENT: String = "ANTHROPIC_API_KEY"

/** JVM system property, useful when a launcher cannot set an environment variable. */
internal const val ANTHROPIC_KEY_PROPERTY: String = "anthropic.api.key"

/** Entry the desktop build reads from a local.properties file in the working directory. */
internal const val ANTHROPIC_KEY_LOCAL_ENTRY: String = "anthropic.api.key"

/**
 * Credential this build was configured with, or null when it has none.
 *
 * A public repository cannot ship a key, so every platform answers the same question in its own
 * dialect and every one of them is allowed to answer "no". Null is the ordinary case, not a fault:
 * the summarizer treats it as "not configured" and never opens a connection.
 */
expect fun platformClaudeApiKey(): String?

/** Trims a candidate credential and turns the empty string into null. */
internal fun normalizeKey(candidate: String?): String? =
    candidate?.trim()?.takeIf { it.isNotEmpty() }
