package com.fixcraft.ito.transport

import org.json.JSONArray
import org.json.JSONObject

/** Application constraints applied only after libyume accepts the schema. */
data class ManualConfiguration(val host: String, val port: Int, val streams: List<String>, val packets: List<String>, val dialAddress: String?) {
    companion object {
        private val credentials = listOf("composite_key", "access_psk", "admission_key", "server_trust", "server_identity", "server_mlkem")

        fun referencedFiles(config: ByteArray, files: Map<String, ByteArray>): Map<String, ByteArray> {
            val root = JSONObject(config.toString(Charsets.UTF_8))
            require(!root.has("circuits") && !root.getJSONObject("endpoint").has("socks5_proxy"))
            val references = credentials.map { key ->
                val reference = root.getJSONObject("credentials").getJSONObject(key)
                require(reference.length() == 1 && reference.has("file"))
                val path = reference.getString("file")
                require(safePath(path) && path != "yume.json")
                path
            }
            require(references.distinct().size == credentials.size)
            return (references + "yume.json").associateWith { path ->
                requireNotNull(files[path]) { "missing contained credential" }
            }
        }

        fun describe(config: ByteArray): ManualConfiguration {
            val root = JSONObject(config.toString(Charsets.UTF_8))
            val endpoint = root.getJSONObject("endpoint")
            require(!root.has("circuits") && !endpoint.has("socks5_proxy"))
            val services = root.getJSONArray("services")
            fun names(kind: String) = (0 until services.length()).map { services.getJSONObject(it) }
                .filter { it.getString("kind") == kind }.map { it.getString("name") }
            return ManualConfiguration(endpoint.getString("host"), endpoint.getInt("port"), names("stream"), names("packet"),
                if (endpoint.has("connect_address")) endpoint.getString("connect_address") else null)
        }

        fun forNetwork(config: ByteArray, address: String): ByteArray {
            val root = JSONObject(config.toString(Charsets.UTF_8))
            root.put("adapters", JSONArray())
            root.remove("control")
            root.getJSONObject("endpoint").put("connect_address", address)
            return root.toString().toByteArray(Charsets.UTF_8)
        }

        fun safePath(path: String): Boolean = path.length in 1..240 && !path.startsWith('/') &&
            path.split('/').all { part -> part.isNotEmpty() && part != "." && part != ".." && part.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" } }
    }
}
