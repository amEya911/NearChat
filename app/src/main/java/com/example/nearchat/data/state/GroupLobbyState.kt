package com.example.nearchat.data.state

data class PendingRequest(val address: String, val name: String)

data class GroupLobbyState(
    val isHost: Boolean = true,
    val members: List<String> = emptyList(),
    val pendingRequests: List<PendingRequest> = emptyList()
)
