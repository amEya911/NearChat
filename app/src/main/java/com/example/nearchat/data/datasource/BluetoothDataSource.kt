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

        // Name probe — used to check if a device has NearChat installed
        // and to get their display name, all before the user taps Connect
        private const val NAME_PROBE_REQUEST = "NEARCHAT_PROBE"
        private const val NAME_PROBE_RESPONSE_PREFIX = "NEARCHAT_NAME:"
        private const val NAME_PROBE_TIMEOUT_MS = 8_000L
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var readJob: Job? = null

    private var discoveryReceiver: BroadcastReceiver? = null
    private var discoveryFinishedReceiver: BroadcastReceiver? = null

    private var pendingDevice: BtDevice? = null

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
    // Handles two types of incoming connections:
    //   1. NEARCHAT_PROBE  → name probe, respond and restart
    //   2. NEARCHAT_REQUEST → real connection request, wait for user to accept/decline
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

                // Use temp streams — don't assign to connectedSocket yet
                // until we know this is a real connection (not a probe)
                val tempInput = socket.inputStream
                val tempOutput = socket.outputStream

                val remoteDevice = socket.remoteDevice
                val btDevice = BtDevice(
                    name = try { remoteDevice.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" },
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
                    message == NAME_PROBE_REQUEST -> {
                        // ── PROBE ──
                        // Someone is scanning and checking if we have NearChat.
                        // Respond with our display name, close, restart server.
                        val myName = localUserDataSource.getDisplayName() ?: localDeviceName
                        val response = "$NAME_PROBE_RESPONSE_PREFIX$myName"
                        try {
                            tempOutput.write(response.toByteArray(Charsets.UTF_8))
                            tempOutput.flush()
                            Log.d(TAG, "Probe response sent: $response")
                            delay(300) // give remote side time to read before we close
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to write probe response: ${e.message}")
                        } finally {
                            try { socket.close() } catch (_: IOException) {}
                        }
                        // Restart immediately so we're ready for the next probe or real connection
                        startServer()
                    }

                    message.startsWith(HANDSHAKE_REQUEST_PREFIX) -> {
                        // ── REAL CONNECTION REQUEST ──
                        // Assign to persistent streams now — this is a real chat connection
                        connectedSocket = socket
                        inputStream = tempInput
                        outputStream = tempOutput

                        val requesterName = message.removePrefix(HANDSHAKE_REQUEST_PREFIX).trim()
                        pendingDevice = btDevice.copy(name = requesterName.ifBlank { btDevice.name })
                        Log.d(TAG, "Connection requested by: $requesterName")
                        _events.emit(BluetoothEvent.ConnectionRequested(requesterName))
                        // Do NOT restart server here — waiting for accept/decline
                    }

                    else -> {
                        // Unknown — not a NearChat device, close and keep listening
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
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            ?: try { it.name } catch (_: SecurityException) { null }
                            ?: "Device (${it.address.takeLast(5)})"
                        _events.tryEmit(
                            BluetoothEvent.DeviceFound(BtDevice(name = name, address = it.address))
                        )
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
    // NAME PROBE
    // Called after DiscoveryFinished for each found device.
    // At this point the BT radio is idle (not scanning), so connect() works.
    //
    // DO NOT call cancelServer() here. If both phones scan and find each other,
    // they will both probe simultaneously. cancelServer() would cause a deadlock:
    // each phone tears down its own server → neither has a server → both probes
    // fail with "connection refused" → both show "No NearChat users found."
    //
    // The server socket (listen) and probe socket (connect to a different device)
    // are independent — they do not conflict with each other.
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun probeDeviceName(device: BtDevice) {
        scope.launch {
            var probeSocket: BluetoothSocket? = null
            try {
                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                probeSocket = bluetoothDevice?.createRfcommSocketToServiceRecord(APP_UUID)

                val resolvedName = withTimeoutOrNull(NAME_PROBE_TIMEOUT_MS) {
                    probeSocket?.connect()
                    Log.d(TAG, "Probe connected to ${device.address}")

                    probeSocket?.outputStream?.write(NAME_PROBE_REQUEST.toByteArray(Charsets.UTF_8))
                    probeSocket?.outputStream?.flush()

                    val buffer = ByteArray(256)
                    val bytesRead = probeSocket?.inputStream?.read(buffer) ?: -1
                    Log.d(TAG, "Probe read $bytesRead bytes from ${device.address}")

                    if (bytesRead > 0) {
                        val response = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                        if (response.startsWith(NAME_PROBE_RESPONSE_PREFIX)) {
                            response.removePrefix(NAME_PROBE_RESPONSE_PREFIX).trim()
                        } else null
                    } else null
                }

                if (resolvedName != null) {
                    Log.d(TAG, "Probe resolved: ${device.address} → $resolvedName")
                    _events.emit(BluetoothEvent.DeviceNameResolved(
                        address = device.address,
                        resolvedName = resolvedName
                    ))
                } else {
                    Log.d(TAG, "Probe: ${device.address} is not a NearChat device")
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Probe failed for ${device.address}: ${e.message}")
            } finally {
                try { probeSocket?.close() } catch (_: IOException) {}
                _events.emit(BluetoothEvent.DeviceProbeComplete(device.address))
            }
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CONNECT (Device A taps a NearChat device in the list)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(device: BtDevice) {
        scope.launch {
            try {
                // Cancel server before connecting — same reason as in probeDeviceName()
                cancelServer()
                stopDiscovery()
                delay(500)

                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                val socket = bluetoothDevice?.createRfcommSocketToServiceRecord(APP_UUID)
                    ?: run {
                        _events.emit(BluetoothEvent.Error("Could not create socket"))
                        startServer()
                        return@launch
                    }

                socket.connect()
                connectedSocket = socket
                inputStream = socket.inputStream
                outputStream = socket.outputStream

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
                        _events.emit(BluetoothEvent.Connected(device))
                        startReadLoop()
                        // Do NOT restart server — we're in a chat session now
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
        // ChatViewModel should call startServer() after handling Disconnected event
    }

    fun cancelServer() {
        serverJob?.cancel()
        serverJob = null
        pendingDevice = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun updateAdapterName() {
        try {
            val name = localUserDataSource.getDisplayName()
            if (!name.isNullOrBlank() && bluetoothAdapter?.name != name) {
                bluetoothAdapter?.name = name
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot set bluetooth name: ${e.message}")
        }
    }
}