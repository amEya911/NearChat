package com.example.nearchat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearchat.data.datasource.GroupBluetoothDataSource
import com.example.nearchat.data.event.GroupEvent
import com.example.nearchat.data.event.GroupLobbyUiEvent
import com.example.nearchat.data.state.GroupLobbyState
import com.example.nearchat.data.state.PendingRequest
import com.example.nearchat.navigation.Screen
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
class GroupLobbyViewModel @Inject constructor(
    private val groupDataSource: GroupBluetoothDataSource
) : ViewModel() {

    private var isStartingChat = false

    private val _state = MutableStateFlow(GroupLobbyState())
    val state: StateFlow<GroupLobbyState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<UiEffect>()
    val effect: SharedFlow<UiEffect> = _effect.asSharedFlow()

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
                    is GroupEvent.ConnectionRequested -> {
                        _state.update { state ->
                            val currentReqs = state.pendingRequests.toMutableList()
                            currentReqs.add(PendingRequest(event.address, event.name))
                            state.copy(pendingRequests = currentReqs)
                        }
                    }
                    is GroupEvent.MemberJoined -> {
                        _state.update { state ->
                            state.copy(members = state.members + event.name)
                        }
                    }
                    is GroupEvent.MemberLeft -> {
                        _state.update { state ->
                            state.copy(members = state.members - event.name)
                        }
                    }
                    is GroupEvent.GroupStarted -> {
                        isStartingChat = true
                        _effect.emit(UiEffect.NavigateTo(Screen.GroupChat))
                    }
                    is GroupEvent.Error -> {
                        _effect.emit(UiEffect.ShowSnackbar(event.message))
                    }
                    else -> {}
                }
            }
        }
    }

    fun onEvent(event: GroupLobbyUiEvent) {
        when (event) {
            is GroupLobbyUiEvent.StartChat -> {
                if (_state.value.members.isNotEmpty()) {
                    groupDataSource.startGroupChat()
                }
            }
            is GroupLobbyUiEvent.AcceptMember -> {
                groupDataSource.acceptMember(event.address)
                _state.update { it.copy(pendingRequests = it.pendingRequests.filter { req -> req.address != event.address }) }
            }
            is GroupLobbyUiEvent.RejectMember -> {
                groupDataSource.rejectMember(event.address)
                _state.update { it.copy(pendingRequests = it.pendingRequests.filter { req -> req.address != event.address }) }
            }
            is GroupLobbyUiEvent.BackPressed -> {
                groupDataSource.disconnectAll()
                viewModelScope.launch {
                    _effect.emit(UiEffect.NavigateBack)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (!isStartingChat && groupDataSource.isActive) {
            groupDataSource.disconnectAll()
        }
    }
}
