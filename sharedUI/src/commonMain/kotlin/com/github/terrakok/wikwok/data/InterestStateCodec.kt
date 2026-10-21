package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.InterestState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns an [InterestState] into a string a settings store can hold, and back.
 *
 * Three decisions worth naming.
 *
 * **The envelope carries a version.** Reading a profile written by an older build is the only way a
 * schema change becomes a crash on launch, and a crash on launch is the one failure this app cannot
 * afford. A version that does not match is dropped rather than migrated: the reader loses their
 * weights once, and no build ever carries a decoder for a schema it does not own.
 *
 * **Unknown keys are ignored and defaults are encoded.** Nominally contradictory, but they cover
 * opposite directions. Ignoring unknown keys means a build that only knows about four fields still
 * loads a profile written with five. Encoding defaults means a field added later is written once as
 * its default and then reads back the same on every platform, instead of depending on whether the
 * store returns a string the parser considers absent.
 *
 * **A decode failure is not an error.** Corrupt JSON, a truncated write, a hand-edited preference
 * file: all of them return null, and the caller starts from an empty profile. Losing a profile costs
 * the reader one session of slightly worse picks; a thrown exception costs them the app.
 */
internal object InterestStateCodec {

    /** Schema version written into every stored profile. */
    const val VERSION: Int = 1

    /**
     * Storage envelope.
     *
     * The version is a sibling of the state rather than a field inside it, so the domain model never
     * carries a persistence concern and the state can be serialised without one.
     */
    @Serializable
    private data class Stored(val version: Int = VERSION, val state: InterestState)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serialises [state] into the string a platform store holds. */
    fun encode(state: InterestState): String = json.encodeToString(Stored(state = state))

    /**
     * Parses a stored profile.
     *
     * Session-scoped bookkeeping is dropped on the way out: penalties and the signal window describe
     * minutes that are already over.
     *
     * @param raw whatever the platform store returned, including null and garbage.
     * @return the profile, or null when there is nothing usable.
     */
    fun decode(raw: String?): InterestState? {
        if (raw.isNullOrBlank()) return null
        val stored = runCatching { json.decodeFromString<Stored>(raw) }.getOrNull() ?: return null
        if (stored.version != VERSION) return null
        return stored.state.withoutSessionState()
    }
}
