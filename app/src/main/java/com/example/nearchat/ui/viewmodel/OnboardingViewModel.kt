package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.data.event.OnboardingUiEvent
import com.example.nearchat.data.state.OnboardingState
import com.example.nearchat.navigation.Screen
import com.example.nearchat.navigation.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val localUserDataSource: LocalUserDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    fun onEvent(event: OnboardingUiEvent) {
        when (event) {
            is OnboardingUiEvent.NameChanged -> {
                _state.update { it.copy(nameInput = event.name, error = null) }
            }
            is OnboardingUiEvent.ConfirmClicked -> {
                val name = _state.value.nameInput.trim()
                if (name.length < 2 || name.length > 20) {
                    _state.update {
                        it.copy(error = "Name must be between 2 and 20 characters")
                    }
                    return
                }
                _state.update { it.copy(isLoading = true, error = null) }
                localUserDataSource.setDisplayName(name)
                viewModelScope.launch {
                    _effect.emit(UiEffect.NavigateTo(Screen.Home))
                }
            }
        }
    }
}
