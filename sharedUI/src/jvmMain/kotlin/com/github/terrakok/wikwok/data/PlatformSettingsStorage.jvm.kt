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
}
