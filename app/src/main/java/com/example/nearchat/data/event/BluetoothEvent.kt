package com.example.nearchat.data.event

import com.example.nearchat.data.model.BtDevice

sealed class BluetoothEvent {
    data class DeviceFound(val device: BtDevice) : BluetoothEvent()
    object DiscoveryStarted : BluetoothEvent()
    object DiscoveryFinished : BluetoothEvent()
    data class Connected(val device: BtDevice) : BluetoothEvent()
    data class ConnectionRequested(val requesterName: String) : BluetoothEvent()
    data class MessageReceived(val text: String) : BluetoothEvent()
    object Disconnected : BluetoothEvent()
    data class ConnectionDeclined(val deviceAddress: String, val cooldownEndTime: Long) : BluetoothEvent()
    data class Error(val message: String) : BluetoothEvent()
}
