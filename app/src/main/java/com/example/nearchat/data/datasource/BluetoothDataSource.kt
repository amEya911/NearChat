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
import java.util.concurrent.ConcurrentHashMap
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
        private const val COOLDOWN_DURATION_MS = 60_000L

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

    // ── Cooldown system ──
    // Maps device MAC address → cooldown expiry timestamp (System.currentTimeMillis)
    private val cooldownMap = ConcurrentHashMap<String, Long>()

    // Track whether a teardown is in progress to prevent double Disconnected emission
    @Volatile
    private var teardownInProgress = false

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
    // COOLDOWN
    // ─────────────────────────────────────────────────────────────────────────

    fun isOnCooldown(address: String): Boolean {
        val expiry = cooldownMap[address] ?: return false
        if (System.currentTimeMillis() >= expiry) {
            cooldownMap.remove(address)
            return false
        }
        return true
    }

    fun getCooldownEnd(address: String): Long {
        return cooldownMap[address] ?: 0L
    }

    private fun startCooldown(address: String) {
        val endTime = System.currentTimeMillis() + COOLDOWN_DURATION_MS
        cooldownMap[address] = endTime
        Log.d(TAG, "Cooldown started for $address, expires at $endTime")
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
                        // Check if this device is on cooldown
                        if (isOnCooldown(remoteDevice.address)) {
                            Log.d(TAG, "Rejecting request from ${remoteDevice.address} — cooldown active")
                            try {
                                tempOutput.write(HANDSHAKE_DECLINE.toByteArray(Charsets.UTF_8))
                                tempOutput.flush()
                                delay(100)
                            } catch (_: IOException) {}
                            try { socket.close() } catch (_: IOException) {}
                            startServer()
                            return@launch
                        }

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
                    Log.d(TAG, "Connection accepted — entering chat with ${device.name}")
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
            val deviceAddress = pendingDevice?.address
            try {
                outputStream?.write(HANDSHAKE_DECLINE.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                delay(100)
            } catch (_: IOException) {}

            pendingDevice = null
            closeSocket()

            // Start cooldown for this device
            if (deviceAddress != null) {
                startCooldown(deviceAddress)
                val endTime = getCooldownEnd(deviceAddress)
                Log.d(TAG, "Decline cooldown started for $deviceAddress")
                _events.emit(BluetoothEvent.ConnectionDeclined(deviceAddress, endTime))
            }

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
        // Check cooldown before attempting to connect
        if (isOnCooldown(device.address)) {
            val remaining = ((getCooldownEnd(device.address) - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            Log.d(TAG, "Connection blocked — cooldown active for ${device.address} (${remaining}s left)")
            _events.tryEmit(
                BluetoothEvent.Error("Please wait ${remaining}s before reconnecting to ${device.name}")
            )
            return
        }

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
                        Log.d(TAG, "Handshake accepted — entering chat with ${device.name}")
                        _events.emit(BluetoothEvent.Connected(device))
                        startReadLoop()
                    }
                    HANDSHAKE_DECLINE -> {
                        Log.d(TAG, "${device.name} declined — starting cooldown")
                        closeSocket()
                        // Start cooldown on the initiator side too
                        startCooldown(device.address)
                        val endTime = getCooldownEnd(device.address)
                        _events.emit(BluetoothEvent.ConnectionDeclined(device.address, endTime))
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
                        // Only emit Disconnected if teardown is not already in progress
                        if (!teardownInProgress) {
                            Log.d(TAG, "Read loop: stream ended (remote closed)")
                            _events.emit(BluetoothEvent.Disconnected)
                        }
                        break
                    }
                    val message = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    _events.emit(BluetoothEvent.MessageReceived(message))
                } catch (e: IOException) {
                    // Only emit Disconnected if teardown is not already in progress
                    // (i.e., disconnectAndRestart was called and already emitted it)
                    if (!teardownInProgress) {
                        Log.d(TAG, "Read loop ended unexpectedly: ${e.message}")
                        _events.emit(BluetoothEvent.Disconnected)
                    } else {
                        Log.d(TAG, "Read loop ended (teardown in progress, skipping duplicate event)")
                    }
                    break
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────────────────

    private fun closeSocket() {
        Log.d(TAG, "closeSocket() — releasing streams and socket")
        currentDevice = null
        try { inputStream?.close() } catch (_: IOException) {}
        inputStream = null
        try { outputStream?.close() } catch (_: IOException) {}
        outputStream = null
        try { connectedSocket?.close() } catch (_: IOException) {}
        connectedSocket = null
    }

    /**
     * Full teardown of the current session + automatic server restart.
     * This is the primary method used when the chat ends (back-nav, explicit disconnect).
     * It ensures no stale resources remain and the server is listening again.
     */
    fun disconnectAndRestart() {
        Log.d(TAG, "disconnectAndRestart() — full teardown starting")
        teardownInProgress = true

        // 1. Cancel read loop (will throw IOException when socket closes, but
        //    teardownInProgress flag prevents duplicate Disconnected emission)
        readJob?.cancel()
        readJob = null

        // 2. Close connected socket (this interrupts the blocking read() call)
        closeSocket()

        // 3. Cancel server and close server socket
        serverJob?.cancel()
        serverJob = null
        pendingDevice = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null

        // 4. Stop any ongoing discovery
        stopDiscovery()

        // 5. Emit Disconnected exactly once
        _events.tryEmit(BluetoothEvent.Disconnected)

        // 6. Wait briefly for BT stack to release the RFCOMM channel, then restart server
        scope.launch {
            delay(300)
            teardownInProgress = false
            Log.d(TAG, "Teardown complete — restarting server")
            startServer()
        }
    }

    /**
     * Legacy disconnect — stops everything without restarting the server.
     * Used only for sign-out or app-level teardown scenarios.
     */
    fun disconnect() {
        Log.d(TAG, "disconnect() — stopping all Bluetooth activity")
        teardownInProgress = true
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
        // Reset flag asynchronously
        scope.launch {
            delay(300)
            teardownInProgress = false
        }
    }

    fun cancelServer() {
        Log.d(TAG, "cancelServer() — closing server socket")
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