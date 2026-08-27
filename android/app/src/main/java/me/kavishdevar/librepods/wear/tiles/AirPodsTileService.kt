package me.kavishdevar.librepods.wear.tiles

import android.content.Context
import android.content.SharedPreferences
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders.Column
import androidx.wear.tiles.LayoutElementBuilders.FontStyle
import androidx.wear.tiles.LayoutElementBuilders.Layout
import androidx.wear.tiles.LayoutElementBuilders.Text
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AirPodsTileService : TileService() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("librepods_wear", Context.MODE_PRIVATE)
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
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

        val titleElement = Text.Builder()
            .setText(statusText)
            .setFontStyle(
                FontStyle.Builder()
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .setSize(DimensionBuilders.sp(20f))
                    .build()
            )
            .build()

        val columnBuilder = Column.Builder()
            .addContent(titleElement)
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())

        if (modeText.isNotEmpty()) {
            columnBuilder.addContent(
                Text.Builder()
                    .setText(modeText)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setColor(argb(0xFFAAAAAA.toInt()))
                            .setSize(DimensionBuilders.sp(14f))
                            .build()
                    )
                    .build()
            )
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                Layout.Builder()
                                    .setRoot(columnBuilder.build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<androidx.wear.protolayout.ResourceBuilders.Resources> {
        val resources = androidx.wear.protolayout.ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return Futures.immediateFuture(resources)
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
