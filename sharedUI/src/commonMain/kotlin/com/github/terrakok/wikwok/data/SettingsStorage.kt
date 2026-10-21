package com.github.terrakok.wikwok.data

/**
 * The handful of values that must survive process death.
 *
 * Kept as a small interface rather than a general key-value store: the app has two values, and a
 * general store would invite the view model to persist derived state that the engine already owns.
 * The implementations are per platform; this contract is what the UI talks to.
 */
interface SettingsStorage {

    /** True once the reader has chosen their interests and seen the feed. */
    var isOnboardingCompleted: Boolean

    /**
     * The interest profile left behind by the last run, or null when there is none.
     *
     * An opaque string on purpose. The encoding belongs to the caller that owns the model, so no
     * platform implementation has to know what the feed learns, and changing the schema never means
     * touching four storage backends. A decode failure is the caller's problem to absorb by starting
     * from an empty profile, which is the one failure mode that costs the reader nothing.
     */
    var savedProfile: String?
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

    /** Preference key holding the serialised interest profile. */
    const val INTEREST_PROFILE: String = "wikiscroll.interest.profile"

    /** Android shared-preferences file name. */
    const val FILE_NAME: String = "wikiscroll.settings"

    /** Desktop preferences node, following the reversed-domain convention of java.util.prefs. */
    const val PREFERENCES_NODE: String = "com/github/terrakok/wikwok"
}
