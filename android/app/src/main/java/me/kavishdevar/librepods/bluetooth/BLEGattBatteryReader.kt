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
import java.util.ArrayDeque
import java.util.UUID

/**
 * One-shot GATT battery read for Wear OS.
 *
 * AirPods often expose the standard Battery Service as a single headset
 * percentage. When several 0x2A19 characteristics exist they are mapped
 * left / right / case in discovery order.
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
        private const val SERVICE_DISCOVERY_DELAY_MS = 400L
    }

    private var gatt: BluetoothGatt? = null
    private var callback: BatteryCallback? = null
    private var targetAddress: String? = null
    private var transportIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var finished = false
    private val pendingReads = ArrayDeque<BluetoothGattCharacteristic>()
    private val collectedLevels = mutableListOf<Int>()

    private val connectTimeoutRunnable = Runnable {
        if (!connected && !finished) {
            Log.w(TAG, "GATT connect timeout (transport=$currentTransport)")
            tryNextTransportOrFail("GATT connect timeout")
        }
    }

    private val currentTransport: Int
        get() = TRANSPORT_ORDER.getOrElse(transportIndex) { BluetoothDevice.TRANSPORT_LE }

    @SuppressLint("MissingPermission")
    fun readBattery(address: String, cb: BatteryCallback) {
        if (gatt != null) {
            Log.d(TAG, "Already connected/connecting, skipping duplicate read")
            cb.onReadFailed("GATT busy")
            return
        }

        callback = cb
        targetAddress = address
        transportIndex = 0
        connected = false
        finished = false
        pendingReads.clear()
        collectedLevels.clear()
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

        Log.i(TAG, "Connecting GATT to $address via transport=$currentTransport")
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
        pendingReads.clear()
        collectedLevels.clear()

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
        pendingReads.clear()
        collectedLevels.clear()
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

    @SuppressLint("MissingPermission")
    private fun readNext(gatt: BluetoothGatt) {
        val next = pendingReads.pollFirst()
        if (next == null) {
            emitCollected()
            return
        }
        if (!gatt.readCharacteristic(next)) {
            Log.w(TAG, "readCharacteristic failed for ${next.uuid}, continuing")
            readNext(gatt)
        }
    }

    private fun emitCollected() {
        when (collectedLevels.size) {
            0 -> tryNextTransportOrFail("No battery values read")
            1 -> {
                val headset = collectedLevels[0]
                finishWithBattery(left = headset, right = headset, case = null)
            }
            2 -> finishWithBattery(collectedLevels[0], collectedLevels[1], null)
            else -> finishWithBattery(collectedLevels[0], collectedLevels[1], collectedLevels[2])
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                Log.i(TAG, "GATT connected (status=$status transport=$currentTransport)")
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
            Log.i(TAG, "GATT services: $services")

            val batteryChars = gatt.services.flatMap { service ->
                service.characteristics.filter { characteristic ->
                    characteristic.uuid == BATTERY_LEVEL_UUID &&
                        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                }
            }
            if (batteryChars.isNotEmpty()) {
                Log.i(TAG, "Found ${batteryChars.size} battery characteristic(s)")
                pendingReads.clear()
                pendingReads.addAll(batteryChars)
                collectedLevels.clear()
                readNext(gatt)
                return
            }

            val appleService = gatt.getService(APPLE_BATTERY_SERVICE_UUID)
            val appleReadable = appleService?.characteristics?.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            }
            if (appleReadable != null && gatt.readCharacteristic(appleReadable)) return

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
                Log.w(TAG, "Characteristic read failed: $status uuid=${characteristic.uuid}")
                readNext(gatt)
                return
            }

            val data = value ?: characteristic.value
            if (data == null || data.isEmpty()) {
                readNext(gatt)
                return
            }

            if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                val level = data[0].toInt() and 0xFF
                if (level in 0..100) {
                    Log.i(TAG, "Battery characteristic ${characteristic.uuid}: $level%")
                    collectedLevels += level
                }
                readNext(gatt)
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
        if (data.size < 2) return null

        fun decodeNibble(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            else -> null
        }

        val left = decodeNibble(data[0].toInt() and 0x0F)
        val right = decodeNibble((data[0].toInt() shr 4) and 0x0F)
        val case = if (data.size >= 2) decodeNibble(data[1].toInt() and 0x0F) else null
        return if (left != null || right != null || case != null) Triple(left, right, case) else null
    }

    fun isConnected(): Boolean = connected
}

private val TRANSPORT_ORDER = intArrayOf(
    BluetoothDevice.TRANSPORT_LE,
    BluetoothDevice.TRANSPORT_AUTO,
    BluetoothDevice.TRANSPORT_BREDR,
)
