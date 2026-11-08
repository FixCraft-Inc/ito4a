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

    @Test fun onlyTheNamedCallStreamIsAccepted() {
        ItoProviderNative.checkProviderServicePolicy(document())
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
        )
        candidates.forEach { services ->
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                ItoProviderNative.checkProviderServicePolicy(document().put("services", services))
            }.code)
        }
    }

    @Test fun providerLocalAdaptersAreRefusedBeforeAdaptationCanDiscardThem() {
        for (kind in listOf("socks5", "forward", "module")) {
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                ItoProviderNative.checkProviderServicePolicy(document().put("adapters",
                    JSONArray().put(JSONObject().put("kind", kind).put("service", "ito-call"))))
            }.code)
        }
    }
}
