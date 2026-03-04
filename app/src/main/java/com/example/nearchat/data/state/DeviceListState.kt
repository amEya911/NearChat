package com.example.nearchat.data.state

import com.example.nearchat.data.model.BtDevice

data class DeviceListState(
    val devices: List<BtDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val isSearching: Boolean = false,   // true while name probes are in-flight
    val connectingTo: BtDevice? = null,
    val error: String? = null
)
