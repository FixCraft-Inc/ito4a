package com.fixcraft.ito.data

import com.fixcraft.ito.domain.AudioPreference
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation
import com.fixcraft.ito.domain.RoutingPreference
import com.fixcraft.ito.domain.SavedService
import com.fixcraft.ito.domain.ServiceOrigin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class PreferencesCodecTest {
    private fun service() = SavedService(
        UUID.fromString("c1b841e1-17a0-49ec-a264-9f49329d5f91"),
        "My own service", requireNotNull(ServiceOrigin.parse("https://provider.example")),
    )

    @Test fun newInstallationRequiresProtectedRoutingWithoutAnyProvider() {
        val value = PreferencesCodec.decode(null)
        assertEquals(RoutingPreference.ProtectedOnly, value.routing)
        assertEquals(emptyList<SavedService>(), value.services)
    }

    @Test fun emergencyPreferenceDoesNotChangeRoutingOrSavedServices() {
        val before = Preferences(services = listOf(service()))
        val after = PreferencesCodec.decode(PreferencesCodec.encode(before.copy(audio = AudioPreference.Emergency)))
        assertEquals(before.routing, after.routing)
        assertEquals(before.services, after.services)
    }

    @Test fun rejectsUnknownOrCorruptRoutingInsteadOfRecoveringToDirect() {
        val encoded = JSONObject(PreferencesCodec.encode(Preferences())).put("routing", "fastest")
        assertThrows(InvalidPreferences::class.java) { PreferencesCodec.decode(encoded.toString()) }
        assertThrows(InvalidPreferences::class.java) { PreferencesCodec.decode("not json") }
        assertThrows(InvalidPreferences::class.java) { PreferencesCodec.decode(" ".repeat(32769)) }
    }

    @Test fun rejectsWrongTypesUnknownFieldsAndUnknownSchemas() {
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.put("schema", "2") }, { it.put("schema", 3) }, { it.put("routing", true) },
            { it.put("enable_plaintext", true) }, { it.remove("audio") }, { it.remove("presentation") },
            { it.put("presentation", "Expert") }, { it.put("schema", 1) },
        )) {
            val json = JSONObject(PreferencesCodec.encode(Preferences()))
            mutation(json)
            assertThrows(InvalidPreferences::class.java) { PreferencesCodec.decode(json.toString()) }
        }
    }

    @Test fun rejectsDuplicateServicesAndCredentialBearingAddresses() {
        val json = JSONObject(PreferencesCodec.encode(Preferences(services = listOf(service()))))
        val services = json.getJSONArray("services")
        services.put(JSONObject(services.getJSONObject(0).toString()))
        assertThrows(InvalidPreferences::class.java) { PreferencesCodec.decode(json.toString()) }
        services.remove(1)
        services.getJSONObject(0).put("origin", "https://user:password@provider.example")
        assertThrows(InvalidPreferences::class.java) { PreferencesCodec.decode(json.toString()) }
    }

    @Test fun schemaOneReadsAsSimpleAndAdvancedChangesNoPolicy() {
        val schemaOne = JSONObject(PreferencesCodec.encode(Preferences(services = listOf(service())))).put("schema", 1)
        schemaOne.remove("presentation")
        val upgraded = PreferencesCodec.decode(schemaOne.toString())
        assertEquals(Presentation.Simple, upgraded.presentation)
        assertEquals(listOf(service()), upgraded.services)
        val advanced = PreferencesCodec.decode(PreferencesCodec.encode(upgraded.copy(presentation = Presentation.Advanced)))
        assertEquals(Presentation.Advanced, advanced.presentation)
        assertEquals(RoutingPreference.ProtectedOnly, advanced.routing)
        assertEquals(upgraded.audio, advanced.audio)
        assertEquals(2, JSONObject(PreferencesCodec.encode(advanced)).getInt("schema"))
    }

    @Test fun removingAServicePreservesPrivacyAndAudioPreferences() {
        val before = Preferences(RoutingPreference.ProtectedOnly, AudioPreference.Emergency, listOf(service()))
        val after = PreferencesCodec.decode(PreferencesCodec.encode(before.copy(services = emptyList())))
        assertEquals(before.routing, after.routing)
        assertEquals(before.audio, after.audio)
        assertEquals(emptyList<SavedService>(), after.services)
    }
}
