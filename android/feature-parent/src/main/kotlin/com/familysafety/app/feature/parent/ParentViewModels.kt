package com.familysafety.app.feature.parent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familysafety.app.core.ui.LoadState
import com.familysafety.app.core.ui.toLoadState
import com.familysafety.app.data.webrtc.PeerState
import com.familysafety.app.data.webrtc.ScreenShareViewer
import com.familysafety.app.data.webrtc.WebRtcEngine
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppRanking
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.DailyUsage
import com.familysafety.app.domain.model.DeviceSummary
import com.familysafety.app.domain.model.PairingCode
import com.familysafety.app.domain.model.PeriodUsage
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.SafetySettings
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.domain.repository.DeviceRepository
import com.familysafety.app.domain.repository.PairingRepository
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.SafetyRepository
import com.familysafety.app.domain.repository.ScreenShareRepository
import com.familysafety.app.domain.repository.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** Devices + alerts for the dashboard and device list; refreshes on realtime notices. */
@HiltViewModel
class ParentHomeViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val safety: SafetyRepository,
    realtime: RealtimeRepository,
    auth: AuthRepository,
) : ViewModel() {
    val deviceList: StateFlow<List<DeviceSummary>> = devices.observeDevices().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce = _loadedOnce.asStateFlow()
    private val _unacknowledged = MutableStateFlow<List<SafetyAlert>>(emptyList())
    val unacknowledged = _unacknowledged.asStateFlow()
    val parentName: StateFlow<String> = MutableStateFlow((auth.authState.value as? AuthState.SignedIn)?.user?.displayName ?: "")

    init {
        refresh()
        viewModelScope.launch {
            realtime.events.collect { e ->
                when (e) {
                    is RealtimeEvent.PairingCompleted, is RealtimeEvent.DeviceRevoked, is RealtimeEvent.UsageSynced -> refresh()
                    is RealtimeEvent.Alert -> refreshAlerts()
                    else -> Unit
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        when (val r = devices.refresh()) {
            is AppResult.Success -> _error.value = null
            is AppResult.Failure -> _error.value = r.error.message
        }
        refreshAlerts()
        _loadedOnce.value = true
        _refreshing.value = false
    }

    private suspend fun refreshAlerts() {
        (safety.alerts(unacknowledgedOnly = true) as? AppResult.Success)?.let { _unacknowledged.value = it.value }
    }
}

@HiltViewModel
class PairDeviceViewModel @Inject constructor(
    private val pairing: PairingRepository,
    realtime: RealtimeRepository,
) : ViewModel() {
    sealed interface UiState {
        data object Idle : UiState
        data object Creating : UiState
        data class ShowingCode(val code: PairingCode) : UiState
        data class Paired(val childName: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            realtime.events.filterIsInstance<RealtimeEvent.PairingCompleted>().collect {
                if (_state.value is UiState.ShowingCode) _state.value = UiState.Paired(it.childDisplayName)
            }
        }
    }

    fun create(label: String) = viewModelScope.launch {
        _state.value = UiState.Creating
        _state.value = when (val r = pairing.createCode(label)) {
            is AppResult.Success -> UiState.ShowingCode(r.value)
            is AppResult.Failure -> UiState.Error(r.error.message)
        }
    }

    fun reset() { _state.value = UiState.Idle }
}

data class DeviceDetailsUi(val device: DeviceSummary, val today: DailyUsage?)

@HiltViewModel
class DeviceDetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val devices: DeviceRepository,
    private val usage: UsageRepository,
    private val safety: SafetyRepository,
) : ViewModel() {
    val deviceId: String = checkNotNull(savedState["deviceId"])
    private val _state = MutableStateFlow<LoadState<DeviceDetailsUi>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _revoked = MutableStateFlow(false)
    val revoked = _revoked.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        _state.value = when (val d = devices.getDevice(deviceId)) {
            is AppResult.Failure -> LoadState.Error(d.error.message)
            is AppResult.Success -> LoadState.Ready(DeviceDetailsUi(d.value, (usage.daily(deviceId, LocalDate.now()) as? AppResult.Success)?.value))
        }
    }

    fun requestCheckIn() = viewModelScope.launch {
        _message.value = when (val r = safety.createAlert(AlertType.CHECK_IN_REQUEST, "Please check in when you can.", deviceId)) {
            is AppResult.Success -> "Check-in request sent"
            is AppResult.Failure -> r.error.message
        }
    }

    fun revoke() = viewModelScope.launch {
        when (val r = devices.revoke(deviceId, "REVOKED_BY_PARENT")) {
            is AppResult.Success -> _revoked.value = true
            is AppResult.Failure -> _message.value = r.error.message
        }
    }

    fun consumeMessage() { _message.value = null }
}

@HiltViewModel
class UsageAnalyticsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val usage: UsageRepository,
) : ViewModel() {
    enum class Period { WEEK, MONTH }

    val deviceId: String = checkNotNull(savedState["deviceId"])
    private val _period = MutableStateFlow(Period.WEEK)
    val period = _period.asStateFlow()
    private val _state = MutableStateFlow<LoadState<PeriodUsage>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private val _dailyLimit = MutableStateFlow<Int?>(null)
    val dailyLimit = _dailyLimit.asStateFlow()

    init { load() }

    fun select(p: Period) { _period.value = p; load() }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        val today = LocalDate.now()
        _state.value = when (_period.value) {
            Period.WEEK -> usage.weekly(deviceId, today)
            Period.MONTH -> usage.monthly(deviceId, YearMonth.from(today))
        }.toLoadState()
        (usage.daily(deviceId, today) as? AppResult.Success)?.let { _dailyLimit.value = it.value.dailyLimitMinutes }
    }
}

@HiltViewModel
class AppActivityViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val usage: UsageRepository,
) : ViewModel() {
    enum class Range(val days: Long, val label: String) { TODAY(1, "Today"), WEEK(7, "7 days"), MONTH(30, "30 days") }

    val deviceId: String = checkNotNull(savedState["deviceId"])
    private val _range = MutableStateFlow(Range.WEEK)
    val range = _range.asStateFlow()
    private val _state = MutableStateFlow<LoadState<List<AppRanking>>>(LoadState.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun select(r: Range) { _range.value = r; load() }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        val to = LocalDate.now()
        _state.value = usage.applications(deviceId, to.minusDays(_range.value.days - 1), to, 100).toLoadState()
    }
}

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val safety: SafetyRepository,
    realtime: RealtimeRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<List<SafetyAlert>>>(LoadState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { realtime.events.filterIsInstance<RealtimeEvent.Alert>().collect { load(silent = true) } }
    }

    fun load(silent: Boolean = false) = viewModelScope.launch {
        if (!silent) _state.value = LoadState.Loading
        _state.value = safety.alerts().toLoadState()
    }

    fun acknowledge(id: String) = viewModelScope.launch {
        if (safety.acknowledge(id) is AppResult.Success) load(silent = true)
    }
}

@HiltViewModel
class SafetySettingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val safety: SafetyRepository,
) : ViewModel() {
    val deviceId: String = checkNotNull(savedState["deviceId"])
    private val _state = MutableStateFlow<LoadState<SafetySettings>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch { _state.value = safety.settings(deviceId).toLoadState() }

    fun edit(transform: (SafetySettings) -> SafetySettings) = _state.update { s -> if (s is LoadState.Ready) LoadState.Ready(transform(s.data)) else s }

    fun save() = viewModelScope.launch {
        val current = (_state.value as? LoadState.Ready)?.data ?: return@launch
        _saving.value = true
        _message.value = when (val r = safety.updateSettings(current)) {
            is AppResult.Success -> { _state.value = LoadState.Ready(r.value); "Settings saved" }
            is AppResult.Failure -> r.error.message
        }
        _saving.value = false
    }

    fun consumeMessage() { _message.value = null }
}

/**
 * Parent-side viewer. Sequence: request → wait for child approval (realtime push, with REST polling fallback)
 * → receive the child's WebRTC offer → answer → render. Leaving the screen stops the session.
 */
@HiltViewModel
class ScreenViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ScreenShareRepository,
    private val realtime: RealtimeRepository,
    private val engine: WebRtcEngine,
    @com.familysafety.app.data.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {
    sealed interface Phase {
        data object Idle : Phase
        data object Requesting : Phase
        data class WaitingForChild(val session: ScreenShareSession) : Phase
        data class Connecting(val session: ScreenShareSession) : Phase
        data class Live(val session: ScreenShareSession) : Phase
        data class Ended(val message: String) : Phase
        data class Error(val message: String) : Phase
    }

    val deviceId: String = checkNotNull(savedState["deviceId"])
    private val viewer = ScreenShareViewer(engine, realtime, viewModelScope)
    val eglBase: EglBase get() = engine.eglBase
    val remoteTrack: StateFlow<VideoTrack?> = viewer.remoteTrack
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase = _phase.asStateFlow()
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            realtime.events.filterIsInstance<RealtimeEvent.ScreenShareUpdated>().collect { onUpdate(it.session) }
        }
        viewModelScope.launch {
            viewer.state.collect { s ->
                val p = _phase.value
                when (s) {
                    PeerState.CONNECTED -> if (p is Phase.Connecting) _phase.value = Phase.Live(p.session)
                    PeerState.FAILED -> if (p is Phase.Connecting || p is Phase.Live) {
                        currentSessionId()?.let { repo.stop(it, com.familysafety.app.domain.model.StopReason.CONNECTION_FAILED) }
                        endWith("The connection failed. Check that both devices are online and try again.")
                    }
                    else -> Unit
                }
            }
        }
    }

    fun request() = viewModelScope.launch {
        realtime.connect()
        _phase.value = Phase.Requesting
        when (val r = repo.request(deviceId)) {
            is AppResult.Success -> { _phase.value = Phase.WaitingForChild(r.value); startPolling(r.value.id) }
            is AppResult.Failure -> _phase.value = Phase.Error(r.error.message)
        }
    }

    private fun startPolling(sessionId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(3_000)
                (repo.status(sessionId) as? AppResult.Success)?.value?.let { onUpdate(it) }
            }
        }
    }

    private suspend fun onUpdate(session: ScreenShareSession) {
        val current = currentSessionId() ?: return
        if (session.id != current) return
        when (session.status) {
            ScreenShareStatus.APPROVED, ScreenShareStatus.ACTIVE -> if (_phase.value is Phase.WaitingForChild) {
                // ICE servers are included only in participant-scoped status responses.
                val withIce = if (session.iceServers.isEmpty()) (repo.status(session.id) as? AppResult.Success)?.value ?: session else session
                _phase.value = Phase.Connecting(withIce)
                viewer.start(withIce.id, withIce.iceServers)
            }
            ScreenShareStatus.REJECTED -> endWith("The request was declined.")
            ScreenShareStatus.EXPIRED -> endWith(if (session.endReason == "MAX_DURATION_REACHED") "The session reached its time limit." else "The request expired before it was answered.")
            ScreenShareStatus.STOPPED, ScreenShareStatus.FAILED -> endWith(endText(session.endReason))
            ScreenShareStatus.REQUESTED -> Unit
        }
    }

    private fun endText(reason: String?) = when (reason) {
        "STOPPED_BY_CHILD" -> "Your child stopped sharing."
        "STOPPED_BY_PARENT" -> "You stopped viewing."
        "DEVICE_REVOKED", "AUTHORIZATION_REVOKED" -> "Access to this device was revoked."
        "PARTICIPANT_DISCONNECTED" -> "The connection was lost."
        else -> "Screen sharing ended."
    }

    private fun currentSessionId(): String? = when (val p = _phase.value) {
        is Phase.WaitingForChild -> p.session.id
        is Phase.Connecting -> p.session.id
        is Phase.Live -> p.session.id
        else -> null
    }

    private fun endWith(message: String) {
        pollJob?.cancel()
        viewer.stop()
        _phase.value = Phase.Ended(message)
    }

    fun stop() = viewModelScope.launch {
        currentSessionId()?.let { repo.stop(it) }
        endWith("You stopped viewing.")
    }

    override fun onCleared() {
        // Leaving the viewer always ends the session server-side; done off the main thread.
        currentSessionId()?.let { id -> appScope.launch { repo.stop(id) } }
        viewer.stop()
    }
}

@HiltViewModel
class ParentSettingsViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    val authState = auth.authState
    fun signOut() = viewModelScope.launch { auth.logout() }
}
