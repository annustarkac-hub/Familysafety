package com.familysafety.app.data.server

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "fs_server")

sealed interface ServerUrlValidation {
    data class Valid(val url: HttpUrl) : ServerUrlValidation
    data class Invalid(val reason: String) : ServerUrlValidation
}

/**
 * Pure validation of a user-entered server address. Release builds accept HTTPS only (the network security
 * config forbids cleartext anyway); debug builds also accept http:// for local development.
 */
object ServerUrls {
    fun validate(input: String, allowCleartext: Boolean): ServerUrlValidation {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ServerUrlValidation.Invalid("Enter the server address.")
        if (trimmed.any { it.isWhitespace() }) return ServerUrlValidation.Invalid("The address must not contain spaces.")
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return ServerUrlValidation.Invalid("That doesn't look like a valid address.")
        if (url.scheme == "http" && !allowCleartext) {
            return ServerUrlValidation.Invalid("Use an https:// address. Unencrypted connections are blocked in this build.")
        }
        if (url.host.isBlank() || (!url.host.contains('.') && url.host != "localhost" && !url.host.all { it.isDigit() || it == '.' || it == ':' })) {
            return ServerUrlValidation.Invalid("Enter a full host name, for example api.example.com.")
        }
        if (url.query != null || url.fragment != null) return ServerUrlValidation.Invalid("Remove the ?query or #fragment from the address.")
        val normalized = url.newBuilder().apply {
            // Ensure the path ends with '/', so API paths are appended under it.
            val path = url.encodedPath
            if (!path.endsWith("/")) encodedPath("$path/")
        }.build()
        return ServerUrlValidation.Valid(normalized)
    }

    /** Hosts reserved for documentation never resolve; the shipped default is such a placeholder. */
    fun isPlaceholder(url: HttpUrl): Boolean =
        url.host.endsWith(".example") || url.host == "example.com" || url.host.endsWith(".example.com")
}

/**
 * The backend address used by REST, refresh and WebSocket traffic. Defaults to the build-time value and can be
 * changed by the user on the sign-in screen (only while signed out — tokens belong to one server).
 */
class ServerConfigStore(
    private val context: Context,
    defaultUrl: String,
    val allowCleartext: Boolean,
) {
    private val key = stringPreferencesKey("server_url_v1")
    val defaultUrl: HttpUrl = requireNotNull(defaultUrl.toHttpUrlOrNull()) { "Invalid default API URL: $defaultUrl" }

    private val _url = MutableStateFlow(this.defaultUrl)
    val url: StateFlow<HttpUrl> = _url.asStateFlow()

    /** Current base URL for synchronous use by OkHttp interceptors. */
    val current: HttpUrl get() = _url.value

    val isPlaceholder: Boolean get() = ServerUrls.isPlaceholder(current)

    suspend fun load() {
        val stored = context.serverDataStore.data.first()[key] ?: return
        (ServerUrls.validate(stored, allowCleartext) as? ServerUrlValidation.Valid)?.let { _url.value = it.url }
    }

    suspend fun save(url: HttpUrl) {
        context.serverDataStore.edit { it[key] = url.toString() }
        _url.value = url
    }

    suspend fun reset() {
        context.serverDataStore.edit { it.remove(key) }
        _url.value = defaultUrl
    }
}
