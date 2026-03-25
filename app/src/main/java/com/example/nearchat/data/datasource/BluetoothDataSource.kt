package com.example.nearchat.data.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.nearchat.data.event.BluetoothEvent
import com.example.nearchat.data.model.BtDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothDataSource @Inject constructor(
    private val context: Context,
    private val localUserDataSource: LocalUserDataSource
) {
    companion object {
        private const val TAG = "BluetoothDataSource"
        private const val APP_NAME = "NearChat"
        val APP_UUID: UUID = UUID.fromString("e0cbf06c-cd8b-4647-bb8a-263b43f0f974")

        private const val HANDSHAKE_REQUEST_PREFIX = "NEARCHAT_REQUEST:"
        private const val HANDSHAKE_ACCEPT = "NEARCHAT_ACCEPT"
        private const val HANDSHAKE_DECLINE = "NEARCHAT_DECLINE"
        private const val HANDSHAKE_TIMEOUT_MS = 30_000L

        // Identity prefix used to broadcast that this device runs NearChat.
        // Format: "[NC]DisplayName" — e.g. "[NC]Ameya"
        // During discovery, only devices whose BT name starts with this prefix are shown.
        const val IDENTITY_PREFIX = "[NC]"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _events = MutableSharedFlow<BluetoothEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    var currentDevice: BtDevice? = null
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var readJob: Job? = null

    private var discoveryReceiver: BroadcastReceiver? = null
    private var discoveryFinishedReceiver: BroadcastReceiver? = null

    private var pendingDevice: BtDevice? = null

    // Store the original BT adapter name so we can restore it later
    private var originalAdapterName: String? = null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    @get:SuppressLint("MissingPermission")
    val localDeviceName: String
        get() = try {
            bluetoothAdapter?.name ?: "Unknown Device"
        } catch (e: SecurityException) {
            "Unknown Device"
        }

    // ─────────────────────────────────────────────────────────────────────────
    // SERVER — always running on both devices from app launch
    // Handles incoming RFCOMM connections for the handshake protocol.
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startServer() {
        updateAdapterName()
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                Log.d(TAG, "Server started, waiting for connection...")

                val socket = serverSocket?.accept() ?: return@launch

                try { serverSocket?.close() } catch (_: IOException) {}
                serverSocket = null

                val tempInput = socket.inputStream
                val tempOutput = socket.outputStream

                val remoteDevice = socket.remoteDevice
                val rawName = try { remoteDevice.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
                // Strip [NC] prefix if present to get the clean display name
                val displayName = if (rawName.startsWith(IDENTITY_PREFIX)) {
                    rawName.removePrefix(IDENTITY_PREFIX).trim()
                } else {
                    rawName
                }
                val btDevice = BtDevice(
                    name = displayName,
                    address = remoteDevice.address
                )

                val buffer = ByteArray(1024)
                val bytesRead = withTimeoutOrNull(10_000L) {
                    tempInput.read(buffer)
                } ?: -1

                if (bytesRead <= 0) {
                    Log.w(TAG, "No data received after accept — restarting")
                    try { socket.close() } catch (_: IOException) {}
                    startServer()
                    return@launch
                }

                val message = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                Log.d(TAG, "Server received: $message")

                when {
                    message.startsWith(HANDSHAKE_REQUEST_PREFIX) -> {
                        // ── REAL CONNECTION REQUEST ──
                        connectedSocket = socket
                        inputStream = tempInput
                        outputStream = tempOutput

                        val requesterName = message.removePrefix(HANDSHAKE_REQUEST_PREFIX).trim()
                        pendingDevice = btDevice.copy(name = requesterName.ifBlank { btDevice.name })
                        Log.d(TAG, "Connection requested by: $requesterName")
                        _events.emit(BluetoothEvent.ConnectionRequested(requesterName))
                    }

                    else -> {
                        Log.w(TAG, "Unknown message, ignoring: $message")
                        try { socket.close() } catch (_: IOException) {}
                        startServer()
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Server error: ${e.message}")
                delay(1000)
                startServer()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error: ${e.message}")
                _events.emit(BluetoothEvent.Error("Bluetooth permission denied"))
            }
        }
    }

    fun acceptConnection() {
        scope.launch {
            try {
                outputStream?.write(HANDSHAKE_ACCEPT.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                val device = pendingDevice
                pendingDevice = null
                if (device != null) {
                    currentDevice = device
                    _events.emit(BluetoothEvent.Connected(device))
                    startReadLoop()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Accept error: ${e.message}")
                _events.emit(BluetoothEvent.Error("Failed to accept connection"))
                closeSocket()
            }
        }
    }

    fun declineConnection() {
        scope.launch {
            try {
                outputStream?.write(HANDSHAKE_DECLINE.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                delay(100)
            } catch (_: IOException) {}
            pendingDevice = null
            closeSocket()
            startServer()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DISCOVERY
    // Filters results to only show devices broadcasting the [NC] identity prefix.
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        try {
            updateAdapterName()
            stopDiscovery()
            _events.tryEmit(BluetoothEvent.DiscoveryStarted)

            discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        val rawName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            ?: try { it.name } catch (_: SecurityException) { null }

                        // Only show devices broadcasting the NearChat identity prefix.
                        if (rawName != null && rawName.startsWith(IDENTITY_PREFIX)) {
                            val displayName = rawName.removePrefix(IDENTITY_PREFIX).trim()
                            if (displayName.isNotBlank()) {
                                _events.tryEmit(
                                    BluetoothEvent.DeviceFound(
                                        BtDevice(name = displayName, address = it.address)
                                    )
                                )
                            }
                        } else {
                            Log.d(TAG, "Filtered out non-NearChat device: ${rawName ?: it.address}")
                        }
                    }
                }
            }

            discoveryFinishedReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                        _events.tryEmit(BluetoothEvent.DiscoveryFinished)
                    }
                }
            }

            context.registerReceiver(discoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            context.registerReceiver(
                discoveryFinishedReceiver,
                IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            )

            bluetoothAdapter?.startDiscovery()

        } catch (e: SecurityException) {
            Log.e(TAG, "Discovery permission error: ${e.message}")
            _events.tryEmit(BluetoothEvent.Error("Bluetooth permission denied"))
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
        try { discoveryReceiver?.let { context.unregisterReceiver(it) } }
        catch (_: IllegalArgumentException) {}
        discoveryReceiver = null
        try { discoveryFinishedReceiver?.let { context.unregisterReceiver(it) } }
        catch (_: IllegalArgumentException) {}
        discoveryFinishedReceiver = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONNECT (Device A taps a NearChat device in the list)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(device: BtDevice) {
        scope.launch {
            try {
                // Stop discovery before connecting — the BT radio can't scan and connect
                // simultaneously. Do NOT cancel the server — it must stay up so the other
                // device can still connect to us if they also initiate.
                stopDiscovery()
                delay(500)

                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                val socket = bluetoothDevice?.createRfcommSocketToServiceRecord(APP_UUID)
                    ?: run {
                        _events.emit(BluetoothEvent.Error("Could not create socket"))
                        return@launch
                    }

                socket.connect()
                connectedSocket = socket
                inputStream = socket.inputStream
                outputStream = socket.outputStream

                // Cancel server now that we have an active connection
                cancelServer()

                val displayName = localUserDataSource.getDisplayName() ?: localDeviceName
                val request = "$HANDSHAKE_REQUEST_PREFIX$displayName"
                outputStream?.write(request.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                Log.d(TAG, "Handshake sent: $request")

                val response = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                    val buffer = ByteArray(1024)
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) String(buffer, 0, bytesRead, Charsets.UTF_8).trim() else null
                }

                Log.d(TAG, "Handshake response: $response")

                when (response) {
                    HANDSHAKE_ACCEPT -> {
                        currentDevice = device
                        _events.emit(BluetoothEvent.Connected(device))
                        startReadLoop()
                    }
                    HANDSHAKE_DECLINE -> {
                        _events.emit(BluetoothEvent.Error("${device.name} declined the connection"))
                        closeSocket()
                        startServer()
                    }
                    null -> {
                        _events.emit(
                            BluetoothEvent.Error(
                                "${device.name} did not respond. Is NearChat open on their device?"
                            )
                        )
                        closeSocket()
                        startServer()
                    }
                    else -> {
                        _events.emit(BluetoothEvent.Error("${device.name} is not running NearChat"))
                        closeSocket()
                        startServer()
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Connection error: ${e.message}")
                _events.emit(
                    BluetoothEvent.Error(
                        "Could not connect to ${device.name}. Make sure NearChat is open on their device."
                    )
                )
                closeSocket()
                startServer()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error: ${e.message}")
                _events.emit(BluetoothEvent.Error("Bluetooth permission denied"))
                startServer()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MESSAGING
    // ─────────────────────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        scope.launch {
            try {
                outputStream?.write(text.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send error: ${e.message}")
                _events.emit(BluetoothEvent.Error("Failed to send message"))
            }
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            while (isActive) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) {
                        _events.emit(BluetoothEvent.Disconnected)
                        break
                    }
                    val message = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    _events.emit(BluetoothEvent.MessageReceived(message))
                } catch (e: IOException) {
                    Log.d(TAG, "Read loop ended: ${e.message}")
                    _events.emit(BluetoothEvent.Disconnected)
                    break
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────────────────

    private fun closeSocket() {
        currentDevice = null
        try { inputStream?.close() } catch (_: IOException) {}
        inputStream = null
        try { outputStream?.close() } catch (_: IOException) {}
        outputStream = null
        try { connectedSocket?.close() } catch (_: IOException) {}
        connectedSocket = null
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        serverJob?.cancel()
        serverJob = null
        pendingDevice = null
        closeSocket()
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        stopDiscovery()
        _events.tryEmit(BluetoothEvent.Disconnected)
    }

    fun cancelServer() {
        serverJob?.cancel()
        serverJob = null
        pendingDevice = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    /**
     * Sets the Bluetooth adapter name to "[NC]DisplayName" so other NearChat
     * devices can identify us during discovery without any server-side lookup.
     */
    @SuppressLint("MissingPermission")
    private fun updateAdapterName() {
        try {
            val displayName = localUserDataSource.getDisplayName() ?: return
            val identityName = "$IDENTITY_PREFIX$displayName"

            // Save original name if we haven't yet
            if (originalAdapterName == null) {
                val currentName = bluetoothAdapter?.name
                if (currentName != null && !currentName.startsWith(IDENTITY_PREFIX)) {
                    originalAdapterName = currentName
                }
            }

            if (bluetoothAdapter?.name != identityName) {
                bluetoothAdapter?.name = identityName
                Log.d(TAG, "Adapter name set to: $identityName")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot set bluetooth name: ${e.message}")
        }
    }

    /**
     * Restores the original Bluetooth adapter name (before NearChat modified it).
     * Called on sign-out.
     */
    @SuppressLint("MissingPermission")
    fun restoreAdapterName() {
        try {
            val original = originalAdapterName ?: return
            if (bluetoothAdapter?.name != original) {
                bluetoothAdapter?.name = original
                Log.d(TAG, "Adapter name restored to: $original")
            }
            originalAdapterName = null
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot restore bluetooth name: ${e.message}")
        }
    }
}