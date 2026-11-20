package com.familysafety.app.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.familysafety.app.core.security.SecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "fs_session")

@Serializable
data class StoredSession(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val role: String,
    val familyId: String? = null,
    val familyName: String? = null,
    val deviceId: String,
    val paired: Boolean = false,
)

/**
 * Persists the session encrypted with a Keystore-held AES-GCM key (see [SecretCipher]). Plain tokens exist only
 * in memory. The installation id is a random identifier (not a hardware id) used to recognise this install.
 */
class SessionStore(
    private val context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
) {
    private val keySession = stringPreferencesKey("session_v1")
    private val keyInstallation = stringPreferencesKey("installation_id")
    private val _session = MutableStateFlow<StoredSession?>(null)
    private val _loaded = MutableStateFlow(false)

    val session: StateFlow<StoredSession?> = _session.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Current access token for synchronous use by OkHttp interceptors. */
    val accessToken: String? get() = _session.value?.accessToken

    suspend fun load() {
        val prefs = context.sessionDataStore.data.first()
        _session.value = prefs[keySession]?.let { enc ->
            runCatching { json.decodeFromString(StoredSession.serializer(), cipher.decrypt(enc)) }.getOrNull()
        }
        _loaded.value = true
    }

    suspend fun save(session: StoredSession) {
        val enc = cipher.encrypt(json.encodeToString(StoredSession.serializer(), session))
        context.sessionDataStore.edit { it[keySession] = enc }
        _session.value = session
    }

    suspend fun update(transform: (StoredSession) -> StoredSession) {
        _session.value?.let { save(transform(it)) }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.remove(keySession) }
        _session.value = null
    }

    suspend fun installationId(): String {
        val prefs = context.sessionDataStore.data.first()
        prefs[keyInstallation]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.sessionDataStore.edit { it[keyInstallation] = id }
        return id
    }
}
