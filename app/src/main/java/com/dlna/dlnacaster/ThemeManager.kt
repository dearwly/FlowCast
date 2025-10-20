package com.dlna.dlnacaster

import android.app.Activity
import android.content.Context

object ThemeManager {
    private const val PREFS_NAME = "ThemePrefs"
    private const val KEY_THEME = "selected_theme"

    fun setTheme(context: Context, theme: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme).apply()
    }

    fun applyTheme(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val theme = prefs.getString(KEY_THEME, "brown")

        val themeResId = if (activity is MainActivity) {
            when (theme) {
                "green" -> R.style.Theme_DlnaCaster_Green_Dialog
                "blue" -> R.style.Theme_DlnaCaster_Blue_Dialog
                "yellow" -> R.style.Theme_DlnaCaster_Yellow_Dialog
                else -> R.style.Theme_DlnaCaster_Brown_Dialog
            }
        } else {
            when (theme) {
                "green" -> R.style.Theme_DlnaCaster_Green_FullScreen
                "blue" -> R.style.Theme_DlnaCaster_Blue_FullScreen
                "yellow" -> R.style.Theme_DlnaCaster_Yellow_FullScreen
                else -> R.style.Theme_DlnaCaster_Brown_FullScreen
            }
        }
        activity.setTheme(themeResId)
    }
}