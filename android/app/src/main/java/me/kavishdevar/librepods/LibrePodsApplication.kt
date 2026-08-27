package me.kavishdevar.librepods

import android.app.Application
import android.util.Log

/**
 * Application entry point for the autonomous Wear OS build.
 *
 * Platform-specific initialization is intentionally kept minimal. Bluetooth
 * and AirPods protocol lifecycle belongs to the dedicated service layer.
 */
class LibrePodsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { System.loadLibrary("bluetooth_socket") }
            .onSuccess { Log.i(TAG, "Loaded bluetooth_socket native helper") }
            .onFailure { Log.w(TAG, "bluetooth_socket native helper unavailable", it) }
    }

    companion object {
        private const val TAG = "LibrePodsApp"
    }
}
