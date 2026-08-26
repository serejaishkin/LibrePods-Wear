package me.kavishdevar.librepods.wear.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kavishdevar.librepods.wear.service.LibrePodsWearService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            LibrePodsWearService.start(context)
        }
    }
}