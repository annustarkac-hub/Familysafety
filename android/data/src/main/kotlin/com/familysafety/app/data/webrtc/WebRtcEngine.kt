package com.familysafety.app.data.webrtc

import android.content.Context
import com.familysafety.app.domain.model.IceServer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lazily initialised, process-wide WebRTC factory and EGL context. */
class WebRtcEngine(private val context: Context) {
    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun rtcConfig(iceServers: List<IceServer>): PeerConnection.RTCConfiguration {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }
}

/** Minimal PeerConnection.Observer forwarding the callbacks we use. */
open class PeerObserver(
    private val onIce: (IceCandidate) -> Unit,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
) : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate) = onIce(candidate)
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = onState(newState)
}

suspend fun PeerConnection.createOfferAwait(constraints: MediaConstraints): SessionDescription = suspendCancellableCoroutine { c ->
    createOffer(object : SdpAdapter() {
        override fun onCreateSuccess(sdp: SessionDescription) { c.resume(sdp) }
        override fun onCreateFailure(error: String?) { c.resumeWithException(IllegalStateException("createOffer: $error")) }
    }, constraints)
}

suspend fun PeerConnection.createAnswerAwait(constraints: MediaConstraints): SessionDescription = suspendCancellableCoroutine { c ->
    createAnswer(object : SdpAdapter() {
        override fun onCreateSuccess(sdp: SessionDescription) { c.resume(sdp) }
        override fun onCreateFailure(error: String?) { c.resumeWithException(IllegalStateException("createAnswer: $error")) }
    }, constraints)
}

suspend fun PeerConnection.setLocalAwait(sdp: SessionDescription): Unit = suspendCancellableCoroutine { c ->
    setLocalDescription(object : SdpAdapter() {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(error: String?) { c.resumeWithException(IllegalStateException("setLocalDescription: $error")) }
    }, sdp)
}

suspend fun PeerConnection.setRemoteAwait(sdp: SessionDescription): Unit = suspendCancellableCoroutine { c ->
    setRemoteDescription(object : SdpAdapter() {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(error: String?) { c.resumeWithException(IllegalStateException("setRemoteDescription: $error")) }
    }, sdp)
}

abstract class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
