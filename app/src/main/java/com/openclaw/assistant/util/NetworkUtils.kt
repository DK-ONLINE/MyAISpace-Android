package com.openclaw.assistant.util

import java.net.InetAddress
import java.net.URI

object NetworkUtils {

    /**
     * Checks if the given URL is secure.
     *
     * Security Policy:
     * - HTTPS is always allowed.
     * - HTTP is ONLY allowed for:
     *   - Loopback addresses (localhost, 127.0.0.1)
     *   - Private IP addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
     *
     * This prevents sending sensitive data (auth tokens) over cleartext to public networks.
     */
    fun isUrlSecure(url: String): Boolean {
        if (url.isBlank()) return false

        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return false

            // HTTPS is secure
            if (scheme == "https") return true

            // Allow HTTP only for local networks
            if (scheme == "http") {
                val host = uri.host ?: return false

                // Fast check for localhost string
                if (host.equals("localhost", ignoreCase = true)) return true

                // Resolve host to check IP properties
                // Note: This performs a DNS lookup, but is acceptable for the security gain
                // on insecure HTTP connections.
                val address = InetAddress.getByName(host)

                return address.isLoopbackAddress ||
                       address.isSiteLocalAddress ||
                       // Check for Link Local (169.254.x.x) - sometimes used in ad-hoc networks
                       address.isLinkLocalAddress
            }

            // Other schemes (ftp, etc) are not supported/secure
            false

        } catch (e: Exception) {
            // Malformed URL or Unknown Host -> Fail Secure
            false
        }
    }
}
