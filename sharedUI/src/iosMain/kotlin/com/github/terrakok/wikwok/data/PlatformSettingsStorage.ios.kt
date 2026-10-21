package com.github.terrakok.wikwok.data

import platform.Foundation.NSUserDefaults

/**
 * Apple settings storage backed by [NSUserDefaults].
 *
 * The standard suite is process-wide and already synchronised by the system; calling synchronize()
 * on every write would be redundant work on a store that no longer needs to be told to persist.
 */
actual class PlatformSettingsStorage actual constructor() : SettingsStorage {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override var isOnboardingCompleted: Boolean
        get() = defaults.boolForKey(SettingsKeys.ONBOARDING_COMPLETED)
        set(value) {
            defaults.setBool(value, forKey = SettingsKeys.ONBOARDING_COMPLETED)
        }
}
