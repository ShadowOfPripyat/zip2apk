package com.zip2apk.builder.util

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    var materialYouEnabled: Boolean
        get() = prefs.getBoolean(KEY_MATERIAL_YOU, false)
        set(value) = prefs.edit().putBoolean(KEY_MATERIAL_YOU, value).apply()

    var autoFixEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FIX, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FIX, value).apply()

    var autoInstallAfterBuild: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INSTALL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_INSTALL, value).apply()

    var autoBuildAfterPreparation: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BUILD_AFTER_PREPARATION, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BUILD_AFTER_PREPARATION, value).apply()

    var keepScreenOnDuringBuild: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    companion object {
        private const val PREFS_NAME = "zip2apk_preferences"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_MATERIAL_YOU = "material_you_enabled"
        private const val KEY_AUTO_FIX = "auto_fix_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install_after_build"
        private const val KEY_AUTO_BUILD_AFTER_PREPARATION = "auto_build_after_preparation"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_during_build"
    }
}
