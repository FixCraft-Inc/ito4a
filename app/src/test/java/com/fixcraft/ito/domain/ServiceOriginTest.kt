package com.fixcraft.ito.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceOriginTest {
    @Test fun normalizesEquivalentHttpsOrigins() {
        assertEquals("https://example.org", ServiceOrigin.parse("HTTPS://EXAMPLE.ORG:443/")?.value)
        assertEquals("https://example.org:8443", ServiceOrigin.parse("https://example.org:8443")?.value)
        assertEquals("https://[::1]", ServiceOrigin.parse("https://[::1]/")?.value)
    }

    @Test fun rejectsCredentialsAndAmbiguousOrInsecureAddresses() {
        val rejected = listOf(
            "", "http://example.org", "https://user:password@example.org", "https://example.org/?token=secret",
            "https://example.org/#token", "https://example.org/path", "https://example.org/%2e%2e",
            "https://example.org:", "https://example.org:0", "https://example.org:65536",
            "https://example.org:-1", "https://example.org:word", " https://example.org",
            "https://example.org\n", "file:///example.org", "https:///example.org",
            "https://example.org\\@attacker.example", "https://" + "a".repeat(2048),
        )
        for (input in rejected) assertNull("Unexpectedly accepted an invalid origin", ServiceOrigin.parse(input))
    }

    @Test fun supportsIndependentAndLocalProvidersWithoutAnAllowlist() {
        assertEquals("https://provider.example", ServiceOrigin.parse("https://provider.example")?.value)
        assertEquals("https://192.168.1.20:8443", ServiceOrigin.parse("https://192.168.1.20:8443/")?.value)
    }
}
