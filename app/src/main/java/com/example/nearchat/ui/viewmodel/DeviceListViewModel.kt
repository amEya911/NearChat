package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.event.BluetoothEvent
import com.example.nearchat.data.event.DeviceListUiEvent
import com.example.nearchat.data.state.DeviceListState
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
class DeviceListViewModel @Inject constructor(
    private val bluetoothDataSource: BluetoothDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceListState())
    val state: StateFlow<DeviceListState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    // Track already-seen addresses to avoid duplicates within a scan
    private val seenAddresses = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            bluetoothDataSource.events.collect { event ->
                when (event) {

                    is BluetoothEvent.DeviceFound -> {
                        // Device already filtered by [NC] prefix in BluetoothDataSource.
                        // Just add to the list if not already present.
                        val isNew = synchronized(seenAddresses) {
                            seenAddresses.add(event.device.address)
                        }
                        if (isNew) {
                            _state.update { state ->
                                state.copy(devices = state.devices + event.device)
                            }
                        }
                    }

                    is BluetoothEvent.DiscoveryStarted -> {
                        _state.update { it.copy(isDiscovering = true) }
                    }

                    is BluetoothEvent.DiscoveryFinished -> {
                        _state.update { it.copy(isDiscovering = false) }
                    }

                    is BluetoothEvent.Connected -> {
                        _state.update { it.copy(connectingTo = null) }
                        _effect.emit(UiEffect.NavigateTo(Screen.Chat))
                    }

                    is BluetoothEvent.Error -> {
                        _state.update { it.copy(connectingTo = null, isConnecting = false, error = event.message) }
                    }

                    else -> {}
                }
            }
        }
    }

    fun onEvent(event: DeviceListUiEvent) {
        when (event) {
            is DeviceListUiEvent.StartDiscovery -> {
                // Full reset on each new scan
                synchronized(seenAddresses) { seenAddresses.clear() }
                _state.update {
                    it.copy(
                        devices = emptyList(),
                        error = null,
                        isDiscovering = false,
                        connectingTo = null
                    )
                }
                bluetoothDataSource.startDiscovery()
            }

            is DeviceListUiEvent.ConnectToDevice -> {
                _state.update { it.copy(connectingTo = event.device, isConnecting = true, error = null) }
                bluetoothDataSource.connect(event.device)
            }

            is DeviceListUiEvent.BackPressed -> {
                bluetoothDataSource.stopDiscovery()
                viewModelScope.launch { _effect.emit(UiEffect.NavigateBack) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}