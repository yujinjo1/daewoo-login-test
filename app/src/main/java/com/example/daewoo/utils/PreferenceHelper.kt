package com.example.daewoo.utils

import android.content.Context

object PreferenceHelper {
    fun getStartScreen(context: Context, defaultValue: String = "109"): String {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_USER, Context.MODE_PRIVATE)
        return prefs.getString(PreferenceKeys.KEY_START_SCREEN, defaultValue) ?: defaultValue
    }

    fun setStartScreen(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_USER, Context.MODE_PRIVATE)
        prefs.edit().putString(PreferenceKeys.KEY_START_SCREEN, value).apply()
    }

    fun getIndoorPoiName(context: Context): String? {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_USER, Context.MODE_PRIVATE)
        return prefs.getString(PreferenceKeys.KEY_INDOOR_POI_NAME, null)
    }

    fun setIndoorPoiName(context: Context, value: String?) {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_USER, Context.MODE_PRIVATE)
        prefs.edit().putString(PreferenceKeys.KEY_INDOOR_POI_NAME, value).apply()
    }

    fun setLaunchedFrom(context: Context, value: String?) {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_USER, Context.MODE_PRIVATE)
        prefs.edit().putString(PreferenceKeys.KEY_LAUNCHED_FROM, value).apply()
    }

    fun getLaunchedFrom(context: Context): String? {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_USER, Context.MODE_PRIVATE)
        return prefs.getString(PreferenceKeys.KEY_LAUNCHED_FROM, null)
    }
}
