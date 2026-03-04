package com.example.nearchat.data.datasource

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalUserDataSource @Inject constructor(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("nearchat_prefs", Context.MODE_PRIVATE)

    fun getDisplayName(): String? = prefs.getString("display_name", null)

    fun setDisplayName(name: String) {
        prefs.edit().putString("display_name", name).apply()
    }

    fun hasProfile(): Boolean = !getDisplayName().isNullOrBlank()
}
