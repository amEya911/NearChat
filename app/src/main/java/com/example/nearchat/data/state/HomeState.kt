package com.example.nearchat.data.state

data class HomeState(
    val bluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val isLoading: Boolean = false,
    val incomingRequest: String? = null
)
