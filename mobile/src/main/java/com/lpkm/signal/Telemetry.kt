package com.lpkm.signal

data class Telemetry(
    val rpm: Double? = null,
    val speed: Double? = null,
    val gear: String? = null,
    val temp: Double? = null,
    val fuel: Double? = null,
    val lights: Int? = null,
    val turnLeft: Boolean? = null,
    val turnRight: Boolean? = null,
) {
    fun isBlank(): Boolean {
        return rpm == null && speed == null && gear == null && temp == null &&
            fuel == null && lights == null && turnLeft == null && turnRight == null
    }

    fun withFrame(param: Int, value: Int): Telemetry {
        return when (param) {
            SignalProtocol.SPEED -> copy(speed = value.toDouble())
            SignalProtocol.GASOLINE -> copy(fuel = value.toDouble())
            SignalProtocol.RPM -> copy(rpm = value.toDouble())
            SignalProtocol.TEMPERATURE -> copy(temp = value.toDouble())
            SignalProtocol.GEAR -> copy(gear = gearLabel(value))
            SignalProtocol.LIGHTS -> copy(lights = value)
            SignalProtocol.TURN_LEFT -> copy(turnLeft = value != 0)
            SignalProtocol.TURN_RIGHT -> copy(turnRight = value != 0)
            else -> this
        }
    }
}

object SignalProtocol {
    const val STX = 0x02
    const val STP = 0x03
    const val FRAME_LEN = 5

    const val SPEED = 0x01
    const val GASOLINE = 0x02
    const val RPM = 0x03
    const val TEMPERATURE = 0x04
    const val GEAR = 0x05
    const val LIGHTS = 0x06
    const val TURN_LEFT = 0x07
    const val TURN_RIGHT = 0x08

    const val CMD_TELM = "TELM"
    const val CMD_PULS = "PULS"
    const val CMD_VLST = "VLST"
    const val CMD_VGET = "VGET"

    private val VEHICLE_ID = Regex("[A-Za-z0-9_-]{1,24}")

    fun vset(id: String): String? {
        if (!VEHICLE_ID.matches(id)) return null
        return "VSET:$id;"
    }

    fun frame(param: Int, value: Int): ByteArray {
        return byteArrayOf(
            STX.toByte(),
            param.toByte(),
            (value shr 8).toByte(),
            value.toByte(),
            STP.toByte(),
        )
    }
}

class FrameDecoder {
    private val pending = ArrayList<Byte>()

    fun reset() {
        pending.clear()
    }

    fun append(data: ByteArray): List<Pair<Int, Int>> {
        data.forEach { pending.add(it) }
        val frames = ArrayList<Pair<Int, Int>>()
        while (pending.size >= SignalProtocol.FRAME_LEN) {
            val stx = pending.indexOf(SignalProtocol.STX.toByte())
            if (stx < 0) {
                pending.clear()
                break
            }
            if (stx > 0) {
                repeat(stx) { pending.removeAt(0) }
            }
            if (pending.size < SignalProtocol.FRAME_LEN) break
            if ((pending[4].toInt() and 0xFF) != SignalProtocol.STP) {
                pending.removeAt(0)
                continue
            }
            val param = pending[1].toInt() and 0xFF
            val raw = ((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF)
            val value = raw.toShort().toInt()
            repeat(SignalProtocol.FRAME_LEN) { pending.removeAt(0) }
            frames.add(param to value)
        }
        if (pending.size > 64) {
            pending.subList(0, pending.size - 8).clear()
        }
        return frames
    }
}

data class VehicleChoice(val id: String, val name: String)

sealed class VehicleMessage {
    data class Catalog(val vehicles: List<VehicleChoice>, val currentId: String?) : VehicleMessage()
    data class Current(val id: String, val name: String) : VehicleMessage()
    data object Rejected : VehicleMessage()
}

fun parseVehicleReply(raw: String): VehicleMessage? {
    val text = raw.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return null
    val ok = Regex(""""ok"\s*:\s*(true|false)""").find(text)?.groupValues?.get(1)
    if (ok == "false") return VehicleMessage.Rejected
    if (text.contains("\"vehicles\"")) {
        val vehicles = Regex(""""id"\s*:\s*"([^"]*)"\s*,\s*"name"\s*:\s*"([^"]*)"""")
            .findAll(text)
            .map { match ->
                val id = match.groupValues[1].trim()
                if (id.isEmpty()) return@map null
                VehicleChoice(id, match.groupValues[2].trim().ifBlank { id })
            }
            .filterNotNull()
            .toList()
        return VehicleMessage.Catalog(vehicles, jsonString(text, "current"))
    }
    val id = jsonString(text, "id") ?: return if (ok == "true") VehicleMessage.Rejected else null
    return VehicleMessage.Current(id, jsonString(text, "name") ?: id)
}

private fun jsonString(json: String, key: String): String? {
    val match = Regex(""""$key"\s*:\s*"([^"]*)"""").find(json) ?: return null
    return match.groupValues[1].trim().ifBlank { null }
}

class BoardDecoder {
    private val pending = ArrayList<Byte>()
    private val text = StringBuilder()

    data class Batch(
        val frames: List<Pair<Int, Int>> = emptyList(),
        val replies: List<String> = emptyList(),
    )

    fun reset() {
        pending.clear()
        text.clear()
    }

    fun append(data: ByteArray): Batch {
        data.forEach { pending.add(it) }
        val frames = ArrayList<Pair<Int, Int>>()
        val replies = ArrayList<String>()
        while (pending.isNotEmpty()) {
            val head = pending[0].toInt() and 0xFF
            if (head == SignalProtocol.STX) {
                if (pending.size < SignalProtocol.FRAME_LEN) break
                if ((pending[4].toInt() and 0xFF) != SignalProtocol.STP) {
                    pending.removeAt(0)
                    continue
                }
                val param = pending[1].toInt() and 0xFF
                val raw = ((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF)
                repeat(SignalProtocol.FRAME_LEN) { pending.removeAt(0) }
                frames.add(param to raw.toShort().toInt())
                continue
            }
            val byte = pending.removeAt(0).toInt() and 0xFF
            if (byte in 0x20..0x7E) {
                text.append(byte.toChar())
                if (text.length > 1024) text.delete(0, text.length - 512)
            }
        }
        replies += takeMessages()
        return Batch(frames, replies)
    }

    private fun takeMessages(): List<String> {
        val found = ArrayList<String>()
        while (true) {
            val start = text.indexOf('{')
            if (start < 0) {
                if (text.length > 128) text.clear()
                break
            }
            if (start > 0) text.delete(0, start)
            val end = jsonEnd(text) ?: break
            found.add(text.substring(0, end))
            text.delete(0, end)
        }
        return found
    }

    private fun jsonEnd(source: CharSequence): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in source.indices) {
            val ch = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
    }
}

private fun gearLabel(code: Int): String {
    if (code <= 0) return "N"
    val symbol = code.toChar()
    if (symbol == '0' || symbol == 'N' || symbol == 'n') return "N"
    return symbol.toString()
}
