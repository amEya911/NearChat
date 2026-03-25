package com.example.nearchat.data.state

data class AuthState(
    val isSignUp: Boolean = true,
    val email: String = "",
    val displayName: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
