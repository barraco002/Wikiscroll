package com.github.terrakok.wikwok.data.claude

import java.io.File
import java.util.Properties

/** File the desktop build reads when no environment variable is set. */
private const val LocalPropertiesFile = "local.properties"

/**
 * Reads the key from the environment first, then a system property, then local.properties.
 *
 * The order is deliberate: an environment variable is what a shell and a CI runner both offer, a
 * system property is what a launcher can pass without a wrapper, and the file is the fallback for a
 * developer who keeps it next to the project. The file is git-ignored, so a key in it cannot leak
 * through a commit.
 */
actual fun platformClaudeApiKey(): String? =
    normalizeKey(System.getenv(ANTHROPIC_KEY_ENVIRONMENT))
        ?: normalizeKey(System.getProperty(ANTHROPIC_KEY_PROPERTY))
        ?: readLocalProperties()

private fun readLocalProperties(): String? {
    val file = File(LocalPropertiesFile)
    if (!file.isFile) return null
    return runCatching {
        Properties().apply { file.inputStream().use { load(it) } }
            .getProperty(ANTHROPIC_KEY_LOCAL_ENTRY)
    }.getOrNull().let(::normalizeKey)
}
