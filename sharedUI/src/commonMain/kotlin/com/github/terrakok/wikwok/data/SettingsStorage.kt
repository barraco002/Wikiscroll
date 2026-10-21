package com.github.terrakok.wikwok.data

/**
 * The handful of settings that must survive process death.
 *
 * Kept as a small interface rather than a general key-value store: the app has exactly one flag
 * today, and a general store would invite the view model to persist derived state that the engine
 * already owns. The implementations are per platform; this contract is what the UI talks to.
 */
interface SettingsStorage {

    /** True once the reader has chosen their interests and seen the feed. */
    var isOnboardingCompleted: Boolean
}

/**
 * Keys shared by every platform implementation.
 *
 * Keeping them in one place means a rename cannot silently orphan a stored value on one platform
 * while the others keep reading the old key.
 */
internal object SettingsKeys {

    /** Preference key holding the onboarding flag. */
    const val ONBOARDING_COMPLETED: String = "wikiscroll.onboarding.completed"

    /** Android shared-preferences file name. */
    const val FILE_NAME: String = "wikiscroll.settings"

    /** Desktop preferences node, following the reversed-domain convention of java.util.prefs. */
    const val PREFERENCES_NODE: String = "com/github/terrakok/wikwok"
}
