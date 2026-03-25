package com.example.nearchat.data.event

sealed class AuthUiEvent {
    data class EmailChanged(val email: String) : AuthUiEvent()
    data class DisplayNameChanged(val name: String) : AuthUiEvent()
    data class PasswordChanged(val password: String) : AuthUiEvent()
    object SubmitClicked : AuthUiEvent()
    object ToggleMode : AuthUiEvent()
}
