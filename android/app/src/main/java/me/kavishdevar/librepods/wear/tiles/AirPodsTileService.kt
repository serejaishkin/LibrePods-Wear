package me.kavishdevar.librepods.wear.tiles

import android.content.Context
import android.content.SharedPreferences
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AirPodsTileService : TileService() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("librepods_wear", Context.MODE_PRIVATE)
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val deviceParams = requestParams.deviceParameters
            ?: DeviceParametersBuilders.DeviceParameters.Builder().build()

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
                                    .setRoot(buildTileLayout(statusText, modeText))
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
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()

        return Futures.immediateFuture(resources)
    }

    private fun buildTileLayout(
        statusText: String,
        modeText: String
    ): LayoutElementBuilders.LayoutElement {
        val columnChildren = mutableListOf<LayoutElementBuilders.LayoutElement>()

        columnChildren.add(
            LayoutElementBuilders.Text.Builder()
                .setText(statusText)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setColor(
                            ColorBuilders.propType(
                                ColorBuilders.ColorProp.Builder()
                                    .setArgb(0xFFFFFFFF.toInt())
                                    .build()
                            )
                        )
                        .setSize(
                            DimensionBuilders.prop(
                                DimensionBuilders.SpProp.Builder()
                                    .setValue(20f)
                                    .build()
                            )
                        )
                        .build()
                )
                .build()
        )

        if (modeText.isNotEmpty()) {
            columnChildren.add(
                LayoutElementBuilders.Text.Builder()
                    .setText(modeText)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(
                                ColorBuilders.propType(
                                    ColorBuilders.ColorProp.Builder()
                                        .setArgb(0xFFAAAAAA.toInt())
                                        .build()
                                )
                            )
                            .setSize(
                                DimensionBuilders.prop(
                                    DimensionBuilders.SpProp.Builder()
                                        .setValue(14f)
                                        .build()
                                )
                            )
                            .build()
                    )
                    .build()
            )
        }

        return LayoutElementBuilders.Column.Builder()
            .addContent(*columnChildren.toTypedArray())
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setHeight(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
