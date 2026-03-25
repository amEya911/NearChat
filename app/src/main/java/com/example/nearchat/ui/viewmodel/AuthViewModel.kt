package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.AuthDataSource
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.data.event.AuthUiEvent
import com.example.nearchat.data.state.AuthState
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
import android.util.Log

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authDataSource: AuthDataSource,
    private val localUserDataSource: LocalUserDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    fun onEvent(event: AuthUiEvent) {
        when (event) {
            is AuthUiEvent.EmailChanged -> {
                _state.update { it.copy(email = event.email, error = null) }
            }
            is AuthUiEvent.DisplayNameChanged -> {
                _state.update { it.copy(displayName = event.name, error = null) }
            }
            is AuthUiEvent.PasswordChanged -> {
                _state.update { it.copy(password = event.password, error = null) }
            }
            is AuthUiEvent.ToggleMode -> {
                _state.update { it.copy(isSignUp = !it.isSignUp, error = null) }
            }
            is AuthUiEvent.SubmitClicked -> {
                val current = _state.value
                val validationError = validate(current)
                if (validationError != null) {
                    _state.update { it.copy(error = validationError) }
                    return
                }
                if (current.isSignUp) doSignUp() else doSignIn()
            }
        }
    }

    private fun validate(state: AuthState): String? {
        if (state.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(state.email.trim()).matches()) {
            return "Enter a valid email address"
        }
        if (state.isSignUp) {
            val name = state.displayName.trim()
            if (name.length < 2 || name.length > 20) {
                return "Display name must be 2–20 characters"
            }
        }
        if (state.password.length < 6) {
            return "Password must be at least 6 characters"
        }
        return null
    }

    private fun doSignUp() {
        val s = _state.value
        Log.d("AuthViewModel", "doSignUp called for: ${s.email}")
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            Log.d("AuthViewModel", "Calling authDataSource.signUp...")
            val result = authDataSource.signUp(
                email = s.email.trim(),
                displayName = s.displayName.trim(),
                password = s.password
            )
            Log.d("AuthViewModel", "authDataSource.signUp returned: ${result.isSuccess}")
            result.fold(
                onSuccess = { user ->
                    Log.d("AuthViewModel", "SignUp success, saving session...")
                    localUserDataSource.saveSession(
                        uid = user.uid,
                        email = s.email.trim(),
                        displayName = s.displayName.trim()
                    )
                    Log.d("AuthViewModel", "Session saved, navigating to Home...")
                    _state.update { it.copy(isLoading = false) }
                    _effect.emit(UiEffect.NavigateTo(Screen.Home))
                },
                onFailure = { e ->
                    Log.e("AuthViewModel", "SignUp failed", e)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.localizedMessage ?: "Sign-up failed"
                        )
                    }
                }
            )
        }
    }

    private fun doSignIn() {
        val s = _state.value
        Log.d("AuthViewModel", "doSignIn called for: ${s.email}")
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            Log.d("AuthViewModel", "Calling authDataSource.signIn...")
            val result = authDataSource.signIn(
                email = s.email.trim(),
                password = s.password
            )
            Log.d("AuthViewModel", "authDataSource.signIn returned: ${result.isSuccess}")
            result.fold(
                onSuccess = { (user, displayName) ->
                    Log.d("AuthViewModel", "SignIn success, saving session...")
                    localUserDataSource.saveSession(
                        uid = user.uid,
                        email = s.email.trim(),
                        displayName = displayName
                    )
                    Log.d("AuthViewModel", "Session saved, navigating to Home...")
                    _state.update { it.copy(isLoading = false) }
                    _effect.emit(UiEffect.NavigateTo(Screen.Home))
                },
                onFailure = { e ->
                    Log.e("AuthViewModel", "SignIn failed", e)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.localizedMessage ?: "Sign-in failed"
                        )
                    }
                }
            )
        }
    }
}
