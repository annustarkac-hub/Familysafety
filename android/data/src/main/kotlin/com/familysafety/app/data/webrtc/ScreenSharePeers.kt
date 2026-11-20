package com.familysafety.app.data.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.familysafety.app.domain.model.IceServer
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.repository.RealtimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.MediaStream

enum class PeerState { IDLE, NEGOTIATING, CONNECTED, FAILED, CLOSED }

/** Buffers remote ICE candidates until the remote description is applied. */
private class IceBuffer(private val pc: () -> PeerConnection?) {
    private val pending = mutableListOf<IceCandidate>()
    var remoteSet = false
        set(value) { field = value; if (value) flush() }

    @Synchronized fun add(c: IceCandidate) { if (remoteSet) pc()?.addIceCandidate(c) else pending += c }
    @Synchronized private fun flush() { pending.forEach { pc()?.addIceCandidate(it) }; pending.clear() }
}

private fun IceCandidate.toSignal() = mapOf("kind" to "ice", "candidate" to sdp, "sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex)

/**
 * Child side: captures the screen with MediaProjection (via WebRTC's ScreenCapturerAndroid) and publishes a
 * send-only video track. MUST be started from a foreground service of type mediaProjection after the user
 * granted the system capture dialog. Audio is never captured.
 */
class ScreenSharePublisher(
    private val context: Context,
    private val engine: WebRtcEngine,
    private val realtime: RealtimeRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PeerState.IDLE)
    val state: StateFlow<PeerState> = _state.asStateFlow()

    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var signalJob: Job? = null
    private var sessionId: String? = null

    fun start(sessionId: String, iceServers: List<IceServer>, projectionData: Intent, onProjectionStopped: () -> Unit) {
        check(this.sessionId == null) { "Publisher already started" }
        this.sessionId = sessionId
        _state.value = PeerState.NEGOTIATING
        val factory = engine.factory
        val (w, h) = captureSize()
        textureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", engine.eglBase.eglBaseContext)
        source = factory.createVideoSource(true)
        capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() = onProjectionStopped()
        }).also {
            it.initialize(textureHelper, context, source!!.capturerObserver)
            it.startCapture(w, h, 15)
        }
        track = factory.createVideoTrack("screen0", source)

        val ice = IceBuffer { pc }
        pc = factory.createPeerConnection(engine.rtcConfig(iceServers), PeerObserver(
            onIce = { realtime.sendSignal(sessionId, it.toSignal()) },
            onState = { s -> _state.value = s.toPeerState() },
        ))
        pc!!.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("screen")))

        signalJob = scope.launch {
            launch {
                realtime.events.filterIsInstance<RealtimeEvent.Signal>().filter { it.sessionId == sessionId }.collect { sig ->
                    when (sig.kind) {
                        "answer" -> sig.sdp?.let {
                            runCatching { pc?.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, it)) }
                                .onSuccess { ice.remoteSet = true }.onFailure { _state.value = PeerState.FAILED }
                        }
                        "ice" -> if (sig.candidate != null) ice.add(IceCandidate(sig.sdpMid, sig.sdpMLineIndex ?: 0, sig.candidate))
                        "bye" -> _state.value = PeerState.CLOSED
                    }
                }
            }
            runCatching {
                val offer = pc!!.createOfferAwait(MediaConstraints())
                pc!!.setLocalAwait(offer)
                check(realtime.sendSignal(sessionId, mapOf("kind" to "offer", "sdp" to offer.description))) { "signaling channel unavailable" }
            }.onFailure { _state.value = PeerState.FAILED }
        }
    }

    fun stop() {
        sessionId?.let { realtime.sendSignal(it, mapOf("kind" to "bye")) }
        signalJob?.cancel()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        track?.dispose()
        source?.dispose()
        pc?.dispose()
        textureHelper?.dispose()
        capturer = null; track = null; source = null; pc = null; textureHelper = null; sessionId = null
        _state.value = PeerState.CLOSED
    }

    /** Downscale so the long edge is at most 1280 px: enough to read the screen, light on bandwidth. */
    private fun captureSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        // Encoders require even dimensions.
        return ((metrics.widthPixels * scale).toInt() and 1.inv()) to ((metrics.heightPixels * scale).toInt() and 1.inv())
    }
}

/** Parent side: receives the child's offer, answers with a receive-only connection and exposes the video track. */
class ScreenShareViewer(
    private val engine: WebRtcEngine,
    private val realtime: RealtimeRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PeerState.IDLE)
    val state: StateFlow<PeerState> = _state.asStateFlow()
    private val _track = MutableStateFlow<VideoTrack?>(null)
    val remoteTrack: StateFlow<VideoTrack?> = _track.asStateFlow()

    private var pc: PeerConnection? = null
    private var job: Job? = null

    fun start(sessionId: String, iceServers: List<IceServer>) {
        if (job != null) return
        _state.value = PeerState.NEGOTIATING
        val ice = IceBuffer { pc }
        pc = engine.factory.createPeerConnection(engine.rtcConfig(iceServers), object : PeerObserver(
            onIce = { realtime.sendSignal(sessionId, it.toSignal()) },
            onState = { s -> _state.value = s.toPeerState() },
        ) {
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { _track.value = it }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val t = transceiver?.receiver?.track()
                if (t is VideoTrack && t.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) _track.value = t
            }
        })
        job = scope.launch {
            realtime.events.filterIsInstance<RealtimeEvent.Signal>().filter { it.sessionId == sessionId }.collect { sig ->
                when (sig.kind) {
                    "offer" -> sig.sdp?.let { sdp ->
                        runCatching {
                            pc!!.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, sdp))
                            ice.remoteSet = true
                            val answer = pc!!.createAnswerAwait(MediaConstraints().apply {
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            })
                            pc!!.setLocalAwait(answer)
                            realtime.sendSignal(sessionId, mapOf("kind" to "answer", "sdp" to answer.description))
                        }.onFailure { _state.value = PeerState.FAILED }
                    }
                    "ice" -> if (sig.candidate != null) ice.add(IceCandidate(sig.sdpMid, sig.sdpMLineIndex ?: 0, sig.candidate))
                    "bye" -> _state.value = PeerState.CLOSED
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _track.value = null
        pc?.dispose(); pc = null
        _state.value = PeerState.CLOSED
    }
}

private fun PeerConnection.PeerConnectionState.toPeerState() = when (this) {
    PeerConnection.PeerConnectionState.CONNECTED -> PeerState.CONNECTED
    PeerConnection.PeerConnectionState.FAILED -> PeerState.FAILED
    PeerConnection.PeerConnectionState.CLOSED, PeerConnection.PeerConnectionState.DISCONNECTED -> PeerState.CLOSED
    else -> PeerState.NEGOTIATING
}
