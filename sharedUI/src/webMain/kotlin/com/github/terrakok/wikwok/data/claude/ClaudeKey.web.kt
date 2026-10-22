package com.github.terrakok.wikwok.data.claude

/**
 * Web builds ship without a key.
 *
 * A browser has no environment to read and no properties file to parse, and a credential compiled
 * into a JavaScript bundle is a credential published: the bundle is served to every visitor and the
 * call is made from the reader's own network. Rather than offer a field that would only work if the
 * key were already leaked, the web target reports that summarizing is unconfigured, and the panel
 * says so. A deployment that wants summaries on the web has to put a proxy in front of the API and
 * keep the key on that proxy.
 */
actual fun platformClaudeApiKey(): String? = null
