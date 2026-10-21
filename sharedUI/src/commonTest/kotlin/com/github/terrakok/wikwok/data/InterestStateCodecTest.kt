package com.github.terrakok.wikwok.data

import com.github.terrakok.wikwok.domain.InterestState
import com.github.terrakok.wikwok.domain.ReadingSignal
import com.github.terrakok.wikwok.domain.Streak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence is the one place where a mistake survives the process, so every failure mode here
 * asserts the same thing: the reader loses the profile, never the launch.
 */
class InterestStateCodecTest {

    private class MemoryStorage : SettingsStorage {
        override var isOnboardingCompleted: Boolean = false
        override var savedProfile: String? = null
    }

    private val richState = InterestState(
        userInterestScores = mapOf("History" to 14, "Astronomy" to 3, "Physics" to 0),
        sessionHistory = listOf("en:1", "en:2", "en:3"),
        seenCategories = setOf("History", "Physics"),
        articlesRead = 27,
        streak = Streak(days = 4, lastVisitEpochDay = 19_700L),
        recentlyRejectedCategories = mapOf("Football" to 3),
        recentSignals = listOf(ReadingSignal.Dwell, ReadingSignal.FastSwipe, ReadingSignal.Dwell),
    )

    @Test
    fun aProfileRoundTripsThroughTheStore() {
        val storage = MemoryStorage()
        val store = ProfileStore(storage)

        store.save(richState)
        val loaded = store.load()

        assertEquals(14, loaded?.scoreOf("History"))
        assertEquals(27, loaded?.articlesRead)
        assertEquals(4, loaded?.streak?.days)
        assertEquals(19_700L, loaded?.streak?.lastVisitEpochDay)
        assertEquals(richState.sessionHistory, loaded?.sessionHistory)
        assertEquals(richState.seenCategories, loaded?.seenCategories)
        assertEquals(setOf("History", "Astronomy", "Physics"), loaded?.userInterestScores?.keys)
    }

    @Test
    fun sessionScopedBookkeepingIsDroppedOnTheWayOut() {
        val loaded = InterestStateCodec.decode(InterestStateCodec.encode(richState))

        assertTrue(loaded!!.recentlyRejectedCategories.isEmpty(), "a penalty from last night is not a fact")
        assertTrue(loaded.recentSignals.isEmpty(), "the exploration band has to start neutral")
        assertEquals(14, loaded.scoreOf("History"), "what the reader likes survives")
    }

    @Test
    fun thereIsNothingToLoadOnAFreshInstall() {
        assertNull(ProfileStore(MemoryStorage()).load())
        assertNull(InterestStateCodec.decode(null))
        assertNull(InterestStateCodec.decode(""))
        assertNull(InterestStateCodec.decode("   "))
    }

    @Test
    fun garbageDecodesToNothingInsteadOfThrowing() {
        assertNull(InterestStateCodec.decode("{not json"))
        assertNull(InterestStateCodec.decode("[]"))
        assertNull(InterestStateCodec.decode("""{"version":1}"""))
    }

    @Test
    fun aProfileFromAnotherSchemaIsIgnored() {
        val encoded = InterestStateCodec.encode(richState)
        val fromTheFuture = encoded.replaceFirst(
            oldValue = "\"version\":" + InterestStateCodec.VERSION,
            newValue = "\"version\":" + (InterestStateCodec.VERSION + 1),
        )

        assertTrue(fromTheFuture != encoded, "the fixture must actually change the version")
        assertNull(InterestStateCodec.decode(fromTheFuture))
    }

    @Test
    fun aStoreThatRefusesToWriteDoesNotThrow() {
        val hostile = object : SettingsStorage {
            override var isOnboardingCompleted: Boolean = false
            override var savedProfile: String?
                get() = error("unreadable")
                set(value) {
                    error("unwritable: " + value.orEmpty().length)
                }
        }
        val store = ProfileStore(hostile)

        store.save(richState)
        assertNull(store.load())
    }
}
