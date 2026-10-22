package com.github.terrakok.wikwok.data.claude

import platform.Foundation.NSProcessInfo

/**
 * Reads the key from the process environment, which Xcode passes from the scheme.
 *
 * A shipped application has no environment and therefore no key: the feature stays unconfigured
 * unless the host application supplies one through its own configuration.
 */
actual fun platformClaudeApiKey(): String? =
    normalizeKey(NSProcessInfo.processInfo.environment[ANTHROPIC_KEY_ENVIRONMENT] as? String)
