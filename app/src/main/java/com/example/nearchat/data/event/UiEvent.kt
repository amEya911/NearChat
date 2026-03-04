package com.example.nearchat.data.event

import com.example.nearchat.data.model.BtDevice

sealed class HomeUiEvent {
    object FindDevicesClicked : HomeUiEvent()
    data class PermissionsUpdated(val granted: Boolean) : HomeUiEvent()
    object AcceptConnection : HomeUiEvent()
    object DeclineConnection : HomeUiEvent()
}

sealed class DeviceListUiEvent {
    object StartDiscovery : DeviceListUiEvent()
    data class ConnectToDevice(val device: BtDevice) : DeviceListUiEvent()
    object BackPressed : DeviceListUiEvent()
}

sealed class ChatUiEvent {
    data class MessageTyped(val text: String) : ChatUiEvent()
    object SendMessage : ChatUiEvent()
    object DisconnectClicked : ChatUiEvent()
    object DisconnectConfirmed : ChatUiEvent()
    object DisconnectDismissed : ChatUiEvent()
}
