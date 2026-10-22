package com.github.terrakok.wikwok.data.claude

/**
 * Reads the key from the process environment or a system property.
 *
 * An installed Android application has no shell environment, so in practice both are empty and the
 * entry point passes the key from the build instead. Nothing breaks when it is missing: the feature
 * reports itself as unconfigured.
 */
actual fun platformClaudeApiKey(): String? =
    normalizeKey(System.getenv(ANTHROPIC_KEY_ENVIRONMENT))
        ?: normalizeKey(System.getProperty(ANTHROPIC_KEY_PROPERTY))
