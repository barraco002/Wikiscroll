package com.github.terrakok.wikwok.data

import java.util.prefs.Preferences

/**
 * Desktop settings storage backed by [Preferences].
 *
 * The node is created under the user root rather than the system root so the app never needs write
 * access to machine-wide configuration. flush() is best effort: a backing store that refuses to
 * write is a warning, not a reason to crash a settings toggle.
 */
actual class PlatformSettingsStorage actual constructor() : SettingsStorage {

    private val preferences: Preferences =
        Preferences.userRoot().node(SettingsKeys.PREFERENCES_NODE)

    override var isOnboardingCompleted: Boolean
        get() = preferences.getBoolean(SettingsKeys.ONBOARDING_COMPLETED, false)
        set(value) {
            preferences.putBoolean(SettingsKeys.ONBOARDING_COMPLETED, value)
            runCatching { preferences.flush() }
        }

    /**
     * Stored with the same key and the same best-effort flush as the flag.
     *
     * java.util.prefs writes through to its backing store synchronously, so a profile write is a
     * small file operation on the calling thread. It is bounded by the profile size, which the codec
     * caps at a two hundred entry history.
     */
    override var savedProfile: String?
        get() = preferences.get(SettingsKeys.INTEREST_PROFILE, null)
        set(value) {
            if (value == null) {
                preferences.remove(SettingsKeys.INTEREST_PROFILE)
            } else {
                preferences.put(SettingsKeys.INTEREST_PROFILE, value)
            }
            runCatching { preferences.flush() }
        }
}
