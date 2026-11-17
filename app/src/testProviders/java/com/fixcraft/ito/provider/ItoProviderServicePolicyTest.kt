package com.fixcraft.ito.provider

import com.fixcraft.yume.provider.ProviderCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ItoProviderServicePolicyTest {
    private fun service(name: String, kind: String) = JSONObject().put("name", name).put("kind", kind)
    private fun document() = JSONObject().put("services", JSONArray()
        .put(service("ito-call", "stream"))).put("adapters", JSONArray())

    @Test fun theCallStreamWithoutAMailboxIsAccepted() {
        ItoProviderNative.checkProviderServicePolicy(document())
    }

    @Test fun theCallAndMailboxStreamsAreAcceptedInEitherOrder() {
        for (names in listOf(listOf("ito-call", "ito-mailbox"), listOf("ito-mailbox", "ito-call"))) {
            ItoProviderNative.checkProviderServicePolicy(document().put("services",
                JSONArray().apply { names.forEach { put(service(it, "stream")) } }))
        }
    }

    @Test fun aVpnKitCannotBecomeAnItoRelayGrant() {
        val vpn = JSONArray().put(service("tcp", "stream")).put(service("udp", "packet"))
        assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
            ItoProviderNative.checkProviderServicePolicy(document().put("services", vpn))
        }.code)
    }

    @Test fun missingExtraAndWrongKindServicesAreRefused() {
        val candidates = listOf(
            JSONArray(),
            JSONArray().put(service("ito-call", "stream")).put(service("extra", "stream")),
            JSONArray().put(service("ito-call", "packet")),
            JSONArray().put(service("other-relay", "stream")),
            JSONArray().put(service("ito-mailbox", "stream")),
            JSONArray().put(service("ito-call", "stream")).put(service("ito-call", "stream")),
            JSONArray().put(service("ito-mailbox", "stream")).put(service("ito-mailbox", "stream")),
            JSONArray().put(service("ito-call", "stream")).put(service("ito-mailbox", "packet")),
            JSONArray().put(service("ito-call", "stream")).put(service("ito-mailbox", "stream"))
                .put(service("extra", "stream")),
        )
        candidates.forEach { services ->
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                ItoProviderNative.checkProviderServicePolicy(document().put("services", services))
            }.code)
        }
    }

    @Test fun providerLocalAdaptersAreRefusedBeforeAdaptationCanDiscardThem() {
        for (withMailbox in listOf(false, true)) for (kind in listOf("socks5", "forward", "module")) {
            val config = document()
            if (withMailbox) config.getJSONArray("services").put(service("ito-mailbox", "stream"))
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                ItoProviderNative.checkProviderServicePolicy(config.put("adapters",
                    JSONArray().put(JSONObject().put("kind", kind).put("service", "ito-call"))))
            }.code)
        }
    }
}
