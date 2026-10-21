package com.github.terrakok.wikwok.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Holds the application context for platform services that a constructor cannot receive.
 *
 * Initialised from the entry activity before the first composition. An uninitialised holder throws
 * instead of returning a null-backed store, because a silent fallback would look like persistence
 * that quietly does not persist.
 */
object AppContextHolder {

    private var applicationContext: Context? = null

    /** Stores the process-wide context. Safe to call more than once. */
    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    internal fun requireContext(): Context = applicationContext
        ?: error("AppContextHolder.init must be called before App() is composed")
}

/**
 * Android settings storage backed by [SharedPreferences].
 *
 * Reads are served from the in-memory map that SharedPreferences keeps after the first load, so the
 * flag costs nothing on the composition path. Writes go through apply() rather than commit() because
 * the value is a user preference, not transactional state, and blocking the main thread to fsync a
 * boolean is not worth the guarantee.
 */
actual class PlatformSettingsStorage actual constructor() : SettingsStorage {

    private val preferences: SharedPreferences = AppContextHolder.requireContext()
        .getSharedPreferences(SettingsKeys.FILE_NAME, Context.MODE_PRIVATE)

    actual override var isOnboardingCompleted: Boolean
        get() = preferences.getBoolean(SettingsKeys.ONBOARDING_COMPLETED, false)
        set(value) {
            preferences.edit().putBoolean(SettingsKeys.ONBOARDING_COMPLETED, value).apply()
        }
}
