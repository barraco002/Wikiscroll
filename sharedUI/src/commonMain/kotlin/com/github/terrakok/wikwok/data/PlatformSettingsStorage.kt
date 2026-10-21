package com.github.terrakok.wikwok.data

/**
 * Settings storage backed by whatever the platform provides.
 *
 * One implementation per target: SharedPreferences on Android, NSUserDefaults on Apple platforms,
 * java.util.prefs on the desktop and localStorage on the web. No third-party dependency is involved,
 * which is why this replaced the key-value libraries the fork started with.
 *
 * The Android implementation needs a Context, which a platform-free constructor cannot receive, so
 * that target installs it through [AppContextHolder] from the entry activity. Every other target
 * gets its backing store from a global the platform already exposes.
 */
expect class PlatformSettingsStorage() : SettingsStorage
