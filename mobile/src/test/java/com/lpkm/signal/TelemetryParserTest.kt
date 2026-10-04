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
}
