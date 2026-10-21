package com.github.terrakok.wikwok.data

import kotlinx.browser.localStorage

/**
 * Web settings storage backed by localStorage.
 *
 * Browser storage throws instead of failing quietly when it is unavailable: private windows with
 * storage disabled, or a page embedded under a policy that blocks it. A settings read that fails is
 * reported as "onboarding not completed", and a write that fails is dropped, which degrades to the
 * behaviour every other platform had before this class existed.
 */
actual class PlatformSettingsStorage actual constructor() : SettingsStorage {

    override var isOnboardingCompleted: Boolean
        get() = runCatching {
            localStorage.getItem(SettingsKeys.ONBOARDING_COMPLETED) == "true"
        }.getOrDefault(false)
        set(value) {
            runCatching { localStorage.setItem(SettingsKeys.ONBOARDING_COMPLETED, value.toString()) }
        }
}
