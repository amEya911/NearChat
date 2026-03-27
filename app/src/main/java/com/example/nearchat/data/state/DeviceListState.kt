package com.example.nearchat.data.state

import com.example.nearchat.data.model.BtDevice

data class DeviceListState(
    val devices: List<BtDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val isConnecting: Boolean = false,
    val connectingTo: BtDevice? = null,
    val error: String? = null,
    val cooldowns: Map<String, Long> = emptyMap(),
    val selectedDeviceForOptions: BtDevice? = null
)
