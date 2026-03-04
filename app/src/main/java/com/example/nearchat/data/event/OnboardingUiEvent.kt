package com.example.nearchat.data.event

sealed class OnboardingUiEvent {
    data class NameChanged(val name: String) : OnboardingUiEvent()
    object ConfirmClicked : OnboardingUiEvent()
}
