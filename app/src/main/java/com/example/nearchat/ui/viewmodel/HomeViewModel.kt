package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.datasource.GroupBluetoothDataSource
import com.example.nearchat.data.event.BluetoothEvent
import com.example.nearchat.data.event.HomeUiEvent
import com.example.nearchat.data.state.HomeState
import com.example.nearchat.navigation.Screen
import com.example.nearchat.navigation.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
class HomeViewModel @Inject constructor(
    private val bluetoothDataSource: BluetoothDataSource,
    private val groupBluetoothDataSource: GroupBluetoothDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    private var serverStarted = false
    private var discoverableRequested = false
    private var cooldownTimerJob: Job? = null

    init {
        _state.update {
            it.copy(
                bluetoothEnabled = bluetoothDataSource.isBluetoothEnabled
            )
        }

        viewModelScope.launch {
            bluetoothDataSource.events.collect { event ->
                when (event) {
                    is BluetoothEvent.ConnectionRequested -> {
                        _state.update {
                            it.copy(incomingRequest = event.requesterName)
                        }
                    }
                    is BluetoothEvent.Connected -> {
                        _state.update {
                            it.copy(isLoading = false, incomingRequest = null)
                        }
                        _effect.emit(UiEffect.NavigateTo(Screen.Chat))
                    }
                    is BluetoothEvent.Disconnected -> {
                        _state.update {
                            it.copy(isLoading = false, incomingRequest = null)
                        }
                        // Reset serverStarted so startServerIfNeeded() will actually restart
                        serverStarted = false
                        startServerIfNeeded()
                    }
                    is BluetoothEvent.ConnectionDeclined -> {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                incomingRequest = null,
                                declineCooldownEnd = event.cooldownEndTime,
                                declinedDeviceAddress = event.deviceAddress
                            )
                        }
                        // Start the visible countdown timer
                        startCooldownTimer(event.cooldownEndTime)
                        // Restart server after decline so we keep listening
                        startServerIfNeeded()
                    }
                    is BluetoothEvent.Error -> {
                        _state.update {
                            it.copy(isLoading = false, incomingRequest = null)
                        }
                        _effect.emit(UiEffect.ShowSnackbar(event.message))
                        // Restart server after error so we keep listening
                        startServerIfNeeded()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startCooldownTimer(endTime: Long) {
        cooldownTimerJob?.cancel()
        cooldownTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    _state.update {
                        it.copy(declineCooldownEnd = null, declinedDeviceAddress = null)
                    }
                    break
                }
                delay(1000)
            }
        }
    }

    private fun startServerIfNeeded() {
        if (_state.value.hasPermissions && bluetoothDataSource.isBluetoothEnabled) {
            serverStarted = true
            bluetoothDataSource.startServer()
            // Request OS discoverability so other phones can find us in their scans.
            // Only shown once per session — after that the user can toggle in Settings.
            if (!discoverableRequested) {
                discoverableRequested = true
                viewModelScope.launch {
                    _effect.emit(UiEffect.RequestDiscoverable)
                }
            }
        }
    }

    fun onEvent(event: HomeUiEvent) {
        when (event) {
            is HomeUiEvent.FindDevicesClicked -> {
                viewModelScope.launch {
                    _effect.emit(UiEffect.NavigateTo(Screen.DeviceList))
                }
            }
            is HomeUiEvent.PermissionsUpdated -> {
                _state.update { it.copy(hasPermissions = event.granted) }
                if (event.granted && !serverStarted) {
                    // Auto-start server as soon as permissions are granted
                    startServerIfNeeded()
                }
            }
            is HomeUiEvent.ProfileClicked -> {
                viewModelScope.launch {
                    _effect.emit(UiEffect.NavigateTo(Screen.Profile))
                }
            }
            is HomeUiEvent.CreateGroupClicked -> {
                groupBluetoothDataSource.createGroup()
                viewModelScope.launch {
                    _effect.emit(UiEffect.NavigateTo(Screen.GroupLobby))
                }
            }
            is HomeUiEvent.AcceptConnection -> {
                bluetoothDataSource.acceptConnection()
                _state.update { it.copy(incomingRequest = null) }
                // Server will restart after the chat session ends (Disconnected event)
            }
            is HomeUiEvent.DeclineConnection -> {
                bluetoothDataSource.declineConnection()
                _state.update { it.copy(incomingRequest = null) }
                // Cooldown + server restart handled by BluetoothDataSource + ConnectionDeclined event
            }
        }
    }

    fun updatePermissionStatus(hasPermissions: Boolean) {
        _state.update { it.copy(hasPermissions = hasPermissions) }
    }

    fun updateBluetoothStatus(enabled: Boolean) {
        _state.update { it.copy(bluetoothEnabled = enabled) }
    }

    fun cancelServer() {
        bluetoothDataSource.cancelServer()
        serverStarted = false
        _state.update { it.copy(isLoading = false, incomingRequest = null) }
    }
}
