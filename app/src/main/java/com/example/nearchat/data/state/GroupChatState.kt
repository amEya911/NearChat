package com.example.nearchat.data.state

import com.example.nearchat.data.model.Message

data class GroupChatState(
    val isHost: Boolean = false,
    val members: List<String> = emptyList(),
    val messages: List<Message> = emptyList(),
    val currentInput: String = "",
    val showLeaveDialog: Boolean = false
)
