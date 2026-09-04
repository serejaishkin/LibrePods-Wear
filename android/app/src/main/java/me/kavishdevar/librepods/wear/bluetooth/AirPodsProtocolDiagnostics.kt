package me.kavishdevar.librepods.wear.bluetooth

/**
 * Small, dependency-free AACP frame decoder used by the Wear transport.
 * Unknown packet layouts are never guessed; they remain available as hex.
 */
object AirPodsProtocolDiagnostics {
    private val header = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    data class Frame(
        val opcode: Int?,
        val payload: ByteArray,
        val raw: ByteArray,
    )

    data class BatteryComponent(
        val type: Component,
        val level: Int,
        val charging: Boolean,
        val connected: Boolean,
    )

    data class Metadata(
        val name: String,
        val modelNumber: String,
        val manufacturer: String,
        val serialNumber: String,
        val version1: String,
        val version2: String,
        val softwareVersion: String,
        val updaterIdentifier: String,
        val leftSerialNumber: String,
        val rightSerialNumber: String,
        val unknownNumericValue: String,
        val encryptedData: String?,
    )

    enum class Component(val wireValue: Int) { HEADSET(0x01), RIGHT(0x02), LEFT(0x04), CASE(0x08), UNKNOWN(-1) }

    fun isHeader(packet: ByteArray): Boolean =
        packet.size >= 4 && packet.copyOfRange(0, 4).contentEquals(header)

    fun decode(packet: ByteArray): Frame? {
        if (!isHeader(packet) || packet.size < 5) return null
        return Frame(packet[4].toInt() and 0xFF, packet.copyOfRange(5, packet.size), packet.copyOf())
    }

    /**
     * AACP battery response: header + opcode + reserved + count + N*5 bytes.
     * Each entry is type, 0x01, level/status, status, 0x01.
     * The layout is validated before returning anything to the UI layer.
     */
    fun parseBattery(packet: ByteArray): List<BatteryComponent>? {
        val frame = decode(packet) ?: return null
        if (frame.opcode != 0x04 || packet.size < 7) return null
        val count = packet[6].toInt() and 0xFF
        if (count > 3 || packet.size != 7 + 5 * count) return null

        val result = ArrayList<BatteryComponent>(count)
        repeat(count) { index ->
            val offset = 7 + index * 5
            if ((packet[offset + 1].toInt() and 0xFF) != 0x01 ||
                (packet[offset + 4].toInt() and 0xFF) != 0x01) return null
            val typeValue = packet[offset].toInt() and 0xFF
            val type = Component.entries.firstOrNull { it.wireValue == typeValue } ?: Component.UNKNOWN
            val level = (packet[offset + 2].toInt() and 0xFF).coerceIn(0, 100)
            val status = packet[offset + 3].toInt() and 0xFF
            result += BatteryComponent(
                type = type,
                level = level,
                charging = status == 0x01,
                connected = status != 0x04,
            )
        }
        return result
    }

    /**
     * Metadata starts with a small non-string prefix after opcode 0x1D.
     * We locate the first NUL followed by printable UTF-8 data, then decode
     * the documented consecutive NUL-terminated fields. No encrypted tail is
     * interpreted as text.
     */
    fun parseMetadata(packet: ByteArray): Metadata? {
        val frame = decode(packet) ?: return null
        if (frame.opcode != 0x1D || packet.size < 12) return null

        var start = -1
        for (index in 5 until packet.lastIndex) {
            if (packet[index] == 0.toByte()) {
                val next = packet[index + 1].toInt() and 0xFF
                if (next in 0x20..0x7E) {
                    start = index + 1
                    break
                }
            }
        }
        if (start < 0) return null

        val values = ArrayList<String>(11)
        var cursor = start
        while (cursor < packet.size && values.size < 11) {
            var end = -1
            for (i in cursor until packet.size) {
                if (packet[i] == 0.toByte()) { end = i; break }
            }
            if (end < 0) return null
            values += packet.copyOfRange(cursor, end).toString(Charsets.UTF_8)
            cursor = end + 1
        }
        if (values.size < 11) return null

        fun value(index: Int): String = values.getOrElse(index) { "" }
        return Metadata(
            name = value(0),
            modelNumber = value(1),
            manufacturer = value(2),
            serialNumber = value(3),
            version1 = value(4),
            version2 = value(5),
            softwareVersion = value(6),
            updaterIdentifier = value(7),
            leftSerialNumber = value(8),
            rightSerialNumber = value(9),
            unknownNumericValue = value(10),
            encryptedData = if (cursor < packet.size) hex(packet.copyOfRange(cursor, packet.size)) else null,
        )
    }

    /** Human-readable, non-sensitive summary used only for debug logging. */
    fun debugSummary(packet: ByteArray): String {
        val frame = decode(packet)
        val opcode = frame?.opcode
        val type = opcodeName(opcode)
        val details = when (opcode) {
            0x04 -> parseBattery(packet)?.joinToString(",") { "${it.type.name}:level=${it.level},charging=${it.charging},connected=${it.connected}" }
                ?: "parseError=battery"
            0x06 -> parseEarDetection(packet)?.let { "leftInEar=${it.first},rightInEar=${it.second}" }
                ?: "parseError=ear"
            0x1D -> parseMetadata(packet)?.let { "name=${it.name},model=${it.modelNumber},mfr=${it.manufacturer},firmware=${it.version1}" }
                ?: "parseError=metadata"
            0x09 -> if (packet.size >= 8) "identifier=0x%02X,value=0x%02X".format(packet[6].toInt() and 0xFF, packet[7].toInt() and 0xFF) else "parseError=control"
            else -> ""
        }
        return "opcode=${if (opcode == null) "UNKNOWN" else "0x%02X".format(opcode)} type=$type len=${packet.size} $details".trim()
    }

    fun parseEarDetection(packet: ByteArray): Pair<Boolean, Boolean>? {
        val frame = decode(packet) ?: return null
        if (frame.opcode != 0x06 || packet.size != 8) return null
        fun inEar(value: Int): Boolean? = when (value) {
            0x00 -> true
            0x01, 0x02 -> false
            else -> null
        }
        val left = inEar(packet[6].toInt() and 0xFF) ?: return null
        val right = inEar(packet[7].toInt() and 0xFF) ?: return null
        return left to right
    }

    fun opcodeName(opcode: Int?): String = when (opcode) {
        0x04 -> "BATTERY_INFO"
        0x06 -> "EAR_DETECTION"
        0x09 -> "CONTROL_COMMAND"
        0x0F -> "REQUEST_NOTIFICATIONS"
        0x17 -> "HEADTRACKING"
        0x19 -> "STEM_PRESS"
        0x1A -> "RENAME"
        0x1D -> "INFORMATION"
        0x2E -> "CONNECTED_DEVICES"
        0x30 -> "PROXIMITY_KEYS_REQ"
        0x31 -> "PROXIMITY_KEYS_RSP"
        0x4B -> "CONVERSATION_AWARENESS"
        0x4D -> "SET_FEATURE_FLAGS"
        0x53 -> "HEADPHONE_ACCOMMODATION"
        0x63 -> "CUSTOM_EQ"
        else -> "UNKNOWN"
    }

    fun hex(bytes: ByteArray, maxBytes: Int = 256): String =
        bytes.take(maxBytes).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
