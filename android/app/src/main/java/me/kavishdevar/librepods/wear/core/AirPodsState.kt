package me.kavishdevar.librepods.wear.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.bluetooth.AACPManager

/** Immutable state exposed by the autonomous Wear AirPods controller. */
data class AirPodsState(
    val deviceName: String = "AirPods",
    val address: String? = null,
    val modelNumber: String? = null,
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val caseBattery: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val caseLidOpen: Boolean? = null,
    val listeningMode: ListeningMode = ListeningMode.OFF,
    val leftInEar: Boolean? = null,
    val rightInEar: Boolean? = null,
    val earDetectionEnabled: Boolean? = null,
    val conversationalAwarenessEnabled: Boolean? = null,
    /** First byte of every control command value reported by the AirPods, keyed by identifier. */
    val controlValues: Map<AACPManager.Companion.ControlCommandIdentifiers, Int> = emptyMap(),
    val protocolStage: String = "IDLE",
    val lastPacketOpcode: String? = null,
    val lastPacketHex: String? = null,
    val lastError: String? = null,
    val attAvailable: Boolean = false,
    val loudSoundReductionEnabled: Boolean? = null,
    val hearingAidAmplification: Float? = null,
    val hearingAidConversationBoost: Boolean? = null,
    val transparencyLevel: Float? = null,
    val customEqEnabled: Boolean = false,
    val customEqLow: Int = 50,
    val customEqMid: Int = 50,
    val customEqHigh: Int = 50,
    val headTrackingEnabled: Boolean = false,
    val micMode: MicMode = MicMode.AUTO,
    val singleClickMode: Int = 0,
    val doubleClickMode: Int = 0,
    val clickHoldMode: Int = 0,
    val doubleClickInterval: Int = 0,
    val clickHoldInterval: Int = 0,
    val autoAnswerMode: Int = 0,
    val chimeVolume: Int = 50,
    val volumeSwipeInterval: Int = 0,
    val callManagementConfig: Int = 0,
    val volumeSwipeMode: Boolean = false,
    val adaptiveVolume: Boolean = false,
    val softwareMute: Boolean = false,
    val hearingAidEnrolled: Boolean = false,
    val hearingAidEnabled: Boolean = false,
    val hpsGainSwipe: Int = 0,
    val hrmEnabled: Boolean = false,
    val inCaseTone: Boolean = false,
    val inCaseToneVolume: Int = 50,
    val siriMultitone: Int = 0,
    val rawGesturesEnabled: Boolean = false,
    val temporaryPairing: Boolean = false,
    val ppeCapLevel: Int = 0,
    val siriMessageConfig: Int = 0,
    val uplinkEqBud: Int = 0,
    val uplinkEqSource: Int = 0,
    val buttonInputDisabled: Boolean = false,
    val firmwareUpdateAvailable: Boolean = false,
    val firmwareUpdateVersion: String? = null,
)

enum class MicMode {
    AUTO,
    RIGHT,
    LEFT,
}

enum class ListeningMode {
    ANC,
    TRANSPARENCY,
    OFF,
}

/** Small state holder; protocol adapters can update it without knowing about Compose. */
class AirPodsStateStore(initial: AirPodsState = AirPodsState()) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<AirPodsState> = mutableState.asStateFlow()

    fun update(transform: (AirPodsState) -> AirPodsState) {
        mutableState.value = transform(mutableState.value)
    }

    fun reset() {
        mutableState.value = AirPodsState()
    }
}
