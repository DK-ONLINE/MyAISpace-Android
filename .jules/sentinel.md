## 2025-01-31 - [Secure Local Networking]
**Vulnerability:** Global `usesCleartextTraffic="true"` allows insecure communication on public networks.
**Learning:** Android's `network_security_config` cannot selectively allow cleartext traffic for arbitrary local IPs (user-defined).
**Prevention:** Combine `usesCleartextTraffic="true"` with manual code validation (`InetAddress.isSiteLocalAddress()`) to enforce HTTPS on public networks while allowing HTTP on local networks.
