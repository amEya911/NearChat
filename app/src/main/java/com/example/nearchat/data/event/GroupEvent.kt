package com.example.nearchat.data.event

sealed class GroupEvent {
    data class MemberJoined(val name: String) : GroupEvent()
    data class MemberLeft(val name: String) : GroupEvent()
    data class GroupMessageReceived(val senderName: String, val text: String) : GroupEvent()
    data class GroupStarted(val memberNames: List<String>) : GroupEvent()
    object GroupDisbanded : GroupEvent()
    data class JoinedGroup(val hostName: String, val memberNames: List<String>) : GroupEvent()
    data class Error(val message: String) : GroupEvent()
}
