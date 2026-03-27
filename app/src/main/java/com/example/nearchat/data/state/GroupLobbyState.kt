package com.example.nearchat.data.state

data class GroupLobbyState(
    val members: List<String> = emptyList(),
    val isWaiting: Boolean = true
)
