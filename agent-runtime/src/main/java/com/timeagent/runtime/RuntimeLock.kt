package com.timeagent.runtime

import android.content.res.AssetManager
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray

internal data class RuntimeAsset(val name: String, val sha256: String, val strip: Int)

/**
 * Parsed assets/fish/runtime-lock.json. The lock is the only source of archive names,
 * hashes and strip counts; there is no fallback download or version guessing.
 */
internal class RuntimeLock(val assets: List<RuntimeAsset>) {
    fun baseMatches(installed: JSONArray): Boolean {
        fun base(items: List<RuntimeAsset>) = items.filter { it.name != OWNED_OVERLAY }.associateBy { it.name }
        val old = (0 until installed.length()).map { index -> installed.getJSONObject(index).let {
            RuntimeAsset(it.getString("name"),it.getString("sha256"),it.getInt("strip"))
        } }
        return base(assets).isNotEmpty() && base(assets) == base(old)
    }

    fun fingerprint(): String {
        val text = buildString {
            for (asset in assets) append(asset.name).append('\u0000').append(asset.sha256)
                .append('\u0000').append(asset.strip).append('\n')
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val OWNED_OVERLAY = "time-agent-overlay.bin"
        private val NAME = Regex("[A-Za-z0-9._-]{1,128}")
        private val SHA = Regex("[0-9a-fA-F]{64}")

        fun read(manager: AssetManager): RuntimeLock {
            val text =
                manager.open("fish/runtime-lock.json").use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 256 * 1024) throw IOException("RUNTIME_LOCK_TOO_LARGE")
                    String(bytes, Charsets.UTF_8)
                }
            val root = JSONObject(text)
            if (root.optInt("schema", -1) != 1) throw IOException("RUNTIME_LOCK_SCHEMA")
            val array = root.optJSONArray("archives") ?: throw IOException("RUNTIME_LOCK_ARCHIVES")
            val assets = ArrayList<RuntimeAsset>(array.length())
            val names = HashSet<String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw IOException("RUNTIME_LOCK_ENTRY")
                val name = item.optString("name")
                if (!NAME.matches(name) || name == "." || name == "..") {
                    throw IOException("RUNTIME_LOCK_NAME")
                }
                if (!names.add(name)) throw IOException("RUNTIME_LOCK_DUPLICATE")
                val sha = item.optString("sha256")
                if (!SHA.matches(sha)) throw IOException("RUNTIME_LOCK_SHA256")
                val strip = item.optInt("strip", -1)
                if (strip !in 0..64) throw IOException("RUNTIME_LOCK_STRIP")
                assets.add(RuntimeAsset(name, sha.lowercase(), strip))
            }
            if (assets.isEmpty()) throw IOException("RUNTIME_LOCK_EMPTY")
            return RuntimeLock(assets)
        }

        fun hex(bytes: ByteArray): String {
            val digits = "0123456789abcdef"
            val out = CharArray(bytes.size * 2)
            for (index in bytes.indices) {
                val value = bytes[index].toInt() and 0xFF
                out[index * 2] = digits[value ushr 4]
                out[index * 2 + 1] = digits[value and 0x0F]
            }
            return out.concatToString()
        }
    }
}
