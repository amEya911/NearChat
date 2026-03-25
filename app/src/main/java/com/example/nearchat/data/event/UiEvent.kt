package com.example.nearchat.data.event

import com.example.nearchat.data.model.BtDevice

sealed class HomeUiEvent {
    object FindDevicesClicked : HomeUiEvent()
    data class PermissionsUpdated(val granted: Boolean) : HomeUiEvent()
    object AcceptConnection : HomeUiEvent()
    object DeclineConnection : HomeUiEvent()
    object ProfileClicked : HomeUiEvent()
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

sealed class ProfileUiEvent {
    data class NameChanged(val name: String) : ProfileUiEvent()
    object SaveName : ProfileUiEvent()
    object EditToggle : ProfileUiEvent()
    object SignOutClicked : ProfileUiEvent()
    object SignOutConfirmed : ProfileUiEvent()
    object SignOutDismissed : ProfileUiEvent()
    object BackPressed : ProfileUiEvent()
}
