package com.segaiptv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

// Protect the UI from very large playlists while reading the source as a stream.
private const val MAX_CHANNELS_PER_PLAYLIST = 50000

data class Playlist(val id: Int, val name: String, val url: String)
data class Channel(val name: String, val url: String, val category: String, val logo: String, val playlist: String)
data class AppConfig(
    val active: Boolean = false,
    val appName: String = "SEGA IPTV Player",
    val accent: String = "#E5474C",
    val background: String = "",
    val logo: String = "",
    val expires: String = "",
    val playlists: List<Playlist> = emptyList()
)

class DeviceIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences("segaiptv_identity", Context.MODE_PRIVATE)
    val id: String by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
    val key: String by lazy {
        prefs.getString("device_key", null) ?: (UUID.randomUUID().toString() + UUID.randomUUID().toString()).also {
            prefs.edit().putString("device_key", it).apply()
        }
    }
    // This is a synthetic MAC-shaped *app identifier*; Android blocks normal apps from
    // accessing actual hardware MAC addresses on modern Android.
    val virtualMac: String by lazy {
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        bytes.take(6).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
    var localUri: String?
        get() = prefs.getString("local_uri", null)
        set(value) { prefs.edit().putString("local_uri", value).apply() }

    fun readFavorites(): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString("favorites", "[]"))
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun saveFavorites(favorites: Set<String>) {
        prefs.edit().putString("favorites", JSONArray(favorites.toList()).toString()).apply()
    }
}

object Api {
    private fun request(path: String, method: String, payload: String? = null, deviceKey: String? = null): JSONObject {
        val con = (URL(BuildConfig.BACKEND_URL.trimEnd('/') + path).openConnection() as HttpURLConnection)
        con.connectTimeout = 12000
        con.readTimeout = 15000
        con.requestMethod = method
        con.setRequestProperty("Accept", "application/json")
        if (deviceKey != null) con.setRequestProperty("X-Device-Key", deviceKey)
        if (payload != null) {
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (payload != null) con.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (con.responseCode !in 200..299) {
                throw IllegalStateException("Server HTTP ${con.responseCode}: ${con.errorStream?.bufferedReader()?.use { it.readText() } ?: ""}")
            }
            return JSONObject(con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { con.disconnect() }
    }

    suspend fun registerAndFetch(identity: DeviceIdentity): AppConfig = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("device_id", identity.id)
            put("mac_alias", identity.virtualMac)
            put("device_key", identity.key)
        }
        request("/api/v1/register", "POST", payload.toString())
        val response = request("/api/v1/config?device_id=${identity.id}", "GET", deviceKey = identity.key)
        val rawPlaylists = response.optJSONArray("playlists") ?: JSONArray()
        val lists = (0 until rawPlaylists.length()).map { n ->
            val p = rawPlaylists.getJSONObject(n)
            Playlist(p.getInt("id"), p.getString("name"), p.getString("url"))
        }
        AppConfig(
            active = response.optBoolean("active"),
            appName = response.optString("app_name", "SEGA IPTV Player"),
            accent = response.optString("accent_color", "#E5474C"),
            background = response.optString("background_url", ""),
            logo = response.optString("logo_url", ""),
            expires = response.optString("expires_at", "").takeUnless { it == "null" } ?: "",
            playlists = lists
        )
    }

    suspend fun downloadPlaylist(playlist: Playlist): List<Channel> = withContext(Dispatchers.IO) {
        val connection = URL(playlist.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("M3U HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                M3uParser.parseLines(reader.lineSequence(), playlist.name, playlist.url)
            }
        } finally { connection.disconnect() }
    }

    suspend fun readLocalPlaylist(context: Context, uri: Uri): List<Channel> = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to read the selected file")
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            M3uParser.parseLines(reader.lineSequence(), "ملف محلي", "")
        }
    }


}

object M3uParser {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String, listName: String, baseUrl: String): List<Channel> =
        parseLines(text.lineSequence(), listName, baseUrl)

    fun parseLines(lines: Sequence<String>, listName: String, baseUrl: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var title = ""
        var group = "غير مصنف"
        var logo = ""
        for (line in lines) {
            val trimmed = line.trim().trimStart('\uFEFF')
            when {
                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attribute.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
                    title = trimmed.substringAfter(',', attrs["tvg-name"] ?: "قناة").trim()
                    group = attrs["group-title"].orEmpty().ifBlank { "غير مصنف" }
                    logo = attrs["tvg-logo"].orEmpty()
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> group = trimmed.substringAfter(':').trim()
                trimmed.isNotBlank() && !trimmed.startsWith('#') -> {
                    val resolved = runCatching {
                        if (baseUrl.isNotBlank()) URI(baseUrl).resolve(trimmed).toString() else trimmed
                    }.getOrDefault(trimmed)
                    if (resolved.startsWith("http://", true) || resolved.startsWith("https://", true)) {
                        channels += Channel(title.ifBlank { "قناة ${channels.size + 1}" }, resolved, group, logo, listName)
                    }
                    title = ""; group = "غير مصنف"; logo = ""
                }
            }
            if (channels.size >= MAX_CHANNELS_PER_PLAYLIST) break
        }
        return channels
    }
}
