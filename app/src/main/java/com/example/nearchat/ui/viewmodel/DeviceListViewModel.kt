package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.event.BluetoothEvent
import com.example.nearchat.data.event.DeviceListUiEvent
import com.example.nearchat.data.model.BtDevice
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val bluetoothDataSource: BluetoothDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceListState())
    val state: StateFlow<DeviceListState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    // Devices found during scan — staged here, not shown yet.
    // Probing only starts after DiscoveryFinished so the BT radio
    // is fully idle before any RFCOMM connect attempt.
    private val stagedDevices = mutableListOf<BtDevice>()

    // How many probes are still in-flight
    private val pendingProbeCount = AtomicInteger(0)

    // Guard against probing the same MAC twice
    private val probedAddresses = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            bluetoothDataSource.events.collect { event ->
                when (event) {

                    is BluetoothEvent.DeviceFound -> {
                        // Stage device — do NOT probe yet.
                        // Probing while discovery is running causes connect() to fail
                        // because the BT radio can't scan and connect simultaneously.
                        val isNew = synchronized(probedAddresses) {
                            if (event.device.address !in probedAddresses) {
                                probedAddresses.add(event.device.address)
                                true
                            } else false
                        }
                        if (isNew) {
                            synchronized(stagedDevices) { stagedDevices.add(event.device) }
                        }
                    }

                    is BluetoothEvent.DiscoveryStarted -> {
                        _state.update { it.copy(isDiscovering = true, isSearching = true) }
                    }

                    is BluetoothEvent.DiscoveryFinished -> {
                        // Radio is now fully idle — safe to probe all staged devices
                        _state.update { it.copy(isDiscovering = false) }

                        val toProbe = synchronized(stagedDevices) {
                            stagedDevices.toList().also { stagedDevices.clear() }
                        }

                        if (toProbe.isEmpty()) {
                            _state.update { it.copy(isSearching = false) }
                            return@collect
                        }

                        // Set count before firing probes to avoid race condition
                        pendingProbeCount.set(toProbe.size)
                        toProbe.forEach { device ->
                            bluetoothDataSource.probeDeviceName(device)
                        }
                    }

                    is BluetoothEvent.DeviceNameResolved -> {
                        // Probe succeeded — this device has NearChat, add to visible list
                        _state.update { state ->
                            if (state.devices.none { it.address == event.address }) {
                                val resolvedDevice = BtDevice(
                                    name = event.resolvedName,
                                    address = event.address,
                                    resolvedName = event.resolvedName,
                                    probeComplete = true
                                )
                                state.copy(devices = state.devices + resolvedDevice)
                            } else state
                        }
                    }

                    is BluetoothEvent.DeviceProbeComplete -> {
                        // One probe done (success or fail).
                        // If all probes are done, hide the searching indicator.
                        val remaining = pendingProbeCount.decrementAndGet()
                        if (remaining <= 0) {
                            _state.update { it.copy(isSearching = false) }
                        }
                    }

                    is BluetoothEvent.Connected -> {
                        _state.update { it.copy(connectingTo = null) }
                        _effect.emit(UiEffect.NavigateTo(Screen.Chat))
                    }

                    is BluetoothEvent.Error -> {
                        _state.update { it.copy(connectingTo = null, error = event.message) }
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
                pendingProbeCount.set(0)
                synchronized(probedAddresses) { probedAddresses.clear() }
                synchronized(stagedDevices) { stagedDevices.clear() }
                _state.update {
                    it.copy(
                        devices = emptyList(),
                        error = null,
                        isSearching = false,
                        isDiscovering = false,
                        connectingTo = null
                    )
                }
                bluetoothDataSource.startDiscovery()
            }

            is DeviceListUiEvent.ConnectToDevice -> {
                _state.update { it.copy(connectingTo = event.device) }
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