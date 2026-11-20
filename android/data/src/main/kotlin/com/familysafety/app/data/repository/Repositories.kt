package com.familysafety.app.data.repository

import com.familysafety.app.data.db.DeviceDao
import com.familysafety.app.data.db.LocalUsageDao
import com.familysafety.app.data.db.NotifiedAlertDao
import com.familysafety.app.data.network.AcceptPairingBody
import com.familysafety.app.data.network.ApiService
import com.familysafety.app.data.network.CreateAlertBody
import com.familysafety.app.data.network.CreatePairingBody
import com.familysafety.app.data.network.DeviceInfoBody
import com.familysafety.app.data.network.LoginBody
import com.familysafety.app.data.network.RegisterBody
import com.familysafety.app.data.network.RevokeBody
import com.familysafety.app.data.network.ScreenShareRequestBody
import com.familysafety.app.data.network.SessionRefBody
import com.familysafety.app.data.network.StopBody
import com.familysafety.app.data.network.TokenResponseDto
import com.familysafety.app.data.network.safeApiCall
import com.familysafety.app.data.network.safeUnitCall
import com.familysafety.app.data.network.toDomain
import com.familysafety.app.data.network.toDto
import com.familysafety.app.data.network.toEntity
import com.familysafety.app.data.network.toInstantOrNull
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.data.session.StoredSession
import com.familysafety.app.data.session.merge
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppRanking
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.DailyUsage
import com.familysafety.app.domain.model.DeviceSummary
import com.familysafety.app.domain.model.PairingCode
import com.familysafety.app.domain.model.PairingResult
import com.familysafety.app.domain.model.PeriodUsage
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.SafetySettings
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.StopReason
import com.familysafety.app.domain.model.UserProfile
import com.familysafety.app.domain.model.map
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.domain.repository.DeviceRepository
import com.familysafety.app.domain.repository.PairingRepository
import com.familysafety.app.domain.repository.SafetyRepository
import com.familysafety.app.domain.repository.ScreenShareRepository
import com.familysafety.app.domain.repository.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Supplies non-identifying device metadata for registration/login. */
fun interface DeviceInfoProvider {
    suspend fun deviceInfo(): DeviceInfoBody
}

/** Hook invoked after sign-in/out so background work and realtime connections follow the session. */
interface SessionLifecycleListener {
    fun onSignedIn(role: Role)
    fun onSignedOut()
}

class AuthRepositoryImpl(
    private val api: ApiService,
    private val store: SessionStore,
    private val deviceInfo: DeviceInfoProvider,
    private val localUsage: LocalUsageDao,
    private val devices: DeviceDao,
    private val notified: NotifiedAlertDao,
    private val listeners: Set<@JvmSuppressWildcards SessionLifecycleListener>,
    scope: CoroutineScope,
) : AuthRepository {

    override val authState: StateFlow<AuthState> = combine(store.loaded, store.session) { loaded, s ->
        when {
            !loaded -> AuthState.Loading
            s == null -> AuthState.SignedOut
            else -> AuthState.SignedIn(
                UserProfile(s.userId, s.email, s.displayName, Role.valueOf(s.role), s.familyId, s.familyName), s.deviceId, s.paired,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    init {
        // The session can also end outside this class (e.g. TokenRefresher after the server rejects a refresh
        // token). Make sure background work and realtime connections always stop with it. Listeners are idempotent.
        scope.launch {
            var wasSignedIn = false
            store.session.collect { s ->
                if (wasSignedIn && s == null) {
                    listeners.forEach { it.onSignedOut() }
                    runCatching { localUsage.clearApps(); localUsage.clearSummaries(); devices.clear(); notified.clear() }
                }
                wasSignedIn = s != null
            }
        }
    }

    override suspend fun register(email: String, password: String, displayName: String, role: Role): AppResult<Unit> =
        safeApiCall { api.register(RegisterBody(email.trim(), password, displayName.trim(), role.name, deviceInfo.deviceInfo())) }
            .map { onTokens(it, paired = false) }

    override suspend fun login(email: String, password: String): AppResult<Unit> =
        safeApiCall { api.login(LoginBody(email.trim(), password, deviceInfo.deviceInfo())) }.map { tokens ->
            onTokens(tokens, paired = false)
            // Pairing status is not part of the token response; fetch it.
            refreshProfile()
            Unit
        }

    private suspend fun onTokens(t: TokenResponseDto, paired: Boolean) {
        store.save((null as StoredSession?).merge(t, paired))
        listeners.forEach { it.onSignedIn(Role.valueOf(t.user.role)) }
    }

    override suspend fun refreshProfile(): AppResult<Unit> = safeApiCall { api.me() }.map { me ->
        store.update { it.copy(paired = me.paired, displayName = me.user.displayName, familyId = me.user.familyId, familyName = me.user.familyName) }
    }

    override suspend fun logout() {
        safeUnitCall { api.logout() } // best effort: local sign-out proceeds even if offline
        clearLocalSession()
    }

    override suspend fun clearLocalSession() {
        listeners.forEach { it.onSignedOut() }
        store.clear()
        localUsage.clearApps()
        localUsage.clearSummaries()
        devices.clear()
        notified.clear()
    }
}

class PairingRepositoryImpl(private val api: ApiService, private val store: SessionStore) : PairingRepository {
    override suspend fun createCode(childLabel: String?): AppResult<PairingCode> =
        safeApiCall { api.createPairing(CreatePairingBody(childLabel?.takeIf { it.isNotBlank() })) }
            .map { PairingCode(it.code, it.expiresAt.toInstantOrNull() ?: Instant.now(), it.childLabel) }

    override suspend fun acceptCode(code: String): AppResult<PairingResult> =
        safeApiCall { api.acceptPairing(AcceptPairingBody(code)) }.map {
            store.update { s -> s.copy(paired = true, familyId = it.familyId, familyName = it.familyName) }
            PairingResult(it.familyName, it.parents, it.pairedAt.toInstantOrNull() ?: Instant.now())
        }
}

class DeviceRepositoryImpl(private val api: ApiService, private val dao: DeviceDao) : DeviceRepository {
    override fun observeDevices(): Flow<List<DeviceSummary>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(): AppResult<List<DeviceSummary>> = safeApiCall { api.devices() }.map { list ->
        dao.replaceAll(list.map { it.toEntity() })
        list.map { it.toDomain() }
    }

    override suspend fun getDevice(deviceId: String): AppResult<DeviceSummary> = safeApiCall { api.device(deviceId) }.map { it.toDomain() }

    override suspend fun revoke(deviceId: String, reason: String?): AppResult<Unit> {
        val r = safeUnitCall { api.revokeDevice(deviceId, RevokeBody(reason)) }
        if (r is AppResult.Success) dao.delete(deviceId)
        return r
    }

    override suspend fun requestDisconnect(deviceId: String): AppResult<Unit> = safeUnitCall { api.disconnectRequest(deviceId) }
}

class UsageRepositoryImpl(private val api: ApiService) : UsageRepository {
    override suspend fun daily(deviceId: String, date: LocalDate): AppResult<DailyUsage> =
        safeApiCall { api.dailyUsage(deviceId, date.toString()) }.map { it.toDomain() }

    override suspend fun weekly(deviceId: String, endDate: LocalDate): AppResult<PeriodUsage> =
        safeApiCall { api.weeklyUsage(deviceId, endDate.toString()) }.map { it.toDomain() }

    override suspend fun monthly(deviceId: String, month: YearMonth): AppResult<PeriodUsage> =
        safeApiCall { api.monthlyUsage(deviceId, month.toString()) }.map { it.toDomain() }

    override suspend fun applications(deviceId: String, from: LocalDate, to: LocalDate, limit: Int): AppResult<List<AppRanking>> =
        safeApiCall { api.applications(deviceId, from.toString(), to.toString(), limit) }.map { r -> r.applications.map { it.toDomain() } }
}

class ScreenShareRepositoryImpl(private val api: ApiService) : ScreenShareRepository {
    override suspend fun request(deviceId: String) = safeApiCall { api.requestScreenShare(ScreenShareRequestBody(deviceId)) }.map { it.toDomain() }
    override suspend fun approve(sessionId: String) = safeApiCall { api.approveScreenShare(SessionRefBody(sessionId)) }.map { it.toDomain() }
    override suspend fun reject(sessionId: String) = safeApiCall { api.rejectScreenShare(SessionRefBody(sessionId)) }.map { it.toDomain() }
    override suspend fun stop(sessionId: String, reason: StopReason) =
        safeApiCall { api.stopScreenShare(StopBody(sessionId, reason.name)) }.map { it.toDomain() }
    override suspend fun statusForDevice(deviceId: String?): AppResult<ScreenShareSession?> =
        safeApiCall { api.screenShareStatus(deviceId = deviceId) }.map { it.active?.toDomain() }
    override suspend fun status(sessionId: String): AppResult<ScreenShareSession?> =
        safeApiCall { api.screenShareStatus(sessionId = sessionId) }.map { s -> (s.active ?: s.recent.firstOrNull())?.toDomain() }
}

class SafetyRepositoryImpl(private val api: ApiService) : SafetyRepository {
    override suspend fun alerts(deviceId: String?, unacknowledgedOnly: Boolean): AppResult<List<SafetyAlert>> =
        safeApiCall { api.alerts(deviceId, unacknowledgedOnly) }.map { r -> r.alerts.map { it.toDomain() } }

    override suspend fun createAlert(type: AlertType, message: String?, deviceId: String?, category: String?): AppResult<SafetyAlert> {
        if (type == AlertType.UNKNOWN) return AppResult.Failure(AppError.Validation("Unsupported alert type"))
        return safeApiCall { api.createAlert(CreateAlertBody(type.name, message?.takeIf { it.isNotBlank() }, deviceId, category)) }.map { it.toDomain() }
    }

    override suspend fun acknowledge(alertId: String) = safeApiCall { api.acknowledgeAlert(alertId) }.map { it.toDomain() }
    override suspend fun settings(deviceId: String) = safeApiCall { api.safetySettings(deviceId) }.map { it.toDomain() }
    override suspend fun updateSettings(settings: SafetySettings) =
        safeApiCall { api.updateSafetySettings(settings.toDto()) }.map { it.toDomain() }
}
