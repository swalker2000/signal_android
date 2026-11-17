package com.lpkm.signal

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbLineBufferTest {
    @Test
    fun probeThenReadyStartsSession() {
        val buffer = UsbLineBuffer()
        assertEquals(
            listOf(UsbLineBuffer.Event.PROBE),
            buffer.push("SIGNAL?\n".toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(
            listOf(UsbLineBuffer.Event.READY),
            buffer.push("USB\r\n".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun plainLogIsNotAHandshake() {
        val buffer = UsbLineBuffer()
        assertEquals(
            listOf(UsbLineBuffer.Event.LOG),
            buffer.push("Signal-AB12\n".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun framesDoNotLookLikeText() {
        val buffer = UsbLineBuffer()
        val frame = SignalProtocol.frame(SignalProtocol.RPM, 1000)
        assertEquals(emptyList<UsbLineBuffer.Event>(), buffer.push(frame))
    }
}
