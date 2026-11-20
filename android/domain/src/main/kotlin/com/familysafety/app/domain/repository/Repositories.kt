package com.familysafety.app.domain.repository

import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppRanking
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.DailyUsage
import com.familysafety.app.domain.model.DeviceSummary
import com.familysafety.app.domain.model.PairingCode
import com.familysafety.app.domain.model.PairingResult
import com.familysafety.app.domain.model.PeriodUsage
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.SafetySettings
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.YearMonth

interface AuthRepository {
    val authState: StateFlow<AuthState>
    suspend fun register(email: String, password: String, displayName: String, role: Role): AppResult<Unit>
    suspend fun login(email: String, password: String): AppResult<Unit>
    suspend fun refreshProfile(): AppResult<Unit>
    suspend fun logout()
    /** Clears local credentials without contacting the server (e.g. after revocation). */
    suspend fun clearLocalSession()
}

interface PairingRepository {
    suspend fun createCode(childLabel: String?): AppResult<PairingCode>
    suspend fun acceptCode(code: String): AppResult<PairingResult>
}

interface DeviceRepository {
    /** Cached device list (Room), refreshed from the backend by [refresh]. */
    fun observeDevices(): Flow<List<DeviceSummary>>
    suspend fun refresh(): AppResult<List<DeviceSummary>>
    suspend fun getDevice(deviceId: String): AppResult<DeviceSummary>
    suspend fun revoke(deviceId: String, reason: String?): AppResult<Unit>
    suspend fun requestDisconnect(deviceId: String): AppResult<Unit>
}

interface UsageRepository {
    suspend fun daily(deviceId: String, date: LocalDate): AppResult<DailyUsage>
    suspend fun weekly(deviceId: String, endDate: LocalDate): AppResult<PeriodUsage>
    suspend fun monthly(deviceId: String, month: YearMonth): AppResult<PeriodUsage>
    suspend fun applications(deviceId: String, from: LocalDate, to: LocalDate, limit: Int = 50): AppResult<List<AppRanking>>
}

/** Child-side collection of on-device usage (UsageStatsManager) and upload. */
interface UsageCollector {
    fun hasUsageAccess(): Boolean
    /** Aggregates the last [days] days locally (including today) and stores them in the local cache. */
    suspend fun collect(days: Int = 7): List<DailyUsage>
    suspend fun syncNow(): AppResult<Int>
    fun observeLocalToday(): Flow<DailyUsage?>
}

interface ScreenShareRepository {
    suspend fun request(deviceId: String): AppResult<ScreenShareSession>
    suspend fun approve(sessionId: String): AppResult<ScreenShareSession>
    suspend fun reject(sessionId: String): AppResult<ScreenShareSession>
    suspend fun stop(sessionId: String, reason: StopReason = StopReason.USER_STOPPED): AppResult<ScreenShareSession>
    suspend fun statusForDevice(deviceId: String?): AppResult<ScreenShareSession?>
    suspend fun status(sessionId: String): AppResult<ScreenShareSession?>
}

interface SafetyRepository {
    suspend fun alerts(deviceId: String? = null, unacknowledgedOnly: Boolean = false): AppResult<List<SafetyAlert>>
    suspend fun createAlert(type: AlertType, message: String?, deviceId: String? = null, category: String? = null): AppResult<SafetyAlert>
    suspend fun acknowledge(alertId: String): AppResult<SafetyAlert>
    suspend fun settings(deviceId: String): AppResult<SafetySettings>
    suspend fun updateSettings(settings: SafetySettings): AppResult<SafetySettings>
}

interface RealtimeRepository {
    val events: SharedFlow<RealtimeEvent>
    val connectionState: StateFlow<ConnectionState>
    fun connect()
    fun disconnect()
    fun sendSignal(sessionId: String, payload: Map<String, Any?>): Boolean
}
