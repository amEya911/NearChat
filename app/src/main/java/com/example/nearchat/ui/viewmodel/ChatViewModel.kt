package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.event.BluetoothEvent
import com.example.nearchat.data.event.ChatUiEvent
import com.example.nearchat.data.model.Message
import com.example.nearchat.data.state.ChatState
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
class ChatViewModel @Inject constructor(
    private val bluetoothDataSource: BluetoothDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    init {
        _state.update { it.copy(connectedDevice = bluetoothDataSource.currentDevice) }
        viewModelScope.launch {
            bluetoothDataSource.events.collect { event ->
                when (event) {
                    is BluetoothEvent.Connected -> {
                        _state.update {
                            it.copy(connectedDevice = event.device)
                        }
                    }
                    is BluetoothEvent.MessageReceived -> {
                        val message = Message(
                            text = event.text,
                            isMine = false
                        )
                        _state.update { state ->
                            state.copy(messages = state.messages + message)
                        }
                        _effect.emit(UiEffect.TriggerHaptic)
                    }
                    is BluetoothEvent.Disconnected -> {
                        _state.update {
                            it.copy(
                                messages = emptyList(),
                                connectedDevice = null,
                                currentInput = "",
                                showDisconnectDialog = false
                            )
                        }
                        _effect.emit(UiEffect.NavigateBack)
                    }
                    is BluetoothEvent.Error -> {
                        _effect.emit(UiEffect.ShowSnackbar(event.message))
                    }
                    else -> {}
                }
            }
        }
    }

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.MessageTyped -> {
                _state.update { it.copy(currentInput = event.text) }
            }
            is ChatUiEvent.SendMessage -> {
                val text = _state.value.currentInput.trim()
                if (text.isNotEmpty()) {
                    bluetoothDataSource.sendMessage(text)
                    val message = Message(
                        text = text,
                        isMine = true
                    )
                    _state.update { state ->
                        state.copy(
                            messages = state.messages + message,
                            currentInput = ""
                        )
                    }
                }
            }
            is ChatUiEvent.DisconnectClicked -> {
                _state.update { it.copy(showDisconnectDialog = true) }
            }
            is ChatUiEvent.DisconnectConfirmed -> {
                _state.update { it.copy(showDisconnectDialog = false) }
                bluetoothDataSource.disconnect()
            }
            is ChatUiEvent.DisconnectDismissed -> {
                _state.update { it.copy(showDisconnectDialog = false) }
            }
        }
    }
}
