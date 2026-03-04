package com.example.nearchat.data.state

import com.example.nearchat.data.model.BtDevice
import com.example.nearchat.data.model.Message

data class ChatState(
    val connectedDevice: BtDevice? = null,
    val messages: List<Message> = emptyList(),
    val currentInput: String = "",
    val showDisconnectDialog: Boolean = false
)
