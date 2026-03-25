package com.example.nearchat.data.model

data class BtDevice(
    val name: String,       // Display name (extracted from [NC] prefix or handshake)
    val address: String
)
