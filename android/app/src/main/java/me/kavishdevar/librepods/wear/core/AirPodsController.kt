package me.kavishdevar.librepods.wear.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.BLEGattBatteryReader
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.wear.bluetooth.AirPodsProtocolDiagnostics
import me.kavishdevar.librepods.wear.bluetooth.WearBluetoothConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wear-facing controller for the autonomous AirPods stack. */
class AirPodsController(private val context: Context, private val transport: WearBluetoothConnection) {
    private val tag = "AirPodsController"
    private val internalStateStore = AirPodsStateStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("librepods_wear", Context.MODE_PRIVATE)
    val state: StateFlow<AirPodsState> = internalStateStore.state
    val stateStore: AirPodsStateStore = internalStateStore
    private var aacp: AACPManager? = null
    private var ble: BLEManager? = null
    private var att: ATTManager? = null
    private var connectedDevice: BluetoothDevice? = null
    private var aacpReaderJob: Job? = null
    private var readyWatchJob: Job? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private val connectMutex = Mutex()
    private var lastConnectAttempt: Long = 0
    private val CONNECT_COOLDOWN_MS = 3000L
    private val gattBatteryReader = BLEGattBatteryReader(context)
    private var gattPollJob: Job? = null

    private val bleListener = object : BLEManager.AirPodsStatusListener {
        override fun onDeviceStatusChanged(device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?) = applyBleStatus(device)
        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) = applyBleStatus(device)
        override fun onLidStateChanged(lidOpen: Boolean) { internalStateStore.update { it.copy(caseLidOpen = lidOpen) } }
        override fun onEarStateChanged(device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean) = applyBleStatus(device)
        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) = applyBleStatus(device)
        override fun onDeviceDisappeared() { Log.d(tag, "AirPods BLE advertisement disappeared") }
    }

    private val aacpCallback = object : AACPManager.PacketCallback {
        override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
            recordPacket(batteryInfo)
            val parsed = AirPodsProtocolDiagnostics.parseBattery(batteryInfo) ?: return
            val left = parsed.firstOrNull { it.type == AirPodsProtocolDiagnostics.Component.LEFT }
            val right = parsed.firstOrNull { it.type == AirPodsProtocolDiagnostics.Component.RIGHT }
            val case = parsed.firstOrNull { it.type == AirPodsProtocolDiagnostics.Component.CASE }
            internalStateStore.update { it.copy(leftBattery = left?.level ?: it.leftBattery, rightBattery = right?.level ?: it.rightBattery, caseBattery = case?.level ?: it.caseBattery, leftCharging = left?.charging ?: it.leftCharging, rightCharging = right?.charging ?: it.rightCharging, caseCharging = case?.charging ?: it.caseCharging, protocolStage = "READY", connected = true, connecting = false) }
            persistTileState()
        }
        override fun onEarDetectionReceived(earDetection: ByteArray) { recordPacket(earDetection); AirPodsProtocolDiagnostics.parseEarDetection(earDetection)?.let { (left, right) -> onEarDetection(left, right) } }
        override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) { recordPacket(conversationAwareness) }
        override fun onControlCommandReceived(controlCommand: ByteArray) {
            recordPacket(controlCommand)
            runCatching { AACPManager.ControlCommand.fromByteArray(controlCommand) }.onSuccess { command ->
                val identifier = AACPManager.Companion.ControlCommandIdentifiers.fromByte(command.identifier)
                val first = command.value.firstOrNull()?.toInt()?.and(0xFF)
                if (identifier != null && first != null) {
                    internalStateStore.update { it.copy(controlValues = it.controlValues + (identifier to first)) }
                }
                when (identifier) {
                    AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE -> when (command.value.firstOrNull()?.toInt()?.and(0xFF)) { 1 -> onListeningModeChanged(ListeningMode.OFF); 2 -> onListeningModeChanged(ListeningMode.ANC); 3 -> onListeningModeChanged(ListeningMode.TRANSPARENCY); else -> Unit }
                    AACPManager.Companion.ControlCommandIdentifiers.EAR_DETECTION_CONFIG -> internalStateStore.update { it.copy(earDetectionEnabled = command.value.firstOrNull()?.toInt()?.and(0xFF) == 1) }
                    AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG -> internalStateStore.update { it.copy(conversationalAwarenessEnabled = command.value.firstOrNull()?.toInt()?.and(0xFF) == 1) }
                    else -> Unit
                }
            }
        }
        override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) { internalStateStore.update { it.copy(deviceName = deviceInformation.name.ifBlank { it.deviceName }, modelNumber = deviceInformation.modelNumber.ifBlank { null }, firmwareVersion = deviceInformation.version1.ifBlank { null }, serialNumber = deviceInformation.serialNumber.ifBlank { null }, protocolStage = "READY", connected = true, connecting = false) } }
        override fun onHeadTrackingReceived(headTracking: ByteArray) { 
            recordPacket(headTracking)
            // Head tracking packet format: check if enabled based on packet content
            internalStateStore.update { it.copy(headTrackingEnabled = headTracking.isNotEmpty()) }
        }
        override fun onUnknownPacketReceived(packet: ByteArray) { recordPacket(packet) }
        override fun onProximityKeysReceived(proximityKeys: ByteArray) { recordPacket(proximityKeys) }
        override fun onStemPressReceived(stemPress: ByteArray) { recordPacket(stemPress) }
        override fun onAudioSourceReceived(audioSource: ByteArray) { recordPacket(audioSource) }
        override fun onOwnershipChangeReceived(owns: Boolean) { Log.d(tag, "AACP ownership=$owns") }
        override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) { Log.d(tag, "AACP connected devices=${connectedDevices.size}") }
        override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) { Log.d(tag, "AACP ownership revoke requested by $sender") }
        override fun onShowNearbyUI(sender: String) { Log.d(tag, "AACP nearby UI requested by $sender") }
        override fun onHeadphoneAccommodationReceived(eqData: FloatArray) { Log.d(tag, "AACP EQ frame received: ${eqData.size} values") }
        override fun onCustomEqReceived(customEq: CustomEq) { Log.d(tag, "AACP custom EQ received") }
        override fun onCapabilitiesReceived(capabilities: List<Capability>) { Log.d(tag, "AACP capabilities=${capabilities.size}") }
    }

    fun initialize(aacpManager: AACPManager, bleManager: BLEManager) {
        aacp = aacpManager; ble = bleManager
        aacpManager.bindTransport(transport); aacpManager.setPacketCallback(aacpCallback)
        bleManager.setAirPodsStatusListener(bleListener)
        runCatching { bleManager.startScanning() }.onFailure { Log.w(tag, "BLE status scanner could not start", it) }
        scope.launch {
            while (true) {
                delay(15_000)
                val currentBle = internalStateStore.state.value
                if (currentBle.leftBattery == null && currentBle.rightBattery == null && currentBle.caseBattery == null) {
                    Log.i(tag, "BLE: no battery data after 15s, restarting scanner")
                    runCatching { ble?.stopScanning() }
                    delay(500)
                    runCatching { ble?.startScanning() }
                }
            }
        }
    }

  @SuppressLint("MissingPermission")
    fun connectToDevice(address: String, name: String = "AirPods", tryAacp: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (tryAacp && now - lastConnectAttempt < CONNECT_COOLDOWN_MS) {
            Log.w(tag, "connectToDevice: cooldown active, skipping (${(CONNECT_COOLDOWN_MS - (now - lastConnectAttempt))}ms remaining)")
            return false
        }
        if (tryAacp) lastConnectAttempt = now
        manualDisconnect = false
        reconnectJob?.cancel()
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return fail("Bluetooth is unavailable")
            if (!adapter.isEnabled) return fail("Bluetooth is disabled")
            val device = adapter.getRemoteDevice(address)
            connectedDevice = device
            prefs.edit()
                .putString("selected_address", address)
                .putString("selected_name", name)
                .putString("last_connected_address", address)
                .putString("last_connected_name", name)
                .apply()
            connectBondedBleMode(address, name)
            if (tryAacp) {
                markConnecting()
                scope.launch { connectTransport(device) }
            }
            true
        } catch (e: SecurityException) {
            fail("Bluetooth permission is required", e)
        } catch (e: IllegalArgumentException) {
            fail("Invalid Bluetooth device", e)
        }
    }

    /** Connect to a paired device using BLE scan + GATT without attempting L2CAP/AACP. */
    @SuppressLint("MissingPermission")
    fun connectBondedBleMode(address: String, name: String = "AirPods"): Boolean {
        manualDisconnect = false
        reconnectJob?.cancel()
        internalStateStore.update {
            it.copy(
                deviceName = name,
                address = address,
                connecting = false,
                connected = true,
                protocolStage = "BLE_ONLY",
                lastError = null,
            )
        }
        // #region agent log
        Log.i(tag, "DEBUG341ec7 hypothesis=E bonded_ble_mode address=$address name=$name")
        // #endregion
        startGattBatteryPolling(address)
        return true
    }

    /** Explicit AACP/L2CAP connect — only call from the UI button, not on auto-connect. */
    @SuppressLint("MissingPermission")
    fun tryAacpConnect(): Boolean {
        val address = internalStateStore.state.value.address ?: connectedDevice?.address ?: return false
        val name = internalStateStore.state.value.deviceName
        return connectToDevice(address, name, tryAacp = true)
    }

    private fun startGattBatteryPolling(address: String) {
        gattPollJob?.cancel()
        gattPollJob = scope.launch {
            while (!manualDisconnect) {
                readGattBatteryOnce(address)
                delay(60_000)
            }
        }
    }

    private suspend fun readGattBatteryOnce(address: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        gattBatteryReader.readBattery(address, object : BLEGattBatteryReader.BatteryCallback {
            override fun onBatteryRead(left: Int?, right: Int?, case: Int?) {
                applyGattBattery(left, right, case, address)
                latch.countDown()
            }

            override fun onReadFailed(reason: String) {
                Log.w(tag, "GATT battery read failed for $address: $reason")
                latch.countDown()
            }
        })
        latch.await()
    }

    private fun applyGattBattery(left: Int?, right: Int?, case: Int?, address: String) {
        val hasBattery = left != null || right != null || case != null
        if (!hasBattery) return
        internalStateStore.update {
            it.copy(
                address = address,
                leftBattery = left ?: it.leftBattery,
                rightBattery = right ?: it.rightBattery,
                caseBattery = case ?: it.caseBattery,
                connected = true,
                connecting = false,
                protocolStage = if (it.protocolStage == "READY") "READY" else "BLE_ONLY",
                lastError = null,
            )
        }
        persistTileState()
    }

    @SuppressLint("MissingPermission")
    fun connectToBondedAirPods(): Boolean {
        val saved = prefs.getString("selected_address", null)
        if (saved != null) return connectBondedBleMode(saved, prefs.getString("selected_name", "AirPods") ?: "AirPods")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.bondedDevices?.firstOrNull { it.name.orEmpty().contains("AirPods", true) || it.name.orEmpty().contains("Pods", true) } ?: return fail("No paired AirPods found")
        return connectBondedBleMode(device.address, device.name ?: "AirPods")
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectTransport(device: BluetoothDevice) {
        connectMutex.withLock {
            try {
                Log.i(tag, "connectTransport: starting L2CAP connection to ${device.address}")
                // #region agent log
                Log.i(tag, "DEBUG341ec7 hypothesis=A l2cap_start address=${device.address}")
                // #endregion
                internalStateStore.update { it.copy(protocolStage = "L2CAP") }
                transport.connectAacp(device)
                Log.i(tag, "connectTransport: L2CAP connected, starting AACP reader")
                
                val manager = aacp ?: error("AACP manager is not initialized")
                startAacpReader(manager)
                
                if (!manager.startSession()) {
                    onError("AACP handshake could not be sent")
                    runCatching { transport.close() }
                    return
                }
                
                internalStateStore.update { it.copy(protocolStage = "HANDSHAKE_SENT", connecting = true, connected = false) }
                Log.i(tag, "connectTransport: handshake sent, waiting for READY state")
                
                readyWatchJob?.cancel()
                readyWatchJob = scope.launch {
                    repeat(50) {
                        delay(100)
                        if (manager.sessionState == AACPManager.SessionState.READY) {
                            Log.i(tag, "connectTransport: session READY")
                            internalStateStore.update { it.copy(protocolStage = "READY", connecting = false, connected = true, lastError = null) }
                            refreshState()
                            initializeAtt()
                            return@launch
                        }
                    }
                    if (manager.sessionState != AACPManager.SessionState.READY) {
                        onError("AACP handshake timeout (${manager.sessionState})")
                    }
                }
            } catch (e: Throwable) {
                Log.e(tag, "connectTransport failed: ${e.javaClass.simpleName}: ${e.message}", e)
                onError("AACP connection failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}", e)
                runCatching { transport.close() }
            }
        }
    }
    
    private fun initializeAtt() {
        runCatching {
            att = ATTManager(transport)
            att?.startReader()
            att?.setOnNotificationReceived { handle, value ->
                when (handle.toInt()) {
                    ATTHandles.LOUD_SOUND_REDUCTION.value -> {
                        internalStateStore.update { it.copy(loudSoundReductionEnabled = value.firstOrNull()?.toInt() == 1) }
                    }
                    ATTHandles.HEARING_AID.value -> {
                        if (value.size >= 4) {
                            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                            val amplification = buffer.float
                            val conversationBoost = buffer.getFloat(4)
                            internalStateStore.update { it.copy(hearingAidAmplification = amplification, hearingAidConversationBoost = conversationBoost == 1.0f) }
                        }
                    }
                    ATTHandles.TRANSPARENCY.value -> {
                        if (value.size >= 4) {
                            val level = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).float
                            internalStateStore.update { it.copy(transparencyLevel = level) }
                        }
                    }
                }
            }
            internalStateStore.update { it.copy(attAvailable = true) }
            readAttCharacteristics()
        }.onFailure { 
            Log.e(tag, "ATT initialization failed", it)
            internalStateStore.update { it.copy(attAvailable = false) }
        }
    }
    
    private fun readAttCharacteristics() {
        scope.launch {
            att?.getCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION)?.let { data ->
                internalStateStore.update { it.copy(loudSoundReductionEnabled = data.firstOrNull()?.toInt() == 1) }
            }
            att?.getCharacteristic(ATTHandles.HEARING_AID)?.let { data ->
                if (data.size >= 4) {
                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val amplification = buffer.float
                    val conversationBoost = buffer.getFloat(4)
                    internalStateStore.update { it.copy(hearingAidAmplification = amplification, hearingAidConversationBoost = conversationBoost == 1.0f) }
                }
            }
            att?.getCharacteristic(ATTHandles.TRANSPARENCY)?.let { data ->
                if (data.size >= 4) {
                    val level = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).float
                    internalStateStore.update { it.copy(transparencyLevel = level) }
                }
            }
        }
    }
    
    fun setLoudSoundReduction(enabled: Boolean): Boolean {
        try {
            att?.writeCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(if (enabled) 1 else 0))
            internalStateStore.update { it.copy(loudSoundReductionEnabled = enabled) }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to set loud sound reduction", e)
            return false
        }
    }
    
    fun setHearingAid(amplification: Float, conversationBoost: Boolean): Boolean {
        try {
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(amplification)
            buffer.putFloat(if (conversationBoost) 1.0f else 0.0f)
            att?.writeCharacteristic(ATTHandles.HEARING_AID, buffer.array())
            internalStateStore.update { it.copy(hearingAidAmplification = amplification, hearingAidConversationBoost = conversationBoost) }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to set hearing aid", e)
            return false
        }
    }
    
    fun setTransparencyLevel(level: Float): Boolean {
        try {
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(level)
            att?.writeCharacteristic(ATTHandles.TRANSPARENCY, buffer.array())
            internalStateStore.update { it.copy(transparencyLevel = level) }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to set transparency level", e)
            return false
        }
    }
    
    fun setCustomEq(enabled: Boolean, low: Int, mid: Int, high: Int): Boolean {
        val state = if (enabled) 2 else 1
        val customEq = CustomEq(state, low, mid, high)
        val sent = aacp?.sendPacket(customEq.toPacket()) == true
        if (sent) {
            internalStateStore.update { 
                it.copy(
                    customEqEnabled = enabled,
                    customEqLow = low,
                    customEqMid = mid,
                    customEqHigh = high
                )
            }
        }
        return sent
    }
    
    fun setHeadTracking(enabled: Boolean): Boolean {
        // Head tracking is controlled via AACP command - placeholder for implementation
        // The actual command format needs to be determined from protocol analysis
        internalStateStore.update { it.copy(headTrackingEnabled = enabled) }
        return true
    }
    
    fun setMicMode(mode: MicMode): Boolean {
        val value = when (mode) {
            MicMode.AUTO -> 0x00
            MicMode.RIGHT -> 0x01
            MicMode.LEFT -> 0x02
        }
        val sent = aacp?.sendControlCommand(0x01, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(micMode = mode) }
        return sent
    }
    
    fun setPressSpeed(speed: Int): Boolean {
        // 0x00 = Default, 0x01 = Slower, 0x02 = Slowest
        val value = speed.coerceIn(0, 2)
        val sent = aacp?.sendControlCommand(0x17, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(doubleClickInterval = value) }
        return sent
    }
    
    fun setHoldDuration(duration: Int): Boolean {
        // 0x00 = Default, 0x01 = Slower, 0x02 = Slowest
        val value = duration.coerceIn(0, 2)
        val sent = aacp?.sendControlCommand(0x18, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(clickHoldInterval = value) }
        return sent
    }
    
    fun setVolumeSwipeSpeed(speed: Int): Boolean {
        // 0x00 = Default, 0x01 = Longer, 0x02 = Longest
        val value = speed.coerceIn(0, 2)
        val sent = aacp?.sendControlCommand(0x23, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(volumeSwipeInterval = value) }
        return sent
    }
    
    fun setCallManagement(config: Int): Boolean {
        val sent = aacp?.sendControlCommand(0x24, byteArrayOf(config.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(callManagementConfig = config) }
        return sent
    }
    
    fun setChimeVolume(volume: Int): Boolean {
        val value = volume.coerceIn(0, 100)
        val sent = aacp?.sendControlCommand(0x1F, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(chimeVolume = value) }
        return sent
    }
    
    fun setInCaseToneVolume(volume: Int): Boolean {
        val value = volume.coerceIn(0, 100)
        val sent = aacp?.sendControlCommand(0x40, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(inCaseToneVolume = value) }
        return sent
    }
    
    fun setHpsGainSwipe(value: Int): Boolean {
        val sent = aacp?.sendControlCommand(0x2F, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(hpsGainSwipe = value) }
        return sent
    }
    
    fun setPpeCapLevel(level: Int): Boolean {
        val sent = aacp?.sendControlCommand(0x38, byteArrayOf(level.toByte(), 0, 0, 0)) == true
        if (sent) internalStateStore.update { it.copy(ppeCapLevel = level) }
        return sent
    }
    
    fun renameAirPods(newName: String): Boolean {
        // Send rename packet using opcode 0x001E
        val nameBytes = newName.toByteArray()
        val packet = ByteArray(4 + 2 + nameBytes.size)
        packet[0] = 0x04
        packet[1] = 0x00
        packet[2] = 0x04
        packet[3] = 0x00
        // Opcode 0x001E in little endian
        packet[4] = 0x1E
        packet[5] = 0x00
        System.arraycopy(nameBytes, 0, packet, 6, nameBytes.size)
        
        val sent = aacp?.sendPacket(packet) == true
        if (sent) {
            internalStateStore.update { it.copy(deviceName = newName) }
            prefs.edit().putString("device_name", newName).apply()
        }
        return sent
    }
    
    fun forgetDevice() {
        prefs.edit()
            .remove("selected_address")
            .remove("selected_name")
            .remove("last_connected_address")
            .remove("last_connected_name")
            .remove("device_name")
            .apply()
        disconnect()
        runCatching { ble?.stopScanning() }
        internalStateStore.reset()
    }
    
    fun resetSettings() {
        prefs.edit().clear().apply()
        disconnect()
        internalStateStore.reset()
    }
    
    fun checkFirmwareUpdates() {
        scope.launch {
            try {
                // Placeholder for firmware update check
                // This would typically check against GitHub releases or an update service
                // For now, we'll simulate a check based on current firmware version
                val currentVersion = internalStateStore.state.value.firmwareVersion
                val latestVersion = "8.454.0" // Example latest version
                
                if (currentVersion != null && currentVersion < latestVersion) {
                    internalStateStore.update { 
                        it.copy(
                            firmwareUpdateAvailable = true,
                            firmwareUpdateVersion = latestVersion
                        )
                    }
                } else {
                    internalStateStore.update { 
                        it.copy(
                            firmwareUpdateAvailable = false,
                            firmwareUpdateVersion = null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to check firmware updates", e)
            }
        }
    }
    
    fun autoConnect() {
        scope.launch {
            delay(2000)
            val lastConnectedAddress = prefs.getString("last_connected_address", null)
            val lastConnectedName = prefs.getString("last_connected_name", "AirPods")
            if (lastConnectedAddress != null) {
                connectBondedBleMode(lastConnectedAddress, lastConnectedName ?: "AirPods")
                return@launch
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val device = adapter?.bondedDevices?.firstOrNull {
                it.name.orEmpty().contains("AirPods", true) || it.name.orEmpty().contains("Pods", true)
            }
            if (device != null) {
                connectBondedBleMode(device.address, device.name ?: "AirPods")
            }
        }
    }

    private fun startAacpReader(manager: AACPManager) {
        aacpReaderJob?.cancel(); aacpReaderJob = scope.launch {
            try {
                val input = transport.aacpInput; val buffer = ByteArray(4096)
                while (true) { val count = input.read(buffer); if (count <= 0) break; val packet = buffer.copyOf(count); recordPacket(packet); manager.receivePacket(packet) }
                if (!manualDisconnect && connectedDevice != null) scheduleReconnect("AACP socket closed by device")
            } catch (e: Throwable) { if (!manualDisconnect && connectedDevice != null) scheduleReconnect("AACP reader stopped: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (manualDisconnect || connectedDevice == null || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            onError(reason); val device = connectedDevice ?: return@launch
            val current = internalStateStore.state.value
            if (current.protocolStage == "BLE_ONLY") {
                Log.i(tag, "scheduleReconnect: skipping - BLE_ONLY mode active")
                return@launch
            }
            for (attempt in 1..3) {
                if (manualDisconnect) return@launch
                val backoff = attempt * 5000L
                Log.i(tag, "scheduleReconnect: waiting ${backoff}ms before attempt $attempt/3")
                delay(backoff)
                Log.i(tag, "AACP reconnect attempt $attempt/3")
                runCatching { transport.close() }
                runCatching { aacp?.unbindTransport(); aacp?.bindTransport(transport) }
                internalStateStore.update { it.copy(connecting = true, connected = false, protocolStage = "RECONNECT_$attempt", lastError = null) }
                connectTransport(device)
                if (aacp?.sessionState == AACPManager.SessionState.READY) return@launch
            }
            onError("AACP reconnect failed after 3 attempts")
        }
    }

    private fun recordPacket(packet: ByteArray?) { if (packet == null) return; val frame = AirPodsProtocolDiagnostics.decode(packet); internalStateStore.update { it.copy(lastPacketOpcode = AirPodsProtocolDiagnostics.opcodeName(frame?.opcode), lastPacketHex = AirPodsProtocolDiagnostics.hex(packet)) } }
    private fun applyBleStatus(device: BLEManager.AirPodsStatus) {
        val hasBattery = device.leftBattery != null || device.rightBattery != null || device.caseBattery != null
        internalStateStore.update {
            val currentStage = it.protocolStage
            val isBusy = currentStage in setOf("READY", "L2CAP", "HANDSHAKE_SENT", "CONNECTING", "RECONNECT_1", "RECONNECT_2", "RECONNECT_3")
            val newStage = if (!isBusy && hasBattery && currentStage != "BLE_ONLY") "BLE_ONLY" else currentStage
            it.copy(
                deviceName = if (device.model != "Unknown") device.model else it.deviceName,
                address = device.address,
                leftBattery = device.leftBattery,
                rightBattery = device.rightBattery,
                caseBattery = device.caseBattery,
                leftCharging = device.isLeftCharging,
                rightCharging = device.isRightCharging,
                caseCharging = device.isCaseCharging,
                caseLidOpen = device.lidOpen,
                leftInEar = device.isLeftInEar,
                rightInEar = device.isRightInEar,
                connected = if (newStage == "BLE_ONLY") true else it.connected,
                protocolStage = newStage,
            )
        }
    }
    fun markConnecting() { internalStateStore.update { it.copy(connecting = true, lastError = null, protocolStage = "CONNECTING") } }
    fun onBattery(left: Int?, right: Int?, caseBattery: Int?) { internalStateStore.update { it.copy(leftBattery = left, rightBattery = right, caseBattery = caseBattery) } }
    fun onEarDetection(leftInEar: Boolean, rightInEar: Boolean) { internalStateStore.update { it.copy(leftInEar = leftInEar, rightInEar = rightInEar) } }
    fun onListeningModeChanged(mode: ListeningMode) { internalStateStore.update { it.copy(listeningMode = mode) }; persistTileState() }

    /** Execute a typed controller command; used by UI and service entry points. */
    fun submit(command: AirPodsCommand): Boolean = when (command) {
        AirPodsCommand.Connect -> connectToBondedAirPods()
        AirPodsCommand.Disconnect -> { disconnect(); true }
        AirPodsCommand.RefreshState -> refreshState()
        is AirPodsCommand.SetListeningMode -> setListeningMode(command.mode)
        is AirPodsCommand.SetEarDetection -> setEarDetection(command.enabled)
        is AirPodsCommand.SetConversationalAwareness -> setConversationalAwareness(command.enabled)
        is AirPodsCommand.SetControlBoolean -> setControlBoolean(command.identifier, command.enabled)
        is AirPodsCommand.SetControlByte -> setControlByte(command.identifier, command.value)
    }

    /**
     * Ask the AirPods to re-send their current state.
     *
     * The notification request is the only inherited packet that makes the
     * device replay battery, ear detection and control command values, so it
     * doubles as a state refresh once the session is READY.
     */
    fun refreshState(): Boolean {
        val manager = aacp ?: return false
        if (manager.sessionState != AACPManager.SessionState.READY) return false
        return manager.sendNotificationRequest()
    }

    fun setListeningMode(mode: ListeningMode): Boolean {
        val value = when (mode) { ListeningMode.OFF -> 1; ListeningMode.ANC -> 2; ListeningMode.TRANSPARENCY -> 3 }.toByte()
        val sent = aacp?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value, byteArrayOf(value, 0, 0, 0)) == true
        if (sent) onListeningModeChanged(mode); return sent
    }
    fun setConversationalAwareness(enabled: Boolean): Boolean { val value = if (enabled) 1 else 2; val sent = aacp?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value, byteArrayOf(value.toByte(), 0, 0, 0)) == true; if (sent) internalStateStore.update { it.copy(conversationalAwarenessEnabled = enabled) }; return sent }
    fun setEarDetection(enabled: Boolean): Boolean { val value = if (enabled) 1 else 2; val sent = aacp?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.EAR_DETECTION_CONFIG.value, byteArrayOf(value.toByte(), 0, 0, 0)) == true; if (sent) internalStateStore.update { it.copy(earDetectionEnabled = enabled) }; return sent }
    
    fun setStemAction(budType: AACPManager.Companion.StemPressBudType, pressType: AACPManager.Companion.StemPressType, action: StemAction): Boolean {
        val actionValue = when (action) {
            StemAction.PLAY_PAUSE -> 0x01
            StemAction.PREVIOUS_TRACK -> 0x02
            StemAction.NEXT_TRACK -> 0x03
            StemAction.DIGITAL_ASSISTANT -> 0x04
            StemAction.CYCLE_NOISE_CONTROL_MODES -> 0x05
        }
        val sent = aacp?.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.STEM_CONFIG.value,
            byteArrayOf(budType.value, pressType.value, actionValue.toByte(), 0)
        ) == true
        if (sent) {
            val key = "stem_${budType.name}_${pressType.name}"
            prefs.edit().putString(key, action.name).apply()
        }
        return sent
    }
    
    fun getStemAction(budType: AACPManager.Companion.StemPressBudType, pressType: AACPManager.Companion.StemPressType): StemAction {
        val key = "stem_${budType.name}_${pressType.name}"
        val saved = prefs.getString(key, null)
        return saved?.let { StemAction.fromString(it) } 
            ?: StemAction.defaultActions[pressType] 
            ?: StemAction.CYCLE_NOISE_CONTROL_MODES
    }

    /** Boolean control commands are encoded as `0x01` enabled / `0x02` disabled. */
    fun setControlBoolean(identifier: AACPManager.Companion.ControlCommandIdentifiers, enabled: Boolean): Boolean =
        setControlByte(identifier, if (enabled) 0x01 else 0x02)

    fun setControlByte(identifier: AACPManager.Companion.ControlCommandIdentifiers, value: Int): Boolean {
        val sent = aacp?.sendControlCommand(identifier.value, byteArrayOf(value.toByte(), 0, 0, 0)) == true
        if (sent) {
            internalStateStore.update { it.copy(controlValues = it.controlValues + (identifier to value)) }
            when (identifier) {
                AACPManager.Companion.ControlCommandIdentifiers.EAR_DETECTION_CONFIG ->
                    internalStateStore.update { it.copy(earDetectionEnabled = value == 0x01) }
                AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG ->
                    internalStateStore.update { it.copy(conversationalAwarenessEnabled = value == 0x01) }
                else -> Unit
            }
        }
        return sent
    }
    
    fun setControlByteRaw(identifier: Int, value: Int): Boolean {
        val sent = aacp?.sendControlCommand(identifier.toByte(), byteArrayOf(value.toByte(), 0, 0, 0)) == true
        return sent
    }
    fun onError(message: String, cause: Throwable? = null) {
        Log.e(tag, message, cause)
        internalStateStore.update {
            val hasBle = it.leftBattery != null || it.rightBattery != null || it.caseBattery != null
            val keepBleOnly = it.protocolStage == "BLE_ONLY" && hasBle
            it.copy(
                connecting = false,
                connected = hasBle || it.protocolStage == "BLE_ONLY",
                protocolStage = when {
                    keepBleOnly -> "BLE_ONLY"
                    hasBle -> "BLE_ONLY"
                    else -> "FAILED"
                },
                lastError = if (hasBle || it.protocolStage == "BLE_ONLY") null else message,
            )
        }
    }
    private fun fail(message: String, cause: Throwable? = null): Boolean { onError(message, cause); return false }
    
    private fun persistTileState() {
        val state = internalStateStore.state.value
        prefs.edit().apply {
            putBoolean("connected", state.connected)
            putBoolean("connecting", state.connecting)
            putString("listening_mode", state.listeningMode.name)
            putInt("left_battery", state.leftBattery ?: -1)
            putInt("right_battery", state.rightBattery ?: -1)
            putInt("case_battery", state.caseBattery ?: -1)
        }.apply()
    }
    fun disconnect() {
        manualDisconnect = true; connectedDevice = null; reconnectJob?.cancel(); reconnectJob = null
        readyWatchJob?.cancel(); readyWatchJob = null; aacpReaderJob?.cancel(); aacpReaderJob = null
        gattPollJob?.cancel(); gattPollJob = null; gattBatteryReader.cleanup()
        runCatching { transport.close() }; aacp?.unbindTransport()
        internalStateStore.update {
            val hasBle = it.leftBattery != null || it.rightBattery != null || it.caseBattery != null
            it.copy(
                connected = hasBle,
                connecting = false,
                protocolStage = if (hasBle) "BLE_ONLY" else "IDLE",
            )
        }
    }
    fun shutdown() {
        disconnect()
        runCatching { ble?.stopScanning() }
        gattBatteryReader.cleanup()
        aacp?.unbindTransport()
        scope.cancel()
        aacp = null
        ble = null
        internalStateStore.reset()
    }
}
