package com.ai.assistance.operit.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLaunchMode {
    ORBIT,
    STANDARD,
}

/** The default home and the first-install choice are separate from Orbit's own configuration. */
class LaunchModePreferences private constructor(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("launch_mode_preferences", Context.MODE_PRIVATE)

    val mode: AppLaunchMode
        get() = if (preferences.getString(KEY_MODE, null) == AppLaunchMode.STANDARD.name) {
            AppLaunchMode.STANDARD
        } else {
            AppLaunchMode.ORBIT
        }

    private val mutableMode = MutableStateFlow(mode)
    val modeFlow = mutableMode.asStateFlow()

    val hasCompletedModeSelection: Boolean
        get() = preferences.getBoolean(KEY_SELECTION_COMPLETED, false)

    fun initializeModeSelection(hasAcceptedAnyAgreement: Boolean) {
        // Persist this before agreement acceptance, so recreation between the two pages keeps
        // the new-install choice. Existing users, including those on an older agreement, skip it.
        if (!preferences.contains(KEY_SELECTION_COMPLETED)) {
            preferences.edit().putBoolean(KEY_SELECTION_COMPLETED, hasAcceptedAnyAgreement).apply()
        }
    }

    fun setMode(mode: AppLaunchMode) {
        preferences.edit()
            .putString(KEY_MODE, mode.name)
            .putBoolean(KEY_SELECTION_COMPLETED, true)
            .apply()
        mutableMode.value = mode
    }

    companion object {
        private const val KEY_MODE = "default_launch_mode"
        private const val KEY_SELECTION_COMPLETED = "mode_selection_completed"

        @Volatile private var instance: LaunchModePreferences? = null

        fun getInstance(context: Context): LaunchModePreferences =
            instance ?: synchronized(this) {
                instance ?: LaunchModePreferences(context).also { instance = it }
            }
    }
}
