package com.example.nearchat.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.AuthDataSource
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.data.event.ProfileUiEvent
import com.example.nearchat.data.state.ProfileState
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
class ProfileViewModel @Inject constructor(
    private val localUserDataSource: LocalUserDataSource,
    private val authDataSource: AuthDataSource,
    private val bluetoothDataSource: BluetoothDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    init {
        val name = localUserDataSource.getDisplayName() ?: ""
        val email = localUserDataSource.getEmail() ?: ""
        _state.update {
            it.copy(displayName = name, email = email, editedName = name)
        }
    }

    fun onEvent(event: ProfileUiEvent) {
        when (event) {
            is ProfileUiEvent.NameChanged -> {
                _state.update { it.copy(editedName = event.name, error = null, saveSuccess = false) }
            }
            is ProfileUiEvent.EditToggle -> {
                _state.update {
                    it.copy(
                        isEditing = !it.isEditing,
                        editedName = it.displayName,
                        error = null,
                        saveSuccess = false
                    )
                }
            }
            is ProfileUiEvent.SaveName -> saveName()
            is ProfileUiEvent.SignOutClicked -> {
                _state.update { it.copy(showSignOutDialog = true) }
            }
            is ProfileUiEvent.SignOutConfirmed -> signOut()
            is ProfileUiEvent.SignOutDismissed -> {
                _state.update { it.copy(showSignOutDialog = false) }
            }
            is ProfileUiEvent.BackPressed -> {
                viewModelScope.launch { _effect.emit(UiEffect.NavigateBack) }
            }
        }
    }

    private fun saveName() {
        val newName = _state.value.editedName.trim()
        if (newName.length < 2 || newName.length > 20) {
            _state.update { it.copy(error = "Name must be 2–20 characters") }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                // Update Firestore
                val uid = localUserDataSource.getUid()
                if (uid != null) {
                    authDataSource.updateDisplayName(uid, newName)
                }

                // Update local cache
                localUserDataSource.saveSession(
                    uid = uid ?: "",
                    email = localUserDataSource.getEmail() ?: "",
                    displayName = newName
                )

                _state.update {
                    it.copy(
                        displayName = newName,
                        isSaving = false,
                        isEditing = false,
                        saveSuccess = true
                    )
                }
                Log.d("ProfileViewModel", "Display name updated to: $newName")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Failed to update name", e)
                _state.update {
                    it.copy(isSaving = false, error = "Failed to save: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun signOut() {
        bluetoothDataSource.restoreAdapterName()
        bluetoothDataSource.disconnect()
        authDataSource.signOut()
        localUserDataSource.clearSession()

        viewModelScope.launch {
            _effect.emit(UiEffect.NavigateTo(Screen.Auth))
        }
    }
}
