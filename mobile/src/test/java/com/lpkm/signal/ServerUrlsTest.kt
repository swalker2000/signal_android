package com.lpkm.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlsTest {
    @Test
    fun keepsDefaultOrigin() {
        assertEquals("http://89.22.169.3:7070", ServerUrls.normalize(""))
        assertEquals("http://89.22.169.3:7070", ServerUrls.normalize("http://89.22.169.3:7070/"))
    }

    @Test
    fun addsSchemeAndDropsTrailingSlash() {
        assertEquals("http://10.0.0.8:7070", ServerUrls.normalize("10.0.0.8:7070/"))
        assertEquals("https://panel.example", ServerUrls.normalize("https://panel.example/"))
    }

    @Test
    fun rejectsAddressWithoutHost() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerUrls.normalize("http://")
        }
    }
}
