package me.kavishdevar.librepods.wear.relay

import org.json.JSONObject

/**
 * Wire protocol for watch ↔ phone communication via Wear Data Layer.
 *
 * All messages are JSON strings sent over MessageClient (low-latency)
 * with path prefixed by "/librepods-relay/".
 *
 * Phone-side requirement: the LibrePods Android app must implement
 * a WearableListenerService that handles these same paths and
 * translates them into AACP calls over L2CAP.
 */
object PhoneRelayProtocol {
    const val PATH_PREFIX = "/librepods-relay/"
    const val PATH_COMMAND = "${PATH_PREFIX}command"
    const val PATH_STATE = "${PATH_PREFIX}state"
    const val PATH_HANDSHAKE = "${PATH_PREFIX}handshake"
    const val PATH_ERROR = "${PATH_PREFIX}error"

    // ── Watch → Phone commands ──────────────────────────────────

    sealed class Command(val type: String) {
        open fun toJson(): JSONObject = JSONObject().put("type", type)

        data class Connect(val address: String, val name: String = "AirPods") : Command("connect") {
            override fun toJson() = super.toJson().put("address", address).put("name", name)
        }

        object Disconnect : Command("disconnect")

        data class SetListeningMode(val mode: String) : Command("set_listening_mode") {
            override fun toJson() = super.toJson().put("mode", mode)
        }

        data class SetNoiseControlMode(val mode: String) : Command("set_noise_control") {
            override fun toJson() = super.toJson().put("mode", mode)
        }

        data class SetTransparencyLevel(val level: Float) : Command("set_transparency_level") {
            override fun toJson() = super.toJson().put("level", level.toDouble())
        }

        data class SetAdaptiveVolume(val enabled: Boolean) : Command("set_adaptive_volume") {
            override fun toJson() = super.toJson().put("enabled", enabled)
        }

        data class SetConversationAware(val enabled: Boolean) : Command("set_conversation_aware") {
            override fun toJson() = super.toJson().put("enabled", enabled)
        }

        data class Rename(val name: String) : Command("rename") {
            override fun toJson() = super.toJson().put("name", name)
        }

        object RequestState : Command("request_state")

        object Ping : Command("ping")
    }

    // ── Phone → Watch state updates ─────────────────────────────

    data class RelayState(
        val phoneConnected: Boolean,
        val airpodsConnected: Boolean,
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val listeningMode: String? = null,
        val noiseControlMode: String? = null,
        val deviceName: String? = null,
        val address: String? = null,
        val conversationAwareEnabled: Boolean? = null,
        val adaptiveVolumeEnabled: Boolean? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("phoneConnected", phoneConnected)
            put("airpodsConnected", airpodsConnected)
            put("leftBattery", leftBattery ?: JSONObject.NULL)
            put("rightBattery", rightBattery ?: JSONObject.NULL)
            put("caseBattery", caseBattery ?: JSONObject.NULL)
            put("listeningMode", listeningMode ?: JSONObject.NULL)
            put("noiseControlMode", noiseControlMode ?: JSONObject.NULL)
            put("deviceName", deviceName ?: JSONObject.NULL)
            put("address", address ?: JSONObject.NULL)
            put("conversationAwareEnabled", conversationAwareEnabled ?: JSONObject.NULL)
            put("adaptiveVolumeEnabled", adaptiveVolumeEnabled ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(json: JSONObject): RelayState = RelayState(
                phoneConnected = json.optBoolean("phoneConnected", false),
                airpodsConnected = json.optBoolean("airpodsConnected", false),
                leftBattery = json.optInt("leftBattery", -1).takeIf { it >= 0 },
                rightBattery = json.optInt("rightBattery", -1).takeIf { it >= 0 },
                caseBattery = json.optInt("caseBattery", -1).takeIf { it >= 0 },
                listeningMode = json.optString("listeningMode").ifEmpty { null },
                noiseControlMode = json.optString("noiseControlMode").ifEmpty { null },
                deviceName = json.optString("deviceName").ifEmpty { null },
                address = json.optString("address").ifEmpty { null },
                conversationAwareEnabled = if (json.has("conversationAwareEnabled")) json.optBoolean("conversationAwareEnabled") else null,
                adaptiveVolumeEnabled = if (json.has("adaptiveVolumeEnabled")) json.optBoolean("adaptiveVolumeEnabled") else null,
            )
        }
    }

    // ── Serialization helpers ───────────────────────────────────

    fun encodeCommand(cmd: Command): String = cmd.toJson().toString()

    fun encodeState(state: RelayState): String = state.toJson().toString()

    fun decodeState(json: String): RelayState = RelayState.fromJson(JSONObject(json))

    fun encodeError(message: String): String = JSONObject()
        .put("type", "error")
        .put("message", message)
        .toString()
}
