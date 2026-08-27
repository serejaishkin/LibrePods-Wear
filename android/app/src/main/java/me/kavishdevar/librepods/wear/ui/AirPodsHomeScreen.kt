package me.kavishdevar.librepods.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.wear.bluetooth.WearBluetoothScanner
import me.kavishdevar.librepods.wear.core.AirPodsController
import me.kavishdevar.librepods.wear.core.AirPodsDevice
import me.kavishdevar.librepods.wear.core.AirPodsState
import me.kavishdevar.librepods.wear.core.MicMode

/** Wear home screen: status, battery, AirPods controls and paired device list. */
@Composable
fun AirPodsHomeScreen(
    controller: AirPodsController,
    scanner: WearBluetoothScanner,
    onOpenSystemBluetooth: () -> Unit,
    onRefresh: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val devices by scanner.devices.collectAsState()
    val listState = rememberScalingLazyListState()
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        MaterialTheme {
            AppScaffold {
                ScreenScaffold {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        RenameAirPodsDialog(
                            currentName = state.deviceName,
                            onRename = { newName ->
                                if (!controller.renameAirPods(newName)) controller.onError("Failed to rename AirPods")
                                showRenameDialog = false
                            },
                            onDismiss = { showRenameDialog = false },
                        )
                    }
                }
            }
        }
        return
    }

    MaterialTheme {
        AppScaffold {
            ScreenScaffold(scrollState = listState) { contentPadding ->
            ScalingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item { ListHeader { Text(state.deviceName) } }
                item { StatusText(state) }

                if (state.connected || state.leftBattery != null || state.rightBattery != null || state.caseBattery != null) {
                    item { BatteryRow(state) }
                }

                if (state.connected) {
                    item {
                        ListeningModeRow(selected = state.listeningMode) { mode ->
                            if (!controller.setListeningMode(mode)) controller.onError("Failed to set listening mode")
                        }
                    }
                    item {
                        ToggleRow("Ear detection", state.earDetectionEnabled == true) { enabled ->
                            if (!controller.setEarDetection(enabled)) controller.onError("Failed to set ear detection")
                        }
                    }
                    item {
                        ToggleRow("Conversation", state.conversationalAwarenessEnabled == true) { enabled ->
                            if (!controller.setConversationalAwareness(enabled)) controller.onError("Failed to set conversation awareness")
                        }
                    }
                    item { EarStatusText(state) }

                    item { ListHeader { Text("Settings") } }
                    state.adaptiveStrengthPercent()?.let { percent ->
                        item {
                            StepperRow("Adaptive strength", percent) { value ->
                                if (!controller.setControlByte(ControlCommandIdentifiers.AUTO_ANC_STRENGTH, 100 - value)) {
                                    controller.onError("Failed to set adaptive strength")
                                }
                            }
                        }
                    }
                    settingItems(state, controller)
                    stemActionItems(controller)
                    attFeatureItems(state, controller)
                    advancedAudioItems(state, controller)
                    gestureControlItems(state, controller)
                    advancedSettingsItems(state, controller)
                    infoItems(state)

                    item {
                        Button(onClick = { controller.refreshState() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Refresh state")
                        }
                    }
                    item {
                        Button(onClick = { showRenameDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Rename AirPods")
                        }
                    }
                    item {
                        Button(onClick = { controller.forgetDevice() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Forget device")
                        }
                    }
                    item {
                        Button(onClick = { controller.resetSettings() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Reset settings")
                        }
                    }
                    item {
                        Button(onClick = { controller.checkFirmwareUpdates() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Check updates")
                        }
                    }
                    if (state.firmwareUpdateAvailable) {
                        item {
                            Text("Update available: ${state.firmwareUpdateVersion}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item {
                        Button(onClick = { controller.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Disconnect")
                        }
                    }
                } else {
                    item { ListHeader { Text("Paired devices") } }
                    if (devices.isEmpty()) {
                        item { Text("No paired devices", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    }
                    deviceItems(devices, controller)
                    item {
                        Button(onClick = onOpenSystemBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Pair in settings") }
                    }
                    item {
                        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                    }
                }
                }
            }
        }
    }
}

/** Model, firmware and serial reported by the AirPods device information packet. */
private fun ScalingLazyListScope.infoItems(state: AirPodsState) {
    val rows = listOfNotNull(
        state.modelNumber?.let { "Model" to it },
        state.firmwareVersion?.let { "Firmware" to it },
        state.serialNumber?.let { "Serial" to it },
        state.address?.let { "Address" to it },
    )
    if (rows.isEmpty()) return
    item { ListHeader { Text("About") } }
    rows.forEach { (label, value) ->
        item(key = "info_$label") {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Boolean control command toggles; only settings already reported by the AirPods are shown. */
private fun ScalingLazyListScope.settingItems(
    state: AirPodsState,
    controller: AirPodsController,
) {
    BooleanSettings.filter { (_, identifier) -> state.controlValues.containsKey(identifier) }
        .forEach { (label, identifier) ->
            item(key = identifier.name) {
                ToggleRow(label, state.isControlEnabled(identifier)) { enabled ->
                    if (!controller.setControlBoolean(identifier, enabled)) {
                        controller.onError("Failed to set $label")
                    }
                }
            }
        }
}

/** Stem gesture action configuration - simplified for Wear OS. */
private fun ScalingLazyListScope.stemActionItems(
    controller: AirPodsController,
) {
    item { ListHeader { Text("Gestures") } }
    
    // Show only long press actions for both buds as they're most commonly customized
    listOf(
        AACPManager.Companion.StemPressBudType.LEFT to "Left long press",
        AACPManager.Companion.StemPressBudType.RIGHT to "Right long press"
    ).forEach { (budType, label) ->
        item(key = "long_press_${budType.name}") {
            StemActionRow(
                label = label,
                currentAction = controller.getStemAction(budType, AACPManager.Companion.StemPressType.LONG_PRESS)
            ) { action ->
                if (!controller.setStemAction(budType, AACPManager.Companion.StemPressType.LONG_PRESS, action)) {
                    controller.onError("Failed to set stem action")
                }
            }
        }
    }
}

/** ATT-based features: loud sound reduction, hearing aid, transparency settings. */
private fun ScalingLazyListScope.attFeatureItems(
    state: AirPodsState,
    controller: AirPodsController,
) {
    if (!state.attAvailable) return
    
    item { ListHeader { Text("Audio Settings") } }
    
    state.loudSoundReductionEnabled?.let { enabled ->
        item {
            ToggleRow("Loud sound reduction", enabled) { newEnabled ->
                if (!controller.setLoudSoundReduction(newEnabled)) {
                    controller.onError("Failed to set loud sound reduction")
                }
            }
        }
    }
    
    state.hearingAidAmplification?.let { amplification ->
        item {
            StepperRow("Hearing aid", (amplification * 100).toInt().coerceIn(0, 100), step = 5) { value ->
                if (!controller.setHearingAid(value / 100f, state.hearingAidConversationBoost == true)) {
                    controller.onError("Failed to set hearing aid")
                }
            }
        }
    }
    
    state.hearingAidConversationBoost?.let { enabled ->
        item {
            ToggleRow("Conversation boost", enabled) { newEnabled ->
                if (!controller.setHearingAid(state.hearingAidAmplification ?: 0f, newEnabled)) {
                    controller.onError("Failed to set conversation boost")
                }
            }
        }
    }
    
    state.transparencyLevel?.let { level ->
        item {
            StepperRow("Transparency", (level * 100).toInt().coerceIn(0, 100), step = 5) { value ->
                if (!controller.setTransparencyLevel(value / 100f)) {
                    controller.onError("Failed to set transparency level")
                }
            }
        }
    }
}

/** Advanced audio features: custom EQ and head tracking. */
private fun ScalingLazyListScope.advancedAudioItems(
    state: AirPodsState,
    controller: AirPodsController,
) {
    item { ListHeader { Text("Advanced") } }
    
    item {
        ToggleRow("Custom EQ", state.customEqEnabled) { enabled ->
            if (!controller.setCustomEq(enabled, state.customEqLow, state.customEqMid, state.customEqHigh)) {
                controller.onError("Failed to set custom EQ")
            }
        }
    }
    
    if (state.customEqEnabled) {
        item {
            StepperRow("Low band", state.customEqLow, step = 5) { value ->
                if (!controller.setCustomEq(true, value, state.customEqMid, state.customEqHigh)) {
                    controller.onError("Failed to set custom EQ low")
                }
            }
        }
        item {
            StepperRow("Mid band", state.customEqMid, step = 5) { value ->
                if (!controller.setCustomEq(true, state.customEqLow, value, state.customEqHigh)) {
                    controller.onError("Failed to set custom EQ mid")
                }
            }
        }
        item {
            StepperRow("High band", state.customEqHigh, step = 5) { value ->
                if (!controller.setCustomEq(true, state.customEqLow, state.customEqMid, value)) {
                    controller.onError("Failed to set custom EQ high")
                }
            }
        }
    }
    
    item {
        ToggleRow("Head tracking", state.headTrackingEnabled) { enabled ->
            if (!controller.setHeadTracking(enabled)) {
                controller.onError("Failed to set head tracking")
            }
        }
    }
}

/** Gesture and control settings: press speed, hold duration, etc. */
private fun ScalingLazyListScope.gestureControlItems(
    state: AirPodsState,
    controller: AirPodsController,
) {
    item { ListHeader { Text("Controls") } }
    
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("Mic mode", style = MaterialTheme.typography.labelSmall)
            val modeLabels = listOf("Auto", "Right", "Left")
            val modes = listOf(MicMode.AUTO, MicMode.RIGHT, MicMode.LEFT)
            FilledTonalButton(
                onClick = {
                    val currentIndex = modes.indexOf(state.micMode)
                    val nextIndex = (currentIndex + 1) % modes.size
                    if (!controller.setMicMode(modes[nextIndex])) {
                        controller.onError("Failed to set mic mode")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(modeLabels[modes.indexOf(state.micMode)], style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    
    item {
        StepperRow("Press speed", state.doubleClickInterval, step = 1) { value ->
            if (!controller.setPressSpeed(value)) {
                controller.onError("Failed to set press speed")
            }
        }
    }
    
    item {
        StepperRow("Hold duration", state.clickHoldInterval, step = 1) { value ->
            if (!controller.setHoldDuration(value)) {
                controller.onError("Failed to set hold duration")
            }
        }
    }
    
    item {
        StepperRow("Swipe speed", state.volumeSwipeInterval, step = 1) { value ->
            if (!controller.setVolumeSwipeSpeed(value)) {
                controller.onError("Failed to set swipe speed")
            }
        }
    }
    item {
        ToggleRow("Single press answers call", state.callManagementConfig == 1) { enabled ->
            if (!controller.setCallManagement(if (enabled) 1 else 0)) {
                controller.onError("Failed to set call management")
            }
        }
    }
}
private fun ScalingLazyListScope.advancedSettingsItems(
    state: AirPodsState,
    controller: AirPodsController,
) {
    item { ListHeader { Text("More Settings") } }
    
    item {
        StepperRow("Chime volume", state.chimeVolume, step = 10) { value ->
            if (!controller.setChimeVolume(value)) {
                controller.onError("Failed to set chime volume")
            }
        }
    }
    
    item {
        StepperRow("Case tone volume", state.inCaseToneVolume, step = 10) { value ->
            if (!controller.setInCaseToneVolume(value)) {
                controller.onError("Failed to set case tone volume")
            }
        }
    }
    
    item {
        ToggleRow("In case tone", state.inCaseTone) { enabled ->
            // Toggle implementation using control command 0x31
            val sent = controller.setControlByteRaw(0x31, if (enabled) 1 else 2)
            if (!sent) controller.onError("Failed to set in case tone")
        }
    }
    
    item {
        ToggleRow("Raw gestures", state.rawGesturesEnabled) { enabled ->
            // Toggle implementation using control command 0x39
            val sent = controller.setControlByteRaw(0x39, if (enabled) 1 else 2)
            if (!sent) controller.onError("Failed to set raw gestures")
        }
    }
}

private fun ScalingLazyListScope.deviceItems(
    devices: List<AirPodsDevice>,
    controller: AirPodsController,
) {
    devices.forEach { device ->
        item(key = device.address) {
            Button(
                onClick = { controller.connectToDevice(device.address, device.name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (device.bonded) "Paired · tap to connect" else "Nearby",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusText(state: AirPodsState) {
    val status = when {
        state.connecting -> "Connecting… (${state.protocolStage})"
        state.connected -> "Connected"
        state.lastError != null -> state.lastError
        else -> "Not connected"
    }
    Text(
        status.orEmpty(),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EarStatusText(state: AirPodsState) {
    if (state.leftInEar == null && state.rightInEar == null) return
    val text = "In ear: ${state.leftInEar.asEarLabel()} / ${state.rightInEar.asEarLabel()}"
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Boolean?.asEarLabel(): String = when (this) {
    true -> "yes"
    false -> "no"
    null -> "—"
}

@Composable
private fun BatteryRow(state: AirPodsState) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BatteryCell("L", state.leftBattery, state.leftCharging)
        BatteryCell("R", state.rightBattery, state.rightCharging)
        BatteryCell("C", state.caseBattery, state.caseCharging)
    }
}

@Composable
private fun BatteryCell(label: String, level: Int?, charging: Boolean) {
    val value = if (level != null && level in 0..100) "$level%" else "--"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(if (charging && value != "--") "$value +" else value, style = MaterialTheme.typography.bodySmall)
    }
}
