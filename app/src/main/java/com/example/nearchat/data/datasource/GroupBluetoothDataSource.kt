package com.example.nearchat.data.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.example.nearchat.data.event.GroupEvent
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

/**
 * Manages group chat Bluetooth connections using a host-relay star topology.
 *
 * The HOST creates a group and continuously accepts incoming connections.
 * Each MEMBER connects to the host via a single RFCOMM socket.
 * When any device sends a message, the host relays it to all other members.
 */
@Singleton
class GroupBluetoothDataSource @Inject constructor(
    private val context: Context,
    private val localUserDataSource: LocalUserDataSource
) {
    companion object {
        private const val TAG = "GroupBtDataSource"
        private const val APP_NAME = "NearChatGroup"
        val GROUP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

        private const val GROUP_JOIN_PREFIX = "NEARCHAT_GROUP_JOIN:"
        private const val GROUP_WELCOME_PREFIX = "NEARCHAT_GROUP_WELCOME:"
        private const val GROUP_MSG_PREFIX = "GROUP_MSG:"
        private const val GROUP_MEMBER_JOINED_PREFIX = "GROUP_MEMBER_JOINED:"
        private const val GROUP_MEMBER_LEFT_PREFIX = "GROUP_MEMBER_LEFT:"
        private const val GROUP_STARTED = "GROUP_STARTED"
        private const val GROUP_DISBANDED = "GROUP_DISBANDED"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _events = MutableSharedFlow<GroupEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GroupEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Host state ──
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val members = ConcurrentHashMap<String, MemberConnection>() // address → connection

    // ── Member state ──
    private var hostSocket: BluetoothSocket? = null
    private var hostInput: InputStream? = null
    private var hostOutput: OutputStream? = null
    private var hostReadJob: Job? = null
    var hostName: String? = null
        private set

    var isHost: Boolean = false
        private set

    var isActive: Boolean = false
        private set

    @Volatile
    private var teardownInProgress = false

    data class MemberConnection(
        val name: String,
        val address: String,
        val socket: BluetoothSocket,
        val input: InputStream,
        val output: OutputStream,
        var readJob: Job? = null
    )

    // ─────────────────────────────────────────────────────────────────────────
    // HOST: Create group and accept members
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun createGroup() {
        isHost = true
        isActive = true
        teardownInProgress = false
        Log.d(TAG, "Creating group — starting accept loop")

        acceptJob?.cancel()
        acceptJob = scope.launch {
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, GROUP_UUID)
                Log.d(TAG, "Group server started, accepting members...")

                while (isActive) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Log.d(TAG, "Accepted connection from ${socket.remoteDevice.address}")
                        handleIncomingMember(socket)
                    } catch (e: IOException) {
                        if (isActive && !teardownInProgress) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Server create error: ${e.message}")
                _events.emit(GroupEvent.Error("Failed to create group"))
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error: ${e.message}")
                _events.emit(GroupEvent.Error("Bluetooth permission denied"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleIncomingMember(socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream

            // Read the join handshake
            val buffer = ByteArray(1024)
            val bytesRead = withTimeoutOrNull(10_000L) {
                input.read(buffer)
            } ?: -1

            if (bytesRead <= 0) {
                Log.w(TAG, "No handshake from member — closing")
                try { socket.close() } catch (_: IOException) {}
                return
            }

            val message = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
            Log.d(TAG, "Member handshake: $message")

            if (!message.startsWith(GROUP_JOIN_PREFIX)) {
                Log.w(TAG, "Invalid group handshake: $message")
                try { socket.close() } catch (_: IOException) {}
                return
            }

            val memberName = message.removePrefix(GROUP_JOIN_PREFIX).trim()
            if (memberName.isBlank()) {
                try { socket.close() } catch (_: IOException) {}
                return
            }

            val address = socket.remoteDevice.address

            // Send welcome with host name + current member list
            val hostDisplayName = localUserDataSource.getDisplayName() ?: "Host"
            val existingMembers = members.values.map { it.name }.joinToString(",")
            val welcome = "$GROUP_WELCOME_PREFIX$hostDisplayName:$existingMembers"
            output.write(welcome.toByteArray(Charsets.UTF_8))
            output.flush()
            Log.d(TAG, "Sent welcome to $memberName: $welcome")

            // Notify existing members about the new joiner
            broadcastToMembers("$GROUP_MEMBER_JOINED_PREFIX$memberName", excludeAddress = null)

            // Store the new member
            val conn = MemberConnection(
                name = memberName,
                address = address,
                socket = socket,
                input = input,
                output = output
            )
            members[address] = conn
            conn.readJob = startMemberReadLoop(conn)

            Log.d(TAG, "Member added: $memberName ($address). Total: ${members.size}")
            _events.emit(GroupEvent.MemberJoined(memberName))

        } catch (e: IOException) {
            Log.e(TAG, "Error handling member: ${e.message}")
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /**
     * Host starts the chat — notifies all members.
     */
    fun startGroupChat() {
        scope.launch {
            broadcastToMembers(GROUP_STARTED, excludeAddress = null)
            val memberNames = members.values.map { it.name }
            Log.d(TAG, "Group chat started with ${memberNames.size} members: $memberNames")
            _events.emit(GroupEvent.GroupStarted(memberNames))
        }
    }

    fun getMemberNames(): List<String> = members.values.map { it.name }

    // ─────────────────────────────────────────────────────────────────────────
    // MEMBER: Join an existing group
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun joinGroup(device: BtDevice) {
        isHost = false
        isActive = true
        teardownInProgress = false

        scope.launch {
            try {
                val btDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                val socket = btDevice?.createRfcommSocketToServiceRecord(GROUP_UUID)
                    ?: run {
                        _events.emit(GroupEvent.Error("Could not create socket"))
                        return@launch
                    }

                socket.connect()
                hostSocket = socket
                hostInput = socket.inputStream
                hostOutput = socket.outputStream

                // Send join handshake
                val displayName = localUserDataSource.getDisplayName() ?: "Unknown"
                val joinMsg = "$GROUP_JOIN_PREFIX$displayName"
                hostOutput?.write(joinMsg.toByteArray(Charsets.UTF_8))
                hostOutput?.flush()
                Log.d(TAG, "Join handshake sent: $joinMsg")

                // Wait for welcome response
                val buffer = ByteArray(2048)
                val bytesRead = withTimeoutOrNull(10_000L) {
                    hostInput?.read(buffer) ?: -1
                } ?: -1

                if (bytesRead <= 0) {
                    Log.e(TAG, "No welcome received")
                    _events.emit(GroupEvent.Error("Group host did not respond"))
                    closeHostSocket()
                    return@launch
                }

                val response = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                Log.d(TAG, "Welcome response: $response")

                if (!response.startsWith(GROUP_WELCOME_PREFIX)) {
                    Log.e(TAG, "Invalid welcome: $response")
                    _events.emit(GroupEvent.Error("Invalid group response"))
                    closeHostSocket()
                    return@launch
                }

                // Parse: GROUP_WELCOME:<hostName>:<member1>,<member2>,...
                val payload = response.removePrefix(GROUP_WELCOME_PREFIX)
                val parts = payload.split(":", limit = 2)
                hostName = parts.getOrNull(0) ?: "Host"
                val existingMembers = parts.getOrNull(1)
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()

                Log.d(TAG, "Joined group. Host: $hostName, existing members: $existingMembers")
                _events.emit(GroupEvent.JoinedGroup(hostName!!, existingMembers))

                // Start reading from host
                startHostReadLoop()

            } catch (e: IOException) {
                Log.e(TAG, "Join error: ${e.message}")
                _events.emit(GroupEvent.Error("Could not join group. Make sure the host has the group open."))
                closeHostSocket()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error: ${e.message}")
                _events.emit(GroupEvent.Error("Bluetooth permission denied"))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MESSAGING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Send a message. Host broadcasts to all; member sends to host for relay.
     */
    fun sendMessage(text: String) {
        val senderName = localUserDataSource.getDisplayName() ?: "Unknown"
        val payload = "$GROUP_MSG_PREFIX$senderName:$text"

        scope.launch {
            if (isHost) {
                // Host: broadcast to all members
                broadcastToMembers(payload, excludeAddress = null)
            } else {
                // Member: send to host for relay
                try {
                    hostOutput?.write(payload.toByteArray(Charsets.UTF_8))
                    hostOutput?.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Send error: ${e.message}")
                    _events.emit(GroupEvent.Error("Failed to send message"))
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ LOOPS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Host: read loop for a single member — relays messages to all others.
     */
    private fun startMemberReadLoop(conn: MemberConnection): Job {
        return scope.launch {
            val buffer = ByteArray(2048)
            while (isActive) {
                try {
                    val bytesRead = conn.input.read(buffer)
                    if (bytesRead == -1) {
                        handleMemberDisconnect(conn)
                        break
                    }
                    val message = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                    Log.d(TAG, "Received from ${conn.name}: $message")
                    handleHostReceivedMessage(message, conn)
                } catch (e: IOException) {
                    if (!teardownInProgress) {
                        Log.d(TAG, "Member ${conn.name} read loop ended: ${e.message}")
                        handleMemberDisconnect(conn)
                    }
                    break
                }
            }
        }
    }

    /**
     * Host: process a message from a member.
     */
    private suspend fun handleHostReceivedMessage(raw: String, from: MemberConnection) {
        when {
            raw.startsWith(GROUP_MSG_PREFIX) -> {
                // Relay to all other members
                broadcastToMembers(raw, excludeAddress = from.address)

                // Also emit event for host's own UI
                val payload = raw.removePrefix(GROUP_MSG_PREFIX)
                val colonIndex = payload.indexOf(':')
                if (colonIndex > 0) {
                    val senderName = payload.substring(0, colonIndex)
                    val text = payload.substring(colonIndex + 1)
                    _events.emit(GroupEvent.GroupMessageReceived(senderName, text))
                }
            }
            else -> {
                Log.w(TAG, "Unknown message from ${from.name}: $raw")
            }
        }
    }

    /**
     * Member: read loop from host.
     */
    private fun startHostReadLoop() {
        hostReadJob?.cancel()
        hostReadJob = scope.launch {
            val buffer = ByteArray(2048)
            while (isActive) {
                try {
                    val bytesRead = hostInput?.read(buffer) ?: -1
                    if (bytesRead == -1) {
                        if (!teardownInProgress) {
                            Log.d(TAG, "Host connection ended")
                            _events.emit(GroupEvent.GroupDisbanded)
                        }
                        break
                    }
                    val raw = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                    Log.d(TAG, "Received from host: $raw")
                    handleMemberReceivedMessage(raw)
                } catch (e: IOException) {
                    if (!teardownInProgress) {
                        Log.d(TAG, "Host read loop ended: ${e.message}")
                        _events.emit(GroupEvent.GroupDisbanded)
                    }
                    break
                }
            }
        }
    }

    /**
     * Member: process a message from the host.
     */
    private suspend fun handleMemberReceivedMessage(raw: String) {
        when {
            raw.startsWith(GROUP_MSG_PREFIX) -> {
                val payload = raw.removePrefix(GROUP_MSG_PREFIX)
                val colonIndex = payload.indexOf(':')
                if (colonIndex > 0) {
                    val senderName = payload.substring(0, colonIndex)
                    val text = payload.substring(colonIndex + 1)
                    _events.emit(GroupEvent.GroupMessageReceived(senderName, text))
                }
            }
            raw.startsWith(GROUP_MEMBER_JOINED_PREFIX) -> {
                val name = raw.removePrefix(GROUP_MEMBER_JOINED_PREFIX).trim()
                if (name.isNotBlank()) {
                    _events.emit(GroupEvent.MemberJoined(name))
                }
            }
            raw.startsWith(GROUP_MEMBER_LEFT_PREFIX) -> {
                val name = raw.removePrefix(GROUP_MEMBER_LEFT_PREFIX).trim()
                if (name.isNotBlank()) {
                    _events.emit(GroupEvent.MemberLeft(name))
                }
            }
            raw == GROUP_STARTED -> {
                _events.emit(GroupEvent.GroupStarted(emptyList()))
            }
            raw == GROUP_DISBANDED -> {
                _events.emit(GroupEvent.GroupDisbanded)
            }
            else -> {
                Log.w(TAG, "Unknown message from host: $raw")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DISCONNECT HANDLING
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun handleMemberDisconnect(conn: MemberConnection) {
        Log.d(TAG, "Member disconnected: ${conn.name} (${conn.address})")
        conn.readJob?.cancel()
        try { conn.input.close() } catch (_: IOException) {}
        try { conn.output.close() } catch (_: IOException) {}
        try { conn.socket.close() } catch (_: IOException) {}
        members.remove(conn.address)

        // Notify remaining members
        broadcastToMembers("$GROUP_MEMBER_LEFT_PREFIX${conn.name}", excludeAddress = null)
        _events.emit(GroupEvent.MemberLeft(conn.name))
    }

    /**
     * Host: broadcast a raw message to all connected members.
     */
    private fun broadcastToMembers(message: String, excludeAddress: String?) {
        val data = message.toByteArray(Charsets.UTF_8)
        val deadMembers = mutableListOf<String>()

        for ((address, conn) in members) {
            if (address == excludeAddress) continue
            try {
                conn.output.write(data)
                conn.output.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send to ${conn.name}: ${e.message}")
                deadMembers.add(address)
            }
        }

        // Clean up dead connections asynchronously
        if (deadMembers.isNotEmpty()) {
            scope.launch {
                for (address in deadMembers) {
                    val conn = members.remove(address) ?: continue
                    conn.readJob?.cancel()
                    try { conn.socket.close() } catch (_: IOException) {}
                    broadcastToMembers("$GROUP_MEMBER_LEFT_PREFIX${conn.name}", excludeAddress = null)
                    _events.emit(GroupEvent.MemberLeft(conn.name))
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEARDOWN
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full teardown — called when host or member leaves the group.
     */
    fun disconnectAll() {
        Log.d(TAG, "disconnectAll() — tearing down group")
        teardownInProgress = true
        isActive = false

        if (isHost) {
            // Notify all members that the group is disbanded
            broadcastToMembers(GROUP_DISBANDED, excludeAddress = null)

            // Close accept loop
            acceptJob?.cancel()
            acceptJob = null
            try { serverSocket?.close() } catch (_: IOException) {}
            serverSocket = null

            // Close all member connections
            for ((_, conn) in members) {
                conn.readJob?.cancel()
                try { conn.input.close() } catch (_: IOException) {}
                try { conn.output.close() } catch (_: IOException) {}
                try { conn.socket.close() } catch (_: IOException) {}
            }
            members.clear()
        } else {
            // Member: close host connection
            hostReadJob?.cancel()
            hostReadJob = null
            closeHostSocket()
        }

        hostName = null
        isHost = false

        _events.tryEmit(GroupEvent.GroupDisbanded)

        scope.launch {
            delay(300)
            teardownInProgress = false
        }
    }

    private fun closeHostSocket() {
        try { hostInput?.close() } catch (_: IOException) {}
        hostInput = null
        try { hostOutput?.close() } catch (_: IOException) {}
        hostOutput = null
        try { hostSocket?.close() } catch (_: IOException) {}
        hostSocket = null
    }
}
