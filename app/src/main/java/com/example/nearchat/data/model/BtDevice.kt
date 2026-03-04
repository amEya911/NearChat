package com.example.nearchat.data.model

data class BtDevice(
    val name: String,                     // OS Bluetooth name from ACTION_FOUND (e.g. "Pixel 6a")
    val address: String,
    val resolvedName: String? = null,     // NearChat display name if probe succeeded (e.g. "Ameya")
    val probeComplete: Boolean = false    // true once probe finished, whether success or fail
)
