package com.lpkm.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryParserTest {
    @Test
    fun assemblesSplitFrames() {
        val decoder = FrameDecoder()
        val frame = SignalProtocol.frame(SignalProtocol.RPM, 4500)
        assertTrue(decoder.append(frame.copyOfRange(0, 2)).isEmpty())
        val parsed = decoder.append(frame.copyOfRange(2, frame.size))
        assertEquals(listOf(SignalProtocol.RPM to 4500), parsed)
    }

    @Test
    fun mapsBoardBurstOntoDashboard() {
        val decoder = FrameDecoder()
        val burst = SignalProtocol.frame(SignalProtocol.SPEED, 82) +
            byteArrayOf(0x00) +
            SignalProtocol.frame(SignalProtocol.GASOLINE, 67) +
            SignalProtocol.frame(SignalProtocol.RPM, 3200) +
            SignalProtocol.frame(SignalProtocol.TEMPERATURE, -5) +
            SignalProtocol.frame(SignalProtocol.GEAR, 'N'.code) +
            SignalProtocol.frame(SignalProtocol.LIGHTS, 2) +
            SignalProtocol.frame(SignalProtocol.TURN_LEFT, 1) +
            SignalProtocol.frame(SignalProtocol.TURN_RIGHT, 0)
        var sample = Telemetry()
        decoder.append(burst).forEach { (param, value) ->
            sample = sample.withFrame(param, value)
        }
        assertEquals(82.0, sample.speed)
        assertEquals(67.0, sample.fuel)
        assertEquals(3200.0, sample.rpm)
        assertEquals(-5.0, sample.temp)
        assertEquals("N", sample.gear)
        assertEquals(2, sample.lights)
        assertEquals(true, sample.turnLeft)
        assertEquals(false, sample.turnRight)
    }

    @Test
    fun gearDigitIsAscii() {
        val decoder = FrameDecoder()
        val frames = decoder.append(SignalProtocol.frame(SignalProtocol.GEAR, '3'.code))
        val sample = Telemetry().withFrame(frames[0].first, frames[0].second)
        assertEquals("3", sample.gear)
    }

    @Test
    fun reassemblesVehicleListSplitLikeBle() {
        val json = """{"vehicles":[{"id":"ktm1190","name":"KTM 1190"},{"id":"ktm1290","name":"KTM 1290"}],"current":"ktm1190"}"""
        val decoder = BoardDecoder()
        val replies = ArrayList<String>()
        var offset = 0
        while (offset < json.length) {
            val end = minOf(offset + 20, json.length)
            replies += decoder.append(json.substring(offset, end).toByteArray(Charsets.US_ASCII)).replies
            offset = end
        }
        assertEquals(listOf(json), replies)
        val message = parseVehicleReply(replies.single())
        assertTrue(message is VehicleMessage.Catalog)
        val catalog = message as VehicleMessage.Catalog
        assertEquals("ktm1190", catalog.currentId)
        assertEquals(listOf("KTM 1190", "KTM 1290"), catalog.vehicles.map { it.name })
    }

    @Test
    fun keepsJsonApartFromTelemetryFrames() {
        val decoder = BoardDecoder()
        val json = """{"ok":true,"id":"husqvarna701","name":"Husqvarna 701"}"""
        val frame = SignalProtocol.frame(SignalProtocol.SPEED, 82)
        val mixed = json.take(8).toByteArray(Charsets.US_ASCII) +
            frame +
            json.drop(8).toByteArray(Charsets.US_ASCII)
        val batch = decoder.append(mixed)
        assertEquals(listOf(SignalProtocol.SPEED to 82), batch.frames)
        assertEquals(listOf(json), batch.replies)
        val message = parseVehicleReply(batch.replies.single())
        assertEquals(VehicleMessage.Current("husqvarna701", "Husqvarna 701"), message)
    }

    @Test
    fun readsSelectFailure() {
        val batch = BoardDecoder().append("""{"ok":false}""".toByteArray(Charsets.US_ASCII))
        assertEquals(VehicleMessage.Rejected, parseVehicleReply(batch.replies.single()))
        assertEquals("VSET:ktm1190;", SignalProtocol.vset("ktm1190"))
        assertEquals(null, SignalProtocol.vset("bad id"))
    }
}
