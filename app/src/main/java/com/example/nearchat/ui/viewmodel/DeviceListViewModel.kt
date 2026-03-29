package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.datasource.GroupBluetoothDataSource
import com.example.nearchat.data.event.BluetoothEvent
import com.example.nearchat.data.event.GroupEvent
import com.example.nearchat.data.event.DeviceListUiEvent
import com.example.nearchat.data.state.DeviceListState
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
class DeviceListViewModel @Inject constructor(
    private val bluetoothDataSource: BluetoothDataSource,
    private val groupBluetoothDataSource: GroupBluetoothDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceListState())
    val state: StateFlow<DeviceListState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    // Track already-seen addresses to avoid duplicates within a scan
    private val seenAddresses = mutableSetOf<String>()

    private var cooldownTickJob: Job? = null

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
                        _state.update { it.copy(connectingTo = null, isConnecting = false) }
                        _effect.emit(UiEffect.NavigateTo(Screen.Chat))
                    }

                    is BluetoothEvent.ConnectionDeclined -> {
                        _state.update { state ->
                            state.copy(
                                connectingTo = null,
                                isConnecting = false,
                                cooldowns = state.cooldowns + (event.deviceAddress to event.cooldownEndTime)
                            )
                        }
                        startCooldownTicker()
                    }

                    is BluetoothEvent.DeviceIsHostingGroup -> {
                        _state.update { state ->
                            state.copy(
                                devices = state.devices.map { 
                                    if (it.address == event.device.address) event.device else it 
                                }
                            )
                        }
                        groupBluetoothDataSource.joinGroup(event.device)
                    }

                    is BluetoothEvent.Error -> {
                        _state.update { it.copy(connectingTo = null, isConnecting = false, error = event.message) }
                    }

                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            groupBluetoothDataSource.events.collect { event ->
                when (event) {
                    is GroupEvent.JoinedGroup -> {
                        _state.update { it.copy(connectingTo = null, isConnecting = false) }
                        _effect.emit(UiEffect.NavigateTo(Screen.GroupLobby))
                    }
                    is GroupEvent.Error -> {
                        _state.update { it.copy(connectingTo = null, isConnecting = false, error = event.message) }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Periodically tick to remove expired cooldowns from the state map,
     * which triggers UI recomposition to update timers.
     */
    private fun startCooldownTicker() {
        if (cooldownTickJob?.isActive == true) return
        cooldownTickJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val current = _state.value.cooldowns
                val active = current.filterValues { it > now }
                if (active != current) {
                    _state.update { it.copy(cooldowns = active) }
                }
                if (active.isEmpty()) break
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
                        // Preserve cooldowns across scans
                    )
                }
                bluetoothDataSource.startDiscovery()
            }

            is DeviceListUiEvent.ConnectToDevice -> {
                if (event.asGroup) {
                    _state.update { it.copy(connectingTo = event.device, isConnecting = true, error = null) }
                    groupBluetoothDataSource.joinGroup(event.device)
                } else {
                    // Check cooldown before connecting 1:1
                    if (bluetoothDataSource.isOnCooldown(event.device.address)) {
                        val remaining = ((bluetoothDataSource.getCooldownEnd(event.device.address) - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                        _state.update {
                            it.copy(error = "Please wait ${remaining}s before reconnecting to ${event.device.name}")
                        }
                        return
                    }
                    _state.update { it.copy(connectingTo = event.device, isConnecting = true, error = null) }
                    bluetoothDataSource.connect(event.device)
                }
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