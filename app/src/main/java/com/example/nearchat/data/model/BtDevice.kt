package com.example.nearchat.data.model

data class BtDevice(
    val name: String,       // Display name (extracted from [NC] or [NC-G] prefix)
    val address: String,
    val isHostingGroup: Boolean = false
)
