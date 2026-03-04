package com.example.nearchat.data.state

data class OnboardingState(
    val nameInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
