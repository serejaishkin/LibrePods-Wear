package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Reads AirPods battery levels via the standard BLE Battery Service (0x180F).
 *
 * This works even when AirPods are connected via classic Bluetooth and don't
 * advertise Apple manufacturer data. AirPods expose the Battery Service over
 * GATT for any connected client.
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
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val READ_DELAY_MS = 1500L
    }

    private var gatt: BluetoothGatt? = null
    private var callback: BatteryCallback? = null
    private var targetAddress: String? = null
    private var readAttempted = false
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false

    private val connectTimeoutRunnable = Runnable {
        if (!connected) {
            Log.w(TAG, "GATT connect timeout for $targetAddress")
            cleanup()
            callback?.onReadFailed("GATT connect timeout")
        }
    }

    @SuppressLint("MissingPermission")
    fun readBattery(address: String, cb: BatteryCallback) {
        if (connected || gatt != null) {
            Log.d(TAG, "Already connected/connecting, skipping")
            return
        }

        callback = cb
        targetAddress = address
        readAttempted = false

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            cb.onReadFailed("Device not found: $address")
            return
        }

        Log.d(TAG, "Connecting GATT to $address")
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        handler.removeCallbacks(connectTimeoutRunnable)
        connected = false
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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                Log.d(TAG, "GATT connected to ${gatt.device?.address}")
                handler.postDelayed({ discoverServices(gatt) }, READ_DELAY_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                Log.w(TAG, "GATT disconnected: status=$status newState=$newState")
                cleanup()
                callback?.onReadFailed("GATT disconnected: status=$status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                cleanup()
                callback?.onReadFailed("Service discovery failed: $status")
                return
            }

            val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
            if (batteryService == null) {
                Log.w(TAG, "Battery service not found on ${gatt.device?.address}")
                val services = gatt.services.map { it.uuid.toString() }
                Log.d(TAG, "Available services: $services")
                cleanup()
                callback?.onReadFailed("Battery service not found")
                return
            }

            val batteryLevelChar = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
            if (batteryLevelChar == null) {
                Log.w(TAG, "Battery level characteristic not found")
                cleanup()
                callback?.onReadFailed("Battery level characteristic not found")
                return
            }

            readAttempted = true
            Log.d(TAG, "Reading battery level characteristic")
            gatt.readCharacteristic(batteryLevelChar)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onCharacteristicReadImpl(gatt, characteristic, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            onCharacteristicReadImpl(gatt, characteristic, status, value)
        }

        private fun onCharacteristicReadImpl(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
            value: ByteArray? = null
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic read failed: $status")
                cleanup()
                callback?.onReadFailed("Characteristic read failed: $status")
                return
            }

            val data = value ?: characteristic.value
            if (data != null && data.isNotEmpty()) {
                val batteryLevel = data[0].toInt() and 0xFF
                Log.i(TAG, "Battery level read: $batteryLevel% from ${gatt.device?.address}")
                cleanup()
                callback?.onBatteryRead(left = null, right = null, case = batteryLevel)
            } else {
                Log.w(TAG, "Empty battery data")
                cleanup()
                callback?.onReadFailed("Empty battery data")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                val batteryLevel = value[0].toInt() and 0xFF
                Log.d(TAG, "Battery level changed: $batteryLevel%")
                callback?.onBatteryRead(left = null, right = null, case = batteryLevel)
            }
        }
    }

    fun isConnected(): Boolean = connected
}
