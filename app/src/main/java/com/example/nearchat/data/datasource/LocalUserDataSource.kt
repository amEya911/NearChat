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

    fun getUid(): String? = prefs.getString("uid", null)

    fun getEmail(): String? = prefs.getString("email", null)

    fun saveSession(uid: String, email: String, displayName: String) {
        prefs.edit()
            .putString("uid", uid)
            .putString("email", email)
            .putString("display_name", displayName)
            .apply()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = !getUid().isNullOrBlank() && !getDisplayName().isNullOrBlank()

    // Keep backward compat — used by BluetoothDataSource
    fun hasProfile(): Boolean = isLoggedIn()
}
