package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.InterestState

/**
 * Reads and writes the interest profile through [SettingsStorage].
 *
 * The view model talks to this instead of to the codec so the two halves of persistence - encoding
 * and storage - stay in the data layer, and the presentation layer only ever sees an
 * [InterestState].
 *
 * Writes are best effort by design. A settings backend that throws on the way to disk must not take
 * a swipe down with it: a feed that forgets is a smaller failure than a feed that crashes, and the
 * reader's profile is derived state that the next session can rebuild from a handful of interactions.
 *
 * @param storage platform store the encoded profile is kept in.
 */
class ProfileStore(private val storage: SettingsStorage) {

    /**
     * The profile stored by the last run, or null when there is none or it cannot be read.
     *
     * Never throws: every failure mode below this call degrades to "no profile yet".
     */
    fun load(): InterestState? = runCatching { InterestStateCodec.decode(storage.savedProfile) }.getOrNull()

    /**
     * Stores [state] as the profile for the next launch.
     *
     * Called after every recorded interaction, so the write path stays small: the encoded profile is
     * bounded by the session history cap, a few kilobytes rather than a document.
     */
    fun save(state: InterestState) {
        runCatching { storage.savedProfile = InterestStateCodec.encode(state) }
    }
}
