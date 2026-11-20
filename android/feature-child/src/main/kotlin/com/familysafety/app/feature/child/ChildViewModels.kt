package com.familysafety.app.feature.child

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familysafety.app.core.ui.LoadState
import com.familysafety.app.core.ui.toLoadState
import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.data.work.WorkScheduler
import com.familysafety.app.data.webrtc.ScreenShareCoordinator
import com.familysafety.app.domain.logic.Formatting
import com.familysafety.app.domain.logic.ShareState
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.DailyUsage
import com.familysafety.app.domain.model.PeriodUsage
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.SafetySettings
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.domain.repository.DeviceRepository
import com.familysafety.app.domain.repository.PairingRepository
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.SafetyRepository
import com.familysafety.app.domain.repository.UsageCollector
import com.familysafety.app.domain.repository.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ChildPairingViewModel @Inject constructor(
    private val pairing: PairingRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val _code = MutableStateFlow("")
    val code = _code.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun onCodeChange(input: String) { _code.value = Formatting.normalizePairingCode(input); _error.value = null }

    fun submit() = viewModelScope.launch {
        if (!Formatting.isValidPairingCode(_code.value)) { _error.value = "Enter the 8-character code shown on your parent's phone."; return@launch }
        _busy.value = true
        when (val r = pairing.acceptCode(_code.value)) {
            is AppResult.Success -> _error.value = null // AuthState now reports paired; navigation follows automatically.
            is AppResult.Failure -> _error.value = r.error.message
        }
        _busy.value = false
    }

    fun signOut() = viewModelScope.launch { auth.logout() }
}

data class PermissionStatus(val usageAccess: Boolean, val notifications: Boolean)

@HiltViewModel
class ChildHomeViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val collector: UsageCollector,
    private val safety: SafetyRepository,
    private val scheduler: WorkScheduler,
    private val notifier: Notifier,
    realtime: RealtimeRepository,
    val coordinator: ScreenShareCoordinator,
) : ViewModel() {
    val authState = auth.authState
    val today: StateFlow<DailyUsage?> = collector.observeLocalToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val shareState: StateFlow<ShareState> = coordinator.state
    val connection: StateFlow<ConnectionState> = realtime.connectionState
    private val _permissions = MutableStateFlow(PermissionStatus(false, false))
    val permissions = _permissions.asStateFlow()
    private val _lastSync = MutableStateFlow<Instant?>(null)
    val lastSync = _lastSync.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            realtime.events.filterIsInstance<RealtimeEvent.DeviceRevoked>().collect { auth.clearLocalSession() }
        }
    }

    /** Called on resume: permission state can change in system Settings at any time. */
    fun onResume() {
        _permissions.value = PermissionStatus(collector.hasUsageAccess(), notifier.canNotify())
        viewModelScope.launch {
            coordinator.refreshFromServer()
            auth.refreshProfile()
        }
        if (collector.hasUsageAccess()) syncNow(silent = true)
    }

    fun syncNow(silent: Boolean = false) = viewModelScope.launch {
        if (!collector.hasUsageAccess()) { if (!silent) _message.value = "Turn on usage access first."; return@launch }
        _syncing.value = true
        when (val r = collector.syncNow()) {
            is AppResult.Success -> { _lastSync.value = Instant.now(); if (!silent) _message.value = "Activity summary shared" }
            is AppResult.Failure -> { collector.collect(7); if (!silent) _message.value = r.error.message }
        }
        _syncing.value = false
    }

    fun checkIn() = send(AlertType.CHECK_IN, "I'm OK", null, "Check-in sent")

    fun send(type: AlertType, message: String?, category: String?, success: String) = viewModelScope.launch {
        _message.value = when (val r = safety.createAlert(type, message, category = category)) {
            is AppResult.Success -> success
            is AppResult.Failure -> r.error.message
        }
    }

    fun scheduleBackgroundSync() = scheduler.syncUsageNow()
    fun consumeMessage() { _message.value = null }
}

@HiltViewModel
class ActivitySummaryViewModel @Inject constructor(
    private val collector: UsageCollector,
    private val usage: UsageRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val today: StateFlow<DailyUsage?> = collector.observeLocalToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _week = MutableStateFlow<LoadState<PeriodUsage>>(LoadState.Loading)
    val week = _week.asStateFlow()
    val hasAccess get() = collector.hasUsageAccess()

    init { load() }

    fun load() = viewModelScope.launch {
        collector.collect(7)
        val deviceId = (auth.authState.value as? AuthState.SignedIn)?.deviceId ?: return@launch
        _week.value = LoadState.Loading
        _week.value = usage.weekly(deviceId, LocalDate.now()).toLoadState()
    }
}

@HiltViewModel
class ChildSafetyViewModel @Inject constructor(
    private val safety: SafetyRepository,
    realtime: RealtimeRepository,
) : ViewModel() {
    private val _alerts = MutableStateFlow<LoadState<List<SafetyAlert>>>(LoadState.Loading)
    val alerts = _alerts.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    init {
        load()
        viewModelScope.launch { realtime.events.filterIsInstance<RealtimeEvent.Alert>().collect { load() } }
    }

    fun load() = viewModelScope.launch { _alerts.value = safety.alerts().toLoadState() }

    fun send(type: AlertType, message: String?, category: String? = null) = viewModelScope.launch {
        _sending.value = true
        _message.value = when (val r = safety.createAlert(type, message, category = category)) {
            is AppResult.Success -> { load(); "Sent to your parents" }
            is AppResult.Failure -> r.error.message
        }
        _sending.value = false
    }

    fun acknowledge(id: String) = viewModelScope.launch { if (safety.acknowledge(id) is AppResult.Success) load() }
    fun consumeMessage() { _message.value = null }
}

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val safety: SafetyRepository,
    private val devices: DeviceRepository,
) : ViewModel() {
    val authState = auth.authState
    private val _settings = MutableStateFlow<LoadState<SafetySettings>>(LoadState.Loading)
    val settings = _settings.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val deviceId get() = (auth.authState.value as? AuthState.SignedIn)?.deviceId

    init { load() }

    fun load() = viewModelScope.launch { deviceId?.let { _settings.value = safety.settings(it).toLoadState() } }

    fun requestDisconnect() = viewModelScope.launch {
        val id = deviceId ?: return@launch
        _message.value = when (val r = devices.requestDisconnect(id)) {
            is AppResult.Success -> "Your parents were asked to disconnect this device."
            is AppResult.Failure -> r.error.message
        }
    }

    fun signOut() = viewModelScope.launch { auth.logout() }
    fun consumeMessage() { _message.value = null }
}

@HiltViewModel
class ScreenShareConsentViewModel @Inject constructor(
    val coordinator: ScreenShareCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state: StateFlow<ShareState> = coordinator.state
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { viewModelScope.launch { coordinator.refreshFromServer() } }

    fun approve() = viewModelScope.launch {
        val r = coordinator.approve()
        if (r is AppResult.Failure) _error.value = r.error.message
    }

    fun reject() = viewModelScope.launch { coordinator.reject() }

    fun onCaptureResult(resultCode: Int, data: Intent?) = viewModelScope.launch {
        val granted = resultCode == android.app.Activity.RESULT_OK && data != null
        coordinator.onSystemPermission(granted)
        if (granted) ScreenCaptureService.start(context, data!!)
    }

    fun stop() = viewModelScope.launch { coordinator.stop() }
    fun dismiss() = coordinator.dismiss()
}
