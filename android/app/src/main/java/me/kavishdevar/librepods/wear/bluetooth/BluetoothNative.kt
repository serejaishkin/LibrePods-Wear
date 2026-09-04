package me.kavishdevar.librepods.wear.bluetooth

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

object BluetoothNative {
    private const val TAG = "BluetoothNative"

    init {
        System.loadLibrary("bluetooth_socket")
    }

    @JvmStatic
    external fun createNativeL2capSocket(address: String, psm: Int): ParcelFileDescriptor?

    fun createNativeL2capStreams(address: String, psm: Int): NativeL2capSocket? {
        val pfd = createNativeL2capSocket(address, psm) ?: return null
        val fd = pfd.fileDescriptor
        return NativeL2capSocket(
            parcelFileDescriptor = pfd,
            inputStream = FileInputStream(fd),
            outputStream = FileOutputStream(fd),
        )
    }

    class NativeL2capSocket(
        val parcelFileDescriptor: ParcelFileDescriptor,
        val inputStream: FileInputStream,
        val outputStream: FileOutputStream,
    ) : AutoCloseable {
        override fun close() {
            runCatching { inputStream.close() }
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }
}
