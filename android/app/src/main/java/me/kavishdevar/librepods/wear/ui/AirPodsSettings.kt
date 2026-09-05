package me.kavishdevar.librepods.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.wear.core.AirPodsState

/**
 * Boolean AirPods settings exposed on the watch.
 *
 * All of them use the same control command encoding: `0x01` enabled,
 * `0x02` disabled, and the AirPods report the current value back with the
 * same identifier.
 */
val BooleanSettings: List<Pair<String, ControlCommandIdentifiers>> = listOf(
    "Adaptive volume" to ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
    "Volume swipe" to ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
    "ANC with one bud" to ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
    "Allow Off mode" to ControlCommandIdentifiers.ALLOW_OFF_OPTION,
    "Auto connect" to ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
    "Sleep detection" to ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
    "Hearing protection" to ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
    "Dynamic end of charge" to ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE,
)

/** A control command value is reported as enabled when its first byte is `0x01`. */
fun AirPodsState.isControlEnabled(identifier: ControlCommandIdentifiers): Boolean =
    controlValues[identifier] == 0x01

/** Adaptive strength is sent inverted: the slider percentage is `100 - rawValue`. */
fun AirPodsState.adaptiveStrengthPercent(): Int? =
    controlValues[ControlCommandIdentifiers.AUTO_ANC_STRENGTH]?.let { 100 - it.coerceIn(0, 100) }

@Composable
fun StepperRow(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    step: Int = 10,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = LibrePodsColors.Neutral500,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialCompactButton(
                text = "-",
                onClick = { onValueChange((value - step).coerceIn(0, 100)) },
                backgroundColor = LibrePodsColors.Neutral800,
                textColor = LibrePodsColors.Neutral300
            )
            Text(
                "$value%",
                color = LibrePodsColors.Neutral300,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            MaterialCompactButton(
                text = "+",
                onClick = { onValueChange((value + step).coerceIn(0, 100)) },
                backgroundColor = LibrePodsColors.Neutral800,
                textColor = LibrePodsColors.Neutral300
            )
        }
    }
}

@Composable
fun StemActionRow(
    label: String,
    currentAction: StemAction,
    modifier: Modifier = Modifier,
    onActionChange: (StemAction) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = LibrePodsColors.Neutral500,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            currentAction.name.replace("_", " "),
            color = LibrePodsColors.Neutral300,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        )
        MaterialSecondaryButton(
            text = "Change action",
            onClick = {
                val currentIndex = StemAction.entries.indexOf(currentAction)
                val nextIndex = (currentIndex + 1) % StemAction.entries.size
                onActionChange(StemAction.entries[nextIndex])
            }
        )
    }
}
