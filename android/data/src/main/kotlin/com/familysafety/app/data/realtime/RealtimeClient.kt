package com.familysafety.app.data.realtime

import com.familysafety.app.data.network.RealtimeEnvelope
import com.familysafety.app.data.network.SafetyAlertDto
import com.familysafety.app.data.network.ScreenShareSessionDto
import com.familysafety.app.data.network.toDomain
import com.familysafety.app.data.session.RefreshOutcome
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.data.session.TokenRefresher
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.repository.RealtimeRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Authenticated WebSocket to /ws. The bearer token is sent in the Authorization header (never the URL).
 * Reconnects with exponential backoff while [connect] is in effect; an HTTP 401 on the upgrade triggers a
 * token refresh first.
 */
class RealtimeClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val store: SessionStore,
    private val refresher: TokenRefresher,
    private val json: Json,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : RealtimeRepository {
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    val wsUrl: String get() = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/ws"

    @Synchronized
    override fun connect() {
        wanted = true
        if (socket != null || store.accessToken == null) return
        open()
    }

    @Synchronized
    override fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        socket?.close(1000, "client closing")
        socket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    override fun sendSignal(sessionId: String, payload: Map<String, Any?>): Boolean {
        val obj = buildJsonObject {
            put("type", JsonPrimitive("signal"))
            put("sessionId", JsonPrimitive(sessionId))
            put("payload", JsonObject(payload.mapValues { (_, v) -> v.toJson() }))
        }
        return socket?.send(obj.toString()) ?: false
    }

    private fun open() {
        val token = store.accessToken ?: return
        _state.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                _state.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { _events.tryEmit(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDropped(webSocket, unauthorized = false)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onDropped(webSocket, unauthorized = response?.code == 401)
        })
    }

    @Synchronized
    private fun onDropped(ws: WebSocket, unauthorized: Boolean) {
        if (socket !== ws) return
        socket = null
        _state.value = ConnectionState.DISCONNECTED
        if (!wanted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(io) {
            if (unauthorized) {
                if (refresher.refreshBlocking(store.accessToken) is RefreshOutcome.SessionEnded) { wanted = false; return@launch }
            }
            val backoff = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            attempt++
            delay(backoff)
            synchronized(this@RealtimeClient) { if (wanted && socket == null) open() }
        }
    }

    internal fun parse(text: String): RealtimeEvent? = runCatching {
        val env = json.decodeFromString(RealtimeEnvelope.serializer(), text)
        val p = env.payload
        when (env.type) {
            "screen_share.requested" -> RealtimeEvent.ScreenShareRequested(json.decodeFromJsonElement<ScreenShareSessionDto>(p!!).toDomain())
            "screen_share.updated" -> RealtimeEvent.ScreenShareUpdated(json.decodeFromJsonElement<ScreenShareSessionDto>(p!!).toDomain())
            "signal" -> {
                val o = p!!.jsonObject
                val sig = o["payload"]!!.jsonObject
                RealtimeEvent.Signal(
                    sessionId = o["sessionId"]!!.jsonPrimitive.content,
                    kind = sig["kind"]?.jsonPrimitive?.content ?: "",
                    sdp = sig["sdp"]?.jsonPrimitive?.contentOrNull,
                    candidate = sig["candidate"]?.jsonPrimitive?.contentOrNull,
                    sdpMid = sig["sdpMid"]?.jsonPrimitive?.contentOrNull,
                    sdpMLineIndex = sig["sdpMLineIndex"]?.jsonPrimitive?.let { runCatching { it.int }.getOrNull() },
                )
            }
            "pairing.completed" -> p!!.jsonObject.let {
                RealtimeEvent.PairingCompleted(it["deviceId"]!!.jsonPrimitive.content, it["childDisplayName"]?.jsonPrimitive?.content ?: "")
            }
            "device.revoked" -> RealtimeEvent.DeviceRevoked(p!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
            "safety.alert" -> RealtimeEvent.Alert(json.decodeFromJsonElement<SafetyAlertDto>(p!!).toDomain())
            "usage.synced" -> RealtimeEvent.UsageSynced(p!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
            "safety.settings_updated" -> RealtimeEvent.SettingsUpdated
            "error" -> RealtimeEvent.Error(p?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "UNKNOWN")
            else -> null
        }
    }.getOrNull()
}

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
