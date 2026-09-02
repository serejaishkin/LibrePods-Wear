package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Performs a single bounded GATT battery read.
 *
 * This reader deliberately does not poll. The controller owns when a fallback
 * read is appropriate and keeps AACP as the authoritative battery source.
 */
class BLEGattBatteryReader(private val context: Context) {

    interface BatteryCallback {
        fun onBatteryRead(left: Int?, right: Int?, case: Int?)
        fun onReadFailed(reason: String)
    }

    companion object {
        private const val TAG = "BLEGattBattery"
        private val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val APPLE_BATTERY_SERVICE_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 800L
    }

    private var gatt: BluetoothGatt? = null
    private var callback: BatteryCallback? = null
    private var targetAddress: String? = null
    private var transportIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var finished = false

    private val connectTimeoutRunnable = Runnable {
        if (!connected && !finished) {
            Log.w(TAG, "GATT connect timeout (transport=$currentTransport)")
            tryNextTransportOrFail("GATT connect timeout")
        }
    }

    private val currentTransport: Int
        get() = TRANSPORT_ORDER.getOrElse(transportIndex) { BluetoothDevice.TRANSPORT_AUTO }

    @SuppressLint("MissingPermission")
    fun readBattery(address: String, cb: BatteryCallback) {
        if (gatt != null) {
            Log.d(TAG, "Already connected/connecting, skipping duplicate read")
            return
        }

        callback = cb
        targetAddress = address
        transportIndex = 0
        connected = false
        finished = false
        connectWithCurrentTransport()
    }

    @SuppressLint("MissingPermission")
    private fun connectWithCurrentTransport() {
        val address = targetAddress ?: return
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            finishWithFailure("Device not found")
            return
        }

        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)

        Log.d(TAG, "Connecting GATT via transport=$currentTransport")
        gatt = device.connectGatt(context, false, gattCallback, currentTransport)
    }

    @SuppressLint("MissingPermission")
    private fun tryNextTransportOrFail(reason: String) {
        handler.removeCallbacks(connectTimeoutRunnable)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        connected = false

        if (transportIndex < TRANSPORT_ORDER.lastIndex) {
            transportIndex++
            Log.d(TAG, "Retrying GATT with transport=$currentTransport after: $reason")
            connectWithCurrentTransport()
            return
        }
        finishWithFailure(reason)
    }

    private fun finishWithFailure(reason: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(connectTimeoutRunnable)
        Log.w(TAG, "GATT battery read failed: $reason")
        val cb = callback
        cleanup()
        cb?.onReadFailed(reason)
    }

    private fun finishWithBattery(left: Int?, right: Int?, case: Int?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(connectTimeoutRunnable)
        Log.i(TAG, "GATT battery read: L=$left R=$right C=$case")
        val cb = callback
        cleanup()
        cb?.onBatteryRead(left, right, case)
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        handler.removeCallbacks(connectTimeoutRunnable)
        connected = false
        finished = true
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing GATT: ${e.message}")
        }
        gatt = null
        callback = null
        targetAddress = null
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        if (!gatt.discoverServices()) {
            tryNextTransportOrFail("Service discovery request failed")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                Log.d(TAG, "GATT connected (status=$status transport=$currentTransport)")
                handler.postDelayed({ discoverServices(gatt) }, SERVICE_DISCOVERY_DELAY_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT disconnected: status=$status newState=$newState transport=$currentTransport")
                if (!finished) tryNextTransportOrFail("GATT disconnected: status=$status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                tryNextTransportOrFail("Service discovery failed: $status")
                return
            }

            val services = gatt.services.map { it.uuid.toString() }
            Log.d(TAG, "GATT services: $services")

            val standardBattery = gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
            if (standardBattery != null) {
                if (!gatt.readCharacteristic(standardBattery)) {
                    tryNextTransportOrFail("Standard battery read request failed")
                }
                return
            }

            val appleService = gatt.getService(APPLE_BATTERY_SERVICE_UUID)
            if (appleService != null) {
                val readable = appleService.characteristics.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                }
                if (readable != null && gatt.readCharacteristic(readable)) return
            }

            tryNextTransportOrFail("No readable battery characteristic found")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            onCharacteristicReadImpl(gatt, characteristic, status, characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onCharacteristicReadImpl(gatt, characteristic, status, value)
        }

        private fun onCharacteristicReadImpl(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
            value: ByteArray?,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                tryNextTransportOrFail("Characteristic read failed: $status")
                return
            }

            val data = value ?: characteristic.value
            if (data == null || data.isEmpty()) {
                tryNextTransportOrFail("Empty battery data")
                return
            }

            if (characteristic.uuid == BATTERY_LEVEL_UUID && data.size == 1) {
                // Standard Battery Service exposes one level only. Do not
                // invent L/R/Case mapping; preserve existing values in state.
                finishWithBattery(left = null, right = null, case = data[0].toInt() and 0xFF)
                return
            }

            val parsed = parseAppleBatteryPayload(data)
            if (parsed != null) {
                finishWithBattery(parsed.first, parsed.second, parsed.third)
            } else {
                tryNextTransportOrFail("Unrecognized battery payload (${data.size} bytes)")
            }
        }
    }

    private fun parseAppleBatteryPayload(data: ByteArray): Triple<Int?, Int?, Int?>? {
        if (data.size < 3) return null

        fun decodeNibble(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            else -> null
        }

        val left = decodeNibble(data[0].toInt() and 0x0F)
        val right = decodeNibble((data[0].toInt() shr 4) and 0x0F)
        val case = decodeNibble(data[1].toInt() and 0x0F)
        return if (left != null || right != null || case != null) Triple(left, right, case) else null
    }

    fun isConnected(): Boolean = connected
}

private val TRANSPORT_ORDER = intArrayOf(
    BluetoothDevice.TRANSPORT_AUTO,
    BluetoothDevice.TRANSPORT_LE,
    BluetoothDevice.TRANSPORT_BREDR,
)
