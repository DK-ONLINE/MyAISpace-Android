package com.openclaw.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun isUrlSecure_https_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("https://example.com"))
        assertTrue(NetworkUtils.isUrlSecure("https://192.168.1.1"))
        assertTrue(NetworkUtils.isUrlSecure("https://8.8.8.8"))
    }

    @Test
    fun isUrlSecure_httpLocalhost_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://localhost"))
        assertTrue(NetworkUtils.isUrlSecure("http://127.0.0.1"))
    }

    @Test
    fun isUrlSecure_httpPrivateIp_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://192.168.1.100"))
        assertTrue(NetworkUtils.isUrlSecure("http://10.0.0.5"))
        assertTrue(NetworkUtils.isUrlSecure("http://172.16.0.5"))
        assertTrue(NetworkUtils.isUrlSecure("http://172.31.255.255"))
    }

    @Test
    fun isUrlSecure_httpPublicIp_returnsFalse() {
        assertFalse(NetworkUtils.isUrlSecure("http://8.8.8.8"))
        assertFalse(NetworkUtils.isUrlSecure("http://1.1.1.1"))
        assertFalse(NetworkUtils.isUrlSecure("http://172.32.0.1")) // Outside private range
        assertFalse(NetworkUtils.isUrlSecure("http://11.0.0.1"))
    }

    @Test
    fun isUrlSecure_invalidUrl_returnsFalse() {
        assertFalse(NetworkUtils.isUrlSecure("invalid-url"))
        assertFalse(NetworkUtils.isUrlSecure(""))
        assertFalse(NetworkUtils.isUrlSecure("   "))
    }

    @Test
    fun isUrlSecure_otherSchemes_returnsFalse() {
        assertFalse(NetworkUtils.isUrlSecure("ftp://192.168.1.1"))
        assertFalse(NetworkUtils.isUrlSecure("ws://192.168.1.1")) // WebSocket insecure
    }
}
