package com.m175astudio.print

import com.m175astudio.net.BridgeClient
import org.junit.Assert.*
import org.junit.Test

class BridgeClientUrlTest {

    @Test
    fun bareIpGetsPort() {
        assertEquals("http://192.168.43.1:8080", BridgeClient("192.168.43.1", 8080).baseUrl())
    }

    @Test
    fun bareIpPortKept() {
        assertEquals("http://192.168.43.1:8080", BridgeClient("192.168.43.1:8080", 9999).baseUrl())
    }

    @Test
    fun fullUrlWithPortKept() {
        assertEquals("http://192.168.43.1:8080",
            BridgeClient("http://192.168.43.1:8080", 9999).baseUrl())
    }

    @Test
    fun fullUrlWithoutPortGetsPort() {
        // Without this the client silently hits :80 — the classic
        // "pasted http://192.168.43.1, Test fails" trap.
        assertEquals("http://192.168.43.1:8080", BridgeClient("http://192.168.43.1", 8080).baseUrl())
    }

    @Test
    fun trailingSlashTrimmed() {
        assertEquals("http://192.168.43.1:8080", BridgeClient("192.168.43.1/", 8080).baseUrl())
    }
}
