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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class AirPodsConnectionSession(
    private val adapter: BluetoothAdapter,
) : AirPodsProtocolTransport {
    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

    companion object {
        private const val TAG = "AirPodsConnection"
        const val AACP_PSM = 0x1001
        private val AACP_UUID = ParcelUuid(UUID.fromString("74EC2172-0BAD-4D01-8F77-997B2BE0722A"))
    }

    private val mutableState = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = mutableState.asStateFlow()

    var aacpSocket: BluetoothSocket? = null
        private set
    var attSocket: BluetoothSocket? = null
        private set
    private var nativeAacp: BluetoothNative.NativeL2capSocket? = null
    private var nativeAtt: BluetoothNative.NativeL2capSocket? = null

    override val aacpInput: InputStream
        get() = nativeAacp?.inputStream ?: requireSocket(aacpSocket).inputStream
    override val aacpOutput: OutputStream
        get() = nativeAacp?.outputStream ?: requireSocket(aacpSocket).outputStream
    override val attInput: InputStream
        get() = nativeAtt?.inputStream ?: requireSocket(attSocket).inputStream
    override val attOutput: OutputStream
        get() = nativeAtt?.outputStream ?: requireSocket(attSocket).outputStream

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connectAacp(device: BluetoothDevice) {
        if (mutableState.value == State.CONNECTING || mutableState.value == State.CONNECTED) return
        mutableState.value = State.CONNECTING
        closeSockets()

        logDeviceConnectivity(device)

        // Step 1: Try Java BluetoothSocket L2CAP
        try {
            val socket = createL2capSocket(device, AACP_UUID, AACP_PSM)
            Log.d(TAG, "Java L2CAP socket created, attempting connect() to ${device.address}")
            socket.connect()
            aacpSocket = socket
            mutableState.value = State.CONNECTED
            Log.i(TAG, "Java AACP L2CAP connected to ${device.address}")
            return
        } catch (error: Throwable) {
            Log.e(TAG, "Java L2CAP failed: ${error.javaClass.simpleName}: ${error.message}")
        }

        // Step 2: Try RFCOMM diagnostic (different kernel socket type, tests if ANY classic connection works)
        try {
            Log.d(TAG, "Trying RFCOMM diagnostic to ${device.address}")
            val rfcommMethod = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            val rfcommSocket = rfcommMethod.invoke(device, 1) as BluetoothSocket
            rfcommSocket.connect()
            Log.i(TAG, "RFCOMM connected to ${device.address} — classic BT IS reachable!")
            rfcommSocket.close()
        } catch (e: Throwable) {
            Log.e(TAG, "RFCOMM diagnostic failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Step 3: Try native L2CAP (SELinux may block)
        try {
            Log.d(TAG, "Trying native L2CAP to ${device.address} PSM 0x${AACP_PSM.toString(16)}")
            val native = BluetoothNative.createNativeL2capStreams(device.address, AACP_PSM)
            if (native != null) {
                nativeAacp = native
                mutableState.value = State.CONNECTED
                Log.i(TAG, "Native AACP L2CAP connected to ${device.address}")
                return
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Native L2CAP failed: ${e.message}")
        }

        closeSockets()
        mutableState.value = State.FAILED
        throw IOException("L2CAP connection failed — Wear OS daemon does not support classic BT socket connections for third-party apps")
    }

    @SuppressLint("MissingPermission")
    private fun logDeviceConnectivity(device: BluetoothDevice) {
        Log.i(TAG, "=== Device Connectivity Diagnostics ===")
        Log.i(TAG, "Address: ${device.address}")
        Log.i(TAG, "Name: ${device.name}")
        Log.i(TAG, "Type: ${device.type} (1=CLASSIC, 2=LE, 3=DUAL)")

        val isBonded = adapter.bondedDevices?.any { it.address == device.address } == true
        Log.i(TAG, "Bonded: $isBonded")

        runCatching {
            val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
            Log.i(TAG, "isConnected(): $connected")
        }.onFailure { Log.d(TAG, "isConnected() not available: ${it.message}") }

        runCatching {
            val state = BluetoothDevice::class.java.getMethod("getConnectionState").invoke(device) as Int
            val stateStr = when (state) { 0 -> "DISCONNECTED"; 1 -> "CONNECTING"; 2 -> "CONNECTED"; else -> "UNKNOWN($state)" }
            Log.i(TAG, "getConnectionState(): $stateStr")
        }.onFailure { Log.d(TAG, "getConnectionState() not available: ${it.message}") }

        runCatching {
            val uuids = device.uuids
            if (uuids != null) Log.i(TAG, "UUIDs: ${uuids.joinToString { it.uuid.toString() }}")
            else Log.i(TAG, "UUIDs: null")
        }.onFailure { Log.d(TAG, "UUIDs check failed: ${it.message}") }

        Log.i(TAG, "=== End Diagnostics ===")
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

            // AACP: try native first, fallback to Java
            try {
                val native = BluetoothNative.createNativeL2capStreams(device.address, aacpPsm)
                if (native != null) {
                    nativeAacp = native
                } else {
                    val newAacp = createL2capSocket(device, aacpUuid, aacpPsm)
                    newAacp.connect()
                    aacpSocket = newAacp
                }
            } catch (e: Throwable) {
                Log.d(TAG, "AACP native failed, trying Java: ${e.message}")
                val newAacp = createL2capSocket(device, aacpUuid, aacpPsm)
                newAacp.connect()
                aacpSocket = newAacp
            }

            // ATT: try native first, fallback to Java
            try {
                val native = BluetoothNative.createNativeL2capStreams(device.address, attPsm)
                if (native != null) {
                    nativeAtt = native
                } else {
                    val newAtt = createL2capSocket(device, attUuid, attPsm)
                    newAtt.connect()
                    attSocket = newAtt
                }
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
        runCatching { nativeAacp?.close() }
        runCatching { nativeAtt?.close() }
        nativeAacp = null
        nativeAtt = null
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
        val constructors = BluetoothSocket::class.java.declaredConstructors
        Log.d(TAG, "BluetoothSocket has ${constructors.size} constructors")
        constructors.forEachIndexed { index, constructor ->
            val params = constructor.parameterTypes.joinToString(", ") { it.simpleName }
            Log.d(TAG, "Constructor $index: ($params)")
        }

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

        val type = 3
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
