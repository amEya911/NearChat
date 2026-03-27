package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.GroupBluetoothDataSource
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.data.event.GroupChatUiEvent
import com.example.nearchat.data.event.GroupEvent
import com.example.nearchat.data.model.Message
import com.example.nearchat.data.state.GroupChatState
import com.example.nearchat.navigation.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupDataSource: GroupBluetoothDataSource,
    private val localUserDataSource: LocalUserDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(GroupChatState())
    val state: StateFlow<GroupChatState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

    private val myName: String
        get() = localUserDataSource.getDisplayName() ?: "Me"

    init {
        _state.update {
            it.copy(
                isHost = groupDataSource.isHost,
                members = groupDataSource.getMemberNames()
            )
        }

        viewModelScope.launch {
            groupDataSource.events.collect { event ->
                when (event) {
                    is GroupEvent.GroupMessageReceived -> {
                        val message = Message(
                            text = event.text,
                            isMine = false,
                            senderName = event.senderName
                        )
                        _state.update { state ->
                            state.copy(messages = state.messages + message)
                        }
                        _effect.emit(UiEffect.TriggerHaptic)
                    }
                    is GroupEvent.MemberJoined -> {
                        _state.update { state ->
                            val updatedMembers = state.members + event.name
                            val systemMsg = Message(
                                text = "${event.name} joined the group",
                                isMine = false,
                                senderName = "System"
                            )
                            state.copy(
                                members = updatedMembers,
                                messages = state.messages + systemMsg
                            )
                        }
                    }
                    is GroupEvent.MemberLeft -> {
                        _state.update { state ->
                            val updatedMembers = state.members - event.name
                            val systemMsg = Message(
                                text = "${event.name} left the group",
                                isMine = false,
                                senderName = "System"
                            )
                            state.copy(
                                members = updatedMembers,
                                messages = state.messages + systemMsg
                            )
                        }
                    }
                    is GroupEvent.GroupDisbanded -> {
                        _state.update {
                            it.copy(
                                messages = emptyList(),
                                members = emptyList(),
                                currentInput = "",
                                showLeaveDialog = false
                            )
                        }
                        _effect.emit(UiEffect.ShowSnackbar("Group has been disbanded"))
                        _effect.emit(UiEffect.NavigateBack)
                    }
                    is GroupEvent.Error -> {
                        _effect.emit(UiEffect.ShowSnackbar(event.message))
                    }
                    else -> {}
                }
            }
        }
    }

    fun onEvent(event: GroupChatUiEvent) {
        when (event) {
            is GroupChatUiEvent.MessageTyped -> {
                _state.update { it.copy(currentInput = event.text) }
            }
            is GroupChatUiEvent.SendMessage -> {
                val text = _state.value.currentInput.trim()
                if (text.isNotEmpty()) {
                    groupDataSource.sendMessage(text)
                    val message = Message(
                        text = text,
                        isMine = true,
                        senderName = myName
                    )
                    _state.update { state ->
                        state.copy(
                            messages = state.messages + message,
                            currentInput = ""
                        )
                    }
                }
            }
            is GroupChatUiEvent.LeaveClicked -> {
                _state.update { it.copy(showLeaveDialog = true) }
            }
            is GroupChatUiEvent.LeaveConfirmed -> {
                _state.update { it.copy(showLeaveDialog = false) }
                groupDataSource.disconnectAll()
            }
            is GroupChatUiEvent.LeaveDismissed -> {
                _state.update { it.copy(showLeaveDialog = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (groupDataSource.isActive) {
            groupDataSource.disconnectAll()
        }
    }
}
