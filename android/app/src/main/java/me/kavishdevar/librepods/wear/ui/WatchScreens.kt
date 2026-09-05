package me.kavishdevar.librepods.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import me.kavishdevar.librepods.wear.core.ListeningMode

/** Reusable compact Wear controls bound to the AirPods controller state. */
@Composable
fun ListeningModeRow(
    selected: ListeningMode,
    modifier: Modifier = Modifier,
    onSelected: (ListeningMode) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        ListeningMode.entries.forEach { mode ->
            if (mode == selected) {
                MaterialCompactButton(
                    text = mode.shortLabel,
                    onClick = { onSelected(mode) },
                    modifier = Modifier.weight(1f),
                    backgroundColor = LibrePodsColors.Blue600,
                    textColor = Color.White
                )
            } else {
                MaterialCompactButton(
                    text = mode.shortLabel,
                    onClick = { onSelected(mode) },
                    modifier = Modifier.weight(1f),
                    backgroundColor = LibrePodsColors.Neutral800,
                    textColor = LibrePodsColors.Neutral300
                )
            }
        }
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = LibrePodsColors.Neutral300,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        MaterialToggleSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** Shown while the UI is waiting for the AirPods service or permissions. */
@Composable
fun StartupScreen(message: String, onOpenSettings: () -> Unit) {
    MaterialTheme {
        AppScaffold {
            ScreenScaffold {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    message,
                    color = LibrePodsColors.Neutral300,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                MaterialPrimaryButton(
                    text = "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            }
        }
    }
}

private val ListeningMode.shortLabel: String
    get() = when (this) {
        ListeningMode.ANC -> "ANC"
        ListeningMode.TRANSPARENCY -> "Trns"
        ListeningMode.OFF -> "Off"
    }
