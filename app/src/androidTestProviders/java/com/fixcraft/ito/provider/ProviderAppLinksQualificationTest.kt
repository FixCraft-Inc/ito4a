package com.fixcraft.ito.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.BuildConfig
import com.fixcraft.ito.transport.RequiresI1Fixture
import com.fixcraft.yume.provider.ProviderAppLinks
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Same qualification APK, with the platform association enabled and disabled. */
@RequiresI1Fixture
@RunWith(AndroidJUnit4::class)
class ProviderAppLinksQualificationTest {
    @Test fun exactTemporaryCaTrust() {
        val arguments = InstrumentationRegistry.getArguments()
        val expected = requireNotNull(arguments.getString("caSha256"))
        require(Regex("[0-9a-f]{64}").matches(expected))
        val present = arguments.getString("caState")
        require(present in setOf("present", "absent"))
        val keys = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val matches = keys.aliases().toList().count { alias ->
            alias.startsWith("user:") && java.security.MessageDigest.getInstance("SHA-256")
                .digest(keys.getCertificate(alias).encoded).joinToString("") { "%02x".format(it.toInt() and 255) } == expected
        }
        assertEquals(if (present == "present") 1 else 0, matches)
    }

    @Test fun actualAssociationIsRequired() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registration = ProviderRegistration(context.packageName, BuildConfig.PROVIDER_CLIENT_ID, BuildConfig.PROVIDER_CALLBACK)
        val expected = InstrumentationRegistry.getArguments().getString("association")
        require(expected in setOf("enabled", "disabled"))
        if (expected == "enabled") ProviderAppLinks.verify(context, registration)
        else {
            try { ProviderAppLinks.verify(context, registration); fail("Disabled association accepted") }
            catch (refusal: ProviderCodec.Refusal) { assertEquals("link_unverified", refusal.code) }
        }
    }
}
