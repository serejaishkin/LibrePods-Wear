package me.kavishdevar.librepods.wear.relay

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class PhoneRelayManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneRelay"
        private const val RELAY_APP_URI = "wear://relay/librepods"
        private const val TIMEOUT_MS = 10_000L
    }

    enum class RelayStatus { UNAVAILABLE, CONNECTING, AVAILABLE, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val job: Job = scope.coroutineContext[Job]!!
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val mutableStatus = MutableStateFlow(RelayStatus.UNAVAILABLE)
    val status: StateFlow<RelayStatus> = mutableStatus.asStateFlow()

    private val mutableState = MutableStateFlow(PhoneRelayProtocol.RelayState(
        phoneConnected = false,
        airpodsConnected = false,
    ))
    val state: StateFlow<PhoneRelayProtocol.RelayState> = mutableState.asStateFlow()

    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var phoneNodeId: String? = null
    private var listenerRegistered = false

    fun initialize() {
        Log.i(TAG, "Initializing phone relay...")
        scope.launch {
            discoverPhoneNode()
            registerMessageListener()
            syncStateFromDataLayer()
        }
    }

    suspend fun connectAacp(address: String, name: String): Boolean {
        val nodeId = phoneNodeId ?: discoverPhoneNode()
        if (nodeId == null) {
            Log.e(TAG, "No phone node found")
            mutableStatus.value = RelayStatus.UNAVAILABLE
            return false
        }

        mutableStatus.value = RelayStatus.CONNECTING
        val response = sendCommand(nodeId, PhoneRelayProtocol.Command.Connect(address, name))
        val result = parseResponse(response)
        if (result) {
            mutableStatus.value = RelayStatus.AVAILABLE
            Log.i(TAG, "Phone relay connected")
        } else {
            mutableStatus.value = RelayStatus.ERROR
            Log.e(TAG, "Phone relay connect failed: $response")
        }
        return result
    }

    suspend fun disconnect() {
        phoneNodeId?.let { nodeId ->
            try {
                sendFireAndForget(nodeId, PhoneRelayProtocol.PATH_COMMAND,
                    PhoneRelayProtocol.encodeCommand(PhoneRelayProtocol.Command.Disconnect))
            } catch (e: Exception) {
                Log.d(TAG, "Disconnect send failed: ${e.message}")
            }
        }
        mutableStatus.value = RelayStatus.UNAVAILABLE
        mutableState.value = PhoneRelayProtocol.RelayState(
            phoneConnected = false, airpodsConnected = false)
    }

    suspend fun sendControl(command: PhoneRelayProtocol.Command): Boolean {
        val nodeId = phoneNodeId ?: return false
        return try {
            val response = sendCommand(nodeId, command)
            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "sendControl failed: ${e.message}")
            false
        }
    }

    suspend fun requestState() {
        val nodeId = phoneNodeId ?: return
        try {
            sendFireAndForget(nodeId, PhoneRelayProtocol.PATH_COMMAND,
                PhoneRelayProtocol.encodeCommand(PhoneRelayProtocol.Command.RequestState))
        } catch (e: Exception) {
            Log.d(TAG, "requestState failed: ${e.message}")
        }
    }

    suspend fun isPhoneAvailable(): Boolean = discoverPhoneNode() != null

    // ── Internal ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun discoverPhoneNode(): String? {
        return try {
            val nodes = withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine<List<Node>> { cont ->
                    nodeClient.connectedNodes.addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(emptyList()) }
                }
            }
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            phoneNodeId = phone?.id
            if (phone != null) {
                Log.i(TAG, "Phone node found: ${phone.id}")
                mutableStatus.value = RelayStatus.AVAILABLE
            } else {
                Log.w(TAG, "No phone nodes found (nodes=${nodes.size})")
                mutableStatus.value = RelayStatus.UNAVAILABLE
            }
            phone?.id
        } catch (e: Exception) {
            Log.e(TAG, "discoverPhoneNode failed: ${e.message}")
            mutableStatus.value = RelayStatus.UNAVAILABLE
            null
        }
    }

    private fun registerMessageListener() {
        if (listenerRegistered) return
        messageClient.addListener { messageEvent: MessageEvent ->
            handleIncomingMessage(messageEvent)
        }
        listenerRegistered = true
        Log.d(TAG, "Message listener registered")
    }

    private fun handleIncomingMessage(event: MessageEvent) {
        when (event.path) {
            PhoneRelayProtocol.PATH_STATE -> {
                try {
                    val json = String(event.data)
                    val newState = PhoneRelayProtocol.decodeState(json)
                    mutableState.value = newState
                    Log.d(TAG, "State update: phone=${newState.phoneConnected} airpods=${newState.airpodsConnected}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse state: ${e.message}")
                }
            }
            PhoneRelayProtocol.PATH_ERROR -> {
                val msg = String(event.data)
                Log.e(TAG, "Phone relay error: $msg")
                mutableState.value = mutableState.value.copy(
                    phoneConnected = false, airpodsConnected = false)
            }
            PhoneRelayProtocol.PATH_HANDSHAKE -> {
                Log.i(TAG, "Phone handshake received")
                mutableStatus.value = RelayStatus.AVAILABLE
            }
            else -> {
                val requestId = event.path.removePrefix(PhoneRelayProtocol.PATH_PREFIX + "response/")
                pendingResponses[requestId]?.complete(String(event.data))
            }
        }
    }

    private fun syncStateFromDataLayer() {
        scope.launch {
            try {
                val dataItem = suspendCancellableCoroutine { cont ->
                    dataClient.getDataItem(
                        Uri.Builder().scheme("wear").path(RELAY_APP_URI).build()
                    ).addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
                if (dataItem != null && dataItem.data != null) {
                    val json = String(dataItem.data!!)
                    mutableState.value = PhoneRelayProtocol.decodeState(json)
                    Log.d(TAG, "Synced state from Data Layer")
                }
            } catch (e: Exception) {
                Log.d(TAG, "No existing Data Layer state: ${e.message}")
            }
        }
    }

    private suspend fun sendCommand(nodeId: String, command: PhoneRelayProtocol.Command): String {
        val requestId = "${System.currentTimeMillis()}_${command.type}"
        val deferred = CompletableDeferred<String>()
        pendingResponses[requestId] = deferred

        try {
            val data = PhoneRelayProtocol.encodeCommand(command).toByteArray()
            sendFireAndForget(nodeId, PhoneRelayProtocol.PATH_COMMAND, data)
            Log.d(TAG, "Sent command: ${command.type} (requestId=$requestId)")

            return withTimeout(TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed: ${e.message}")
            return "ERROR: ${e.message}"
        } finally {
            pendingResponses.remove(requestId)
        }
    }

    private suspend fun sendFireAndForget(nodeId: String, path: String, data: String) {
        sendFireAndForget(nodeId, path, data.toByteArray())
    }

    private suspend fun sendFireAndForget(nodeId: String, path: String, data: ByteArray) {
        suspendCancellableCoroutine<Unit> { cont ->
            messageClient.sendMessage(nodeId, path, data)
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resume(Unit) }
        }
    }

    private fun parseResponse(response: String): Boolean {
        return try {
            val json = org.json.JSONObject(response)
            json.optBoolean("success", false)
        } catch (e: Exception) {
            response == "TIMEOUT" || response.contains("true", ignoreCase = true)
        }
    }

    fun destroy() {
        try {
            job.cancel()
        } catch (_: Exception) {}
    }
}
