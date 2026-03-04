package com.example.nearchat.data.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isMine: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
