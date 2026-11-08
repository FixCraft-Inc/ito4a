package com.fixcraft.ito.data

import com.fixcraft.ito.domain.AudioPreference
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation
import com.fixcraft.ito.domain.RoutingPreference
import com.fixcraft.ito.domain.SavedService
import com.fixcraft.ito.domain.ServiceOrigin
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class InvalidPreferences : Exception("Saved preferences are invalid")

/** Contains public addresses and preferences only. Never put credentials in this format. */
object PreferencesCodec {
    private const val MAX_CHARACTERS = 32768

    fun decode(text: String?): Preferences {
        if (text == null) return Preferences()
        if (text.length !in 1..MAX_CHARACTERS) throw InvalidPreferences()
        try {
            val root = JSONObject(text)
            // Schema 1 predates the presentation choice and reads as Simple.
            val schema = root.get("schema")
            val keys = setOf("schema", "routing", "audio", "services")
            require((schema == 1 && root.keys().asSequence().toSet() == keys) ||
                (schema == 2 && root.keys().asSequence().toSet() == keys + "presentation"))
            val presentation = if (schema == 2) Presentation.valueOf(string(root, "presentation")) else Presentation.Simple
            val routing = RoutingPreference.valueOf(string(root, "routing"))
            val audio = AudioPreference.valueOf(string(root, "audio"))
            val list = root.getJSONArray("services")
            require(list.length() <= Preferences.MAX_SERVICES)
            val services = (0 until list.length()).map { index ->
                val entry = list.getJSONObject(index)
                require(entry.keys().asSequence().toSet() == setOf("id", "name", "origin"))
                val idText = string(entry, "id")
                val id = UUID.fromString(idText)
                require(id.toString() == idText)
                val origin = ServiceOrigin.parse(string(entry, "origin"))
                requireNotNull(origin)
                SavedService(id, string(entry, "name"), origin)
            }
            return Preferences(routing, audio, services, presentation)
        } catch (_: JSONException) {
            throw InvalidPreferences()
        } catch (_: IllegalArgumentException) {
            throw InvalidPreferences()
        }
    }

    private fun string(objectValue: JSONObject, name: String): String {
        val value = objectValue.get(name)
        require(value is String)
        return value
    }

    fun encode(preferences: Preferences): String {
        val services = JSONArray()
        for (service in preferences.services) {
            services.put(JSONObject().put("id", service.id.toString())
                .put("name", service.name).put("origin", service.origin.value))
        }
        return JSONObject().put("schema", 2)
            .put("routing", preferences.routing.name)
            .put("audio", preferences.audio.name)
            .put("services", services)
            .put("presentation", preferences.presentation.name).toString()
    }
}
