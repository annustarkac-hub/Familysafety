package com.familysafety.app.feature.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familysafety.app.core.designsystem.ConfirmDialog
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenViewerScreen(onBack: () -> Unit, vm: ScreenViewerViewModel = hiltViewModel()) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val track by vm.remoteTrack.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("View screen") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val status = when (val p = phase) {
                ScreenViewerViewModel.Phase.Idle -> "Your child will be asked to approve. They see a notification and can stop sharing at any time. Sessions are live only and are not recorded."
                ScreenViewerViewModel.Phase.Requesting -> "Sending request…"
                is ScreenViewerViewModel.Phase.WaitingForChild -> "Waiting for your child to approve…"
                is ScreenViewerViewModel.Phase.Connecting -> "Approved. Connecting…"
                is ScreenViewerViewModel.Phase.Live -> "Live"
                is ScreenViewerViewModel.Phase.Ended -> p.message
                is ScreenViewerViewModel.Phase.Error -> p.message
            }
            Text(status, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (phase is ScreenViewerViewModel.Phase.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)

            when (phase) {
                ScreenViewerViewModel.Phase.Idle, is ScreenViewerViewModel.Phase.Ended, is ScreenViewerViewModel.Phase.Error ->
                    Button(onClick = { vm.request() }) { Text(if (phase == ScreenViewerViewModel.Phase.Idle) "Request to view screen" else "Request again") }
                ScreenViewerViewModel.Phase.Requesting, is ScreenViewerViewModel.Phase.WaitingForChild, is ScreenViewerViewModel.Phase.Connecting -> {
                    CircularProgressIndicator()
                    OutlinedButton(onClick = { vm.stop() }) { Text("Cancel") }
                }
                is ScreenViewerViewModel.Phase.Live -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FiberManualRecord, null, tint = Color.Red)
                        Spacer(Modifier.width(4.dp))
                        Text("Your child can see that sharing is active", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { confirmStop = true }) { Text("Stop viewing") }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                val t = track
                if (t != null && (phase is ScreenViewerViewModel.Phase.Live || phase is ScreenViewerViewModel.Phase.Connecting)) {
                    RemoteVideo(t, vm.eglBase, Modifier.fillMaxSize().semantics { contentDescription = "Child's shared screen" })
                } else {
                    Text("No video", color = Color.White.copy(alpha = 0.6f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (confirmStop) ConfirmDialog("Stop viewing?", "The screen-sharing session will end for both of you.", "Stop",
            onConfirm = { confirmStop = false; vm.stop() }, onDismiss = { confirmStop = false })
    }
}

@Composable
private fun RemoteVideo(track: VideoTrack, egl: EglBase, modifier: Modifier) {
    val renderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(modifier = modifier, factory = { ctx ->
        SurfaceViewRenderer(ctx).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            renderer.value = this
        }
    })
    DisposableEffect(track, renderer.value) {
        val r = renderer.value
        r?.let { track.addSink(it) }
        onDispose { r?.let { runCatching { track.removeSink(it) } } }
    }
    DisposableEffect(Unit) { onDispose { renderer.value?.release() } }
}
