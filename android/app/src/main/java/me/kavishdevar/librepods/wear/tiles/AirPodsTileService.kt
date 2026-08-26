package me.kavishdevar.librepods.wear.tiles

import android.content.Context
import android.content.SharedPreferences
import androidx.wear.tiles.DeviceParameters
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.material.Text
import androidx.wear.tiles.material.layouts.PrimaryLayout
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AirPodsTileService : TileService() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("librepods_wear", Context.MODE_PRIVATE)
    }

    override fun onTileRequest(requestParams: TileBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val deviceParams = requestParams.deviceParameters ?: DeviceParameters.DEVICE_PARAMS_UNKNOWN

        val connected = prefs.getBoolean("connected", false)
        val connecting = prefs.getBoolean("connecting", false)
        val listeningMode = prefs.getString("listening_mode", null)
        val leftBattery = prefs.getInt("left_battery", -1)
        val rightBattery = prefs.getInt("right_battery", -1)
        val caseBattery = prefs.getInt("case_battery", -1)

        val statusText = when {
            connected -> {
                val battery = listOfNotNull(
                    leftBattery.takeIf { it in 0..100 },
                    rightBattery.takeIf { it in 0..100 },
                    caseBattery.takeIf { it in 0..100 }
                ).average().let { if (it.isNaN()) null else it.toInt() }
                
                if (battery != null) "Connected: $battery%" else "Connected"
            }
            connecting -> "Connecting..."
            else -> "Disconnected"
        }

        val modeText = when (listeningMode) {
            "ANC" -> "ANC"
            "TRANSPARENCY" -> "Transparency"
            "OFF" -> "Off"
            else -> ""
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        buildTileLayout(statusText, modeText, deviceParams)
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: TileBuilders.ResourcesRequest): ListenableFuture<TileBuilders.Resources> {
        val resources = TileBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()

        return Futures.immediateFuture(resources)
    }

    private fun buildTileLayout(statusText: String, modeText: String, deviceParams: DeviceParameters): LayoutElementBuilders.LayoutElement {
        return PrimaryLayout.Builder(deviceParams)
            .setClickable(
                LayoutElementBuilders.Clickable.Builder()
                    .setOnClick(
                        LayoutElementBuilders.Action.Builder()
                            .setLaunchAction(
                                LayoutElementBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        LayoutElementBuilders.AndroidActivity.Builder()
                                            .setClassName("me.kavishdevar.librepods.MainActivity")
                                            .setPackageName("me.kavishdevar.librepods.wear")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        Text.Builder(this, statusText)
                            .setColor(LayoutElementBuilders.ColorBuilders.argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, modeText)
                            .setColor(LayoutElementBuilders.ColorBuilders.argb(0xFFAAAAAA.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}