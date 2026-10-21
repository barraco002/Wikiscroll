package com.github.terrakok.wikwok.data

/**
 * The handful of settings that must survive process death.
 *
 * Kept as a small interface rather than a general key-value store: the app has exactly one flag
 * today, and a general store would invite the view model to persist derived state that the engine
 * already owns.
 */
interface SettingsStorage {

    /** True once the reader has chosen their interests and seen the feed. */
    val isOnboardingCompleted: Boolean

    /** Records that the onboarding was completed or restarted. */
    fun setOnboardingCompleted(completed: Boolean)
}

/**
 * Non-persistent [SettingsStorage].
 *
 * The platform backends (SharedPreferences, NSUserDefaults, localStorage) each need an expect/actual
 * pair, and none of them can be exercised from the JVM harness this repository is verified with, so
 * this step ships the seam without the storage engine behind it.
 *
 * TODO(persistence): replace with an expect/actual implementation and delete this object. Nothing
 *  outside [App] knows which implementation is installed.
 */
object InMemorySettings : SettingsStorage {

    private var completed: Boolean = false

    override val isOnboardingCompleted: Boolean
        get() = completed

    override fun setOnboardingCompleted(completed: Boolean) {
        this.completed = completed
    }

    /** Clears the flag. Exists so tests start from a known state despite the shared object. */
    fun reset() {
        completed = false
    }
}
