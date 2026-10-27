package com.fixcraft.ito.transport

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualConfigurationTest {
    private val keys = listOf("composite_key", "access_psk", "admission_key", "server_trust", "server_identity", "server_mlkem")
    private fun document(): JSONObject = JSONObject().put("credentials", JSONObject().also { c ->
        keys.forEach { c.put(it, JSONObject().put("file", "credentials/$it")) }
    }).put("endpoint", JSONObject().put("host", "edge.example").put("port", 443))
        .put("services", org.json.JSONArray().put(JSONObject().put("name", "tcp").put("kind", "stream")))
        .put("adapters", org.json.JSONArray().put(JSONObject().put("kind", "socks5")))
        .put("control", JSONObject().put("socket", "local.sock"))
    private fun files(config: JSONObject) = (keys.associate { "credentials/$it" to byteArrayOf(1) } + ("yume.json" to config.toString().toByteArray()))

    @Test fun missingCredentialIsARefusal() {
        val config = document()
        assertThrows(IllegalArgumentException::class.java) {
            ManualConfiguration.referencedFiles(config.toString().toByteArray(), files(config) - "credentials/access_psk")
        }
    }
    @Test fun pathsCannotEscapeOrAlias() {
        for (path in listOf("../key", "/key", "credentials/../key", "credentials//key", "yume.json")) {
            val config = document()
            config.getJSONObject("credentials").getJSONObject("access_psk").put("file", path)
            assertThrows(IllegalArgumentException::class.java) { ManualConfiguration.referencedFiles(config.toString().toByteArray(), files(config)) }
        }
    }
    @Test fun referencesMustBeDistinct() {
        val config = document()
        config.getJSONObject("credentials").getJSONObject("access_psk").put("file", "credentials/admission_key")
        assertThrows(IllegalArgumentException::class.java) { ManualConfiguration.referencedFiles(config.toString().toByteArray(), files(config)) }
    }
    @Test fun unusedKitFilesCannotBecomeCredentials() {
        val config = document()
        val allowed = ManualConfiguration.referencedFiles(config.toString().toByteArray(), files(config) + ("run.sh" to byteArrayOf(5)))
        assertEquals(7, allowed.size)
        assertFalse(allowed.containsKey("run.sh"))
    }
    @Test fun networkAdaptationKeepsAuthenticatedHostAndServices() {
        val config = document()
        val adapted = JSONObject(ManualConfiguration.forNetwork(config.toString().toByteArray(), "192.0.2.4").toString(Charsets.UTF_8))
        assertEquals("edge.example", adapted.getJSONObject("endpoint").getString("host"))
        assertEquals("192.0.2.4", adapted.getJSONObject("endpoint").getString("connect_address"))
        assertEquals(config.getJSONArray("services").toString(), adapted.getJSONArray("services").toString())
        assertEquals(0, adapted.getJSONArray("adapters").length())
        assertFalse(adapted.has("control"))
    }
    @Test fun circuitsAndProxyAreRefused() {
        for (circuit in listOf(true, false)) {
            val config = document()
            if (circuit) config.put("circuits", JSONObject()) else config.getJSONObject("endpoint").put("socks5_proxy", JSONObject())
            assertThrows(IllegalArgumentException::class.java) { ManualConfiguration.describe(config.toString().toByteArray()) }
        }
    }
    @Test fun explicitManualDialAddressKeepsItsSeparateAuthority() {
        val config = document()
        config.getJSONObject("endpoint").put("connect_address", "192.0.2.7")
        val description = ManualConfiguration.describe(config.toString().toByteArray())
        assertEquals("edge.example", description.host)
        assertEquals("192.0.2.7", description.dialAddress)
        val adapted = JSONObject(ManualConfiguration.forNetwork(config.toString().toByteArray(), description.dialAddress!!).toString(Charsets.UTF_8))
        assertEquals("edge.example", adapted.getJSONObject("endpoint").getString("host"))
        assertEquals("192.0.2.7", adapted.getJSONObject("endpoint").getString("connect_address"))
    }
}
