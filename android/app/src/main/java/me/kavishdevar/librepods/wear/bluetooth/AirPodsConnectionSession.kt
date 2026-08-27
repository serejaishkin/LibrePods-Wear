package me.kavishdevar.librepods.wear.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

/**
 * Owns the direct AirPods L2CAP transport for the Wear application.
 *
 * AACP is the first transport we bring up because battery/ear state and
 * control notifications are carried on the classic L2CAP PSM 0x1001.
 * ATT remains an optional second transport for later features.
 */
class AirPodsConnectionSession(
    private val adapter: BluetoothAdapter,
) : AirPodsProtocolTransport {
    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

    companion object {
        private const val TAG = "AirPodsConnection"
        const val AACP_PSM = 0x1001
        // AirPods AACP service UUID - used for L2CAP socket creation
        private val AACP_UUID = ParcelUuid(UUID.fromString("74EC2172-0BAD-4D01-8F77-997B2BE0722A"))
    }

    private val mutableState = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = mutableState.asStateFlow()

    var aacpSocket: BluetoothSocket? = null
        private set
    var attSocket: BluetoothSocket? = null
        private set

    override val aacpInput get() = requireSocket(aacpSocket).inputStream
    override val aacpOutput get() = requireSocket(aacpSocket).outputStream
    override val attInput get() = requireSocket(attSocket).inputStream
    override val attOutput get() = requireSocket(attSocket).outputStream

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connectAacp(device: BluetoothDevice) {
        if (mutableState.value == State.CONNECTING || mutableState.value == State.CONNECTED) return
        mutableState.value = State.CONNECTING
        closeSockets()

        try {
            val socket = createL2capSocket(device, AACP_UUID, AACP_PSM)
            Log.d(TAG, "Socket created, attempting connect() to ${device.address} on PSM 0x${AACP_PSM.toString(16)}")
            socket.connect()
            aacpSocket = socket
            mutableState.value = State.CONNECTED
            Log.i(TAG, "AACP L2CAP connected to ${device.address} on PSM 0x${AACP_PSM.toString(16)}")
        } catch (error: Throwable) {
            Log.e(TAG, "AACP L2CAP connection failed: ${error.javaClass.simpleName}: ${error.message}", error)
            closeSockets()
            mutableState.value = State.FAILED
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(
        device: BluetoothDevice,
        aacpUuid: ParcelUuid,
        aacpPsm: Int,
        attUuid: ParcelUuid,
        attPsm: Int,
    ) {
        if (mutableState.value == State.CONNECTING || mutableState.value == State.CONNECTED) return
        mutableState.value = State.CONNECTING
        closeSockets()

        try {
            adapter.cancelDiscovery()
            val newAacp = createL2capSocket(device, aacpUuid, aacpPsm)
            newAacp.connect()
            aacpSocket = newAacp

            try {
                val newAtt = createL2capSocket(device, attUuid, attPsm)
                newAtt.connect()
                attSocket = newAtt
            } catch (attError: IOException) {
                closeSockets()
                throw attError
            }

            mutableState.value = State.CONNECTED
        } catch (error: Throwable) {
            closeSockets()
            mutableState.value = State.FAILED
            throw error
        }
    }

    @Synchronized
    fun reconnectAacp(device: BluetoothDevice) {
        disconnect()
        connectAacp(device)
    }

    @Synchronized
    fun reconnect(
        device: BluetoothDevice,
        aacpUuid: ParcelUuid,
        aacpPsm: Int,
        attUuid: ParcelUuid,
        attPsm: Int,
    ) {
        disconnect()
        connect(device, aacpUuid, aacpPsm, attUuid, attPsm)
    }

    @Synchronized
    fun disconnect() {
        if (mutableState.value == State.IDLE) return
        mutableState.value = State.DISCONNECTING
        closeSockets()
        mutableState.value = State.IDLE
    }

    @Synchronized
    fun close() = disconnect()

    private fun requireSocket(socket: BluetoothSocket?): BluetoothSocket =
        socket ?: throw IllegalStateException("AirPods transport is not connected")

    private fun closeSockets() {
        runCatching { aacpSocket?.close() }
        runCatching { attSocket?.close() }
        aacpSocket = null
        attSocket = null
    }

    @Suppress("DEPRECATION")
    private fun createL2capSocket(
        device: BluetoothDevice,
        uuid: ParcelUuid,
        psm: Int,
    ): BluetoothSocket {
        // Log available constructors for debugging
        val constructors = BluetoothSocket::class.java.declaredConstructors
        Log.d(TAG, "BluetoothSocket has ${constructors.size} constructors")
        constructors.forEachIndexed { index, constructor ->
            val params = constructor.parameterTypes.joinToString(", ") { it.simpleName }
            Log.d(TAG, "Constructor $index: ($params)")
        }

        // Try the public L2CAP channel API first (API 29+)
        if (psm == AACP_PSM) {
            runCatching {
                val socket = device.createInsecureL2capChannel(psm)
                Log.d(TAG, "Using public createInsecureL2capChannel for PSM 0x${psm.toString(16)}")
                return socket
            }.onFailure {
                Log.d(TAG, "Insecure L2CAP channel API failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            runCatching {
                val socket = device.createL2capChannel(psm)
                Log.d(TAG, "Using public createL2capChannel for PSM 0x${psm.toString(16)}")
                return socket
            }.onFailure {
                Log.d(TAG, "Public L2CAP channel API failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        // Fall back to reflection-based constructors (matches reference repo approach)
        val type = 3 // L2CAP socket type
        val constructorSpecs: List<Array<Any>> = listOf(
            arrayOf(adapter, device, type, true, true, psm, uuid),
            arrayOf(device, type, true, true, psm, uuid),
            arrayOf(device, type, 1, true, true, psm, uuid),
            arrayOf(type, 1, true, true, device, psm, uuid),
            arrayOf(type, true, true, device, psm, uuid),
        )
        var lastException: Exception? = null
        for ((index, params) in constructorSpecs.withIndex()) {
            try {
                val parameterTypes = params.map { it::class.javaPrimitiveType ?: it::class.java }.toTypedArray()
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*parameterTypes)
                constructor.isAccessible = true
                Log.d(TAG, "Using L2CAP socket constructor #${index + 1} for PSM 0x${psm.toString(16)}")
                return constructor.newInstance(*params) as BluetoothSocket
            } catch (error: Exception) {
                lastException = error
                Log.d(TAG, "L2CAP constructor #${index + 1} failed: ${error.message}")
            }
        }
        throw lastException ?: IllegalStateException("No compatible L2CAP BluetoothSocket constructor found")
    }
}
