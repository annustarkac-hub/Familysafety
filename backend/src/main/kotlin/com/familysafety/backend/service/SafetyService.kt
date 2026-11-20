package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.BadRequestException
import com.familysafety.backend.common.ConflictException
import com.familysafety.backend.common.ForbiddenException
import com.familysafety.backend.common.NotFoundException
import com.familysafety.backend.model.AlertAudience
import com.familysafety.backend.model.AlertSeverity
import com.familysafety.backend.model.AlertType
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.SafetyAlert
import com.familysafety.backend.model.SafetySettings
import com.familysafety.backend.realtime.ConnectionRegistry
import com.familysafety.backend.realtime.EventTypes
import com.familysafety.backend.realtime.RealtimeEvent
import com.familysafety.backend.realtime.afterCommit
import com.familysafety.backend.repository.SafetyAlertRepository
import com.familysafety.backend.repository.SafetySettingsRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.web.CreateAlertRequest
import com.familysafety.backend.web.SafetyAlertDto
import com.familysafety.backend.web.SafetyAlertListResponse
import com.familysafety.backend.web.SafetySettingsDto
import com.familysafety.backend.web.UpdateSafetySettingsRequest
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class SafetyService(
    private val alerts: SafetyAlertRepository,
    private val settings: SafetySettingsRepository,
    private val users: UserRepository,
    private val access: AccessPolicy,
    private val registry: ConnectionRegistry,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun create(p: AuthPrincipal, req: CreateAlertRequest): SafetyAlertDto {
        val now = clock.instant()
        val alert = when (p.role) {
            Role.CHILD -> {
                if (!req.type.childCreatable) throw ForbiddenException("ALERT_TYPE_NOT_ALLOWED", "This alert type cannot be created by a child account")
                val pairing = access.childActivePairing(p) ?: throw ConflictException("DEVICE_NOT_PAIRED", "This device is not paired with a family")
                val name = users.findById(p.userId).map { it.displayName }.orElse("Your child")
                val dedupe = if (req.type == AlertType.USAGE_ACCESS_REVOKED) "usage-access:${pairing.deviceId}:${LocalDate.now(clock)}" else null
                if (dedupe != null && alerts.existsByDedupeKey(dedupe)) {
                    throw ConflictException("ALERT_ALREADY_RAISED", "This alert was already raised today")
                }
                SafetyAlert(familyId = pairing.familyId, deviceId = pairing.deviceId, createdByUserId = p.userId,
                    alertType = req.type, severity = req.type.defaultSeverity, audience = AlertAudience.PARENTS,
                    title = childTitle(req.type, name), message = composeMessage(req), dedupeKey = dedupe)
            }
            Role.PARENT -> {
                if (!req.type.parentCreatable) throw ForbiddenException("ALERT_TYPE_NOT_ALLOWED", "This alert type cannot be created by a parent account")
                val deviceId = req.deviceId ?: throw BadRequestException("DEVICE_ID_REQUIRED", "deviceId is required")
                val pairing = access.requireParentAccessToDevice(p, deviceId)
                val name = users.findById(p.userId).map { it.displayName }.orElse("Your parent")
                SafetyAlert(familyId = pairing.familyId, deviceId = deviceId, createdByUserId = p.userId,
                    alertType = req.type, severity = req.type.defaultSeverity, audience = AlertAudience.CHILD,
                    title = "$name asked you to check in", message = req.message?.trim())
            }
        }
        alert.createdAt = now
        val saved = alerts.save(alert)
        audit.record(AuditAction.SAFETY_ALERT_CREATE, p.userId, p.deviceId, "safety_alert", saved.id, details = mapOf("type" to saved.alertType.name))
        publish(saved)
        return saved.toDto()
    }

    /** Server-originated alert (limit exceeded, device revoked, ...). Returns null if deduplicated. */
    @Transactional
    fun raiseSystemAlert(
        familyId: UUID, deviceId: UUID?, type: AlertType, title: String, message: String?,
        dedupeKey: String? = null, audience: AlertAudience = AlertAudience.PARENTS, severity: AlertSeverity = type.defaultSeverity,
    ): SafetyAlert? {
        if (dedupeKey != null && alerts.existsByDedupeKey(dedupeKey)) return null
        val saved = alerts.save(SafetyAlert(familyId = familyId, deviceId = deviceId, createdByUserId = null, alertType = type,
            severity = severity, audience = audience, title = title, message = message, dedupeKey = dedupeKey))
        publish(saved)
        return saved
    }

    @Transactional(readOnly = true)
    fun list(p: AuthPrincipal, deviceId: UUID?, unacknowledgedOnly: Boolean, limit: Int, before: Instant?): SafetyAlertListResponse {
        val page = PageRequest.of(0, limit.coerceIn(1, 100))
        val cursor = before ?: Instant.parse("9999-12-31T00:00:00Z")
        val result = when (p.role) {
            Role.PARENT -> {
                val familyId = access.parentFamilyId(p)
                if (deviceId != null) {
                    access.requireParentAccessToDevice(p, deviceId)
                    alerts.searchDevice(familyId, deviceId, listOf(AlertAudience.PARENTS), unacknowledgedOnly, cursor, page)
                } else alerts.searchFamily(familyId, listOf(AlertAudience.PARENTS), unacknowledgedOnly, cursor, page)
            }
            Role.CHILD -> {
                // Transparency: a child sees every alert concerning their own device, including those sent to parents.
                val pairing = access.childActivePairing(p) ?: return SafetyAlertListResponse(emptyList(), null)
                alerts.searchDevice(pairing.familyId, pairing.deviceId, AlertAudience.entries, unacknowledgedOnly, cursor, page)
            }
        }
        return SafetyAlertListResponse(result.map { it.toDto() }, if (result.size == page.pageSize) result.last().createdAt else null)
    }

    @Transactional
    fun acknowledge(p: AuthPrincipal, alertId: UUID): SafetyAlertDto {
        val alert = alerts.findById(alertId).orElseThrow { NotFoundException("ALERT_NOT_FOUND", "Alert not found") }
        val allowed = when (p.role) {
            Role.PARENT -> alert.audience == AlertAudience.PARENTS && alert.familyId == access.parentFamilyId(p)
            Role.CHILD -> alert.audience == AlertAudience.CHILD && alert.deviceId != null && alert.deviceId == p.deviceId
        }
        if (!allowed) throw NotFoundException("ALERT_NOT_FOUND", "Alert not found")
        if (alert.acknowledgedAt == null) {
            alert.acknowledgedAt = clock.instant()
            alert.acknowledgedByUserId = p.userId
            alerts.save(alert)
        }
        return alert.toDto()
    }

    @Transactional
    fun getSettings(p: AuthPrincipal, deviceId: UUID): SafetySettingsDto {
        access.requireReadAccessToDevice(p, deviceId)
        val s = settings.findByDeviceId(deviceId) ?: throw NotFoundException("SETTINGS_NOT_FOUND", "No settings for this device (is it paired?)")
        return s.toDto()
    }

    @Transactional
    fun updateSettings(p: AuthPrincipal, req: UpdateSafetySettingsRequest): SafetySettingsDto {
        val pairing = access.requireParentAccessToDevice(p, req.deviceId)
        if ((req.bedtimeStart == null) != (req.bedtimeEnd == null)) {
            throw BadRequestException("INVALID_BEDTIME", "bedtimeStart and bedtimeEnd must both be set or both be null")
        }
        if (req.bedtimeStart != null && req.bedtimeStart == req.bedtimeEnd) {
            throw BadRequestException("INVALID_BEDTIME", "bedtimeStart and bedtimeEnd must differ")
        }
        val s = settings.findByDeviceId(req.deviceId) ?: SafetySettings(familyId = pairing.familyId, deviceId = req.deviceId)
        s.familyId = pairing.familyId
        s.dailyLimitMinutes = req.dailyLimitMinutes
        s.bedtimeStart = req.bedtimeStart
        s.bedtimeEnd = req.bedtimeEnd
        s.limitAlertsEnabled = req.limitAlertsEnabled
        s.bedtimeAlertsEnabled = req.bedtimeAlertsEnabled
        s.screenSharingEnabled = req.screenSharingEnabled
        s.usageRetentionDays = req.usageRetentionDays
        val saved = settings.saveAndFlush(s)
        audit.record(AuditAction.SAFETY_SETTINGS_UPDATE, p.userId, p.deviceId, "device", req.deviceId,
            details = mapOf("dailyLimitMinutes" to req.dailyLimitMinutes, "screenSharingEnabled" to req.screenSharingEnabled))
        val dto = saved.toDto()
        afterCommit { registry.sendToDevice(req.deviceId, RealtimeEvent(EventTypes.SETTINGS_UPDATED, dto)) }
        return dto
    }

    private fun publish(alert: SafetyAlert) {
        val dto = alert.toDto()
        afterCommit {
            val event = RealtimeEvent(EventTypes.SAFETY_ALERT, dto)
            when (alert.audience) {
                AlertAudience.PARENTS -> registry.sendToFamilyParents(alert.familyId, event)
                AlertAudience.CHILD -> alert.deviceId?.let { registry.sendToDevice(it, event) }
            }
        }
    }

    private fun childTitle(type: AlertType, name: String) = when (type) {
        AlertType.CHECK_IN -> "$name checked in"
        AlertType.INCIDENT_REPORT -> "$name reported a safety concern"
        AlertType.HELP_REQUEST -> "$name is asking for help"
        AlertType.USAGE_ACCESS_REVOKED -> "Usage access was turned off on $name's device"
        else -> type.name
    }

    private fun composeMessage(req: CreateAlertRequest): String? {
        val msg = req.message?.trim()?.takeIf { it.isNotEmpty() }
        val cat = req.category?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cat != null && msg != null -> "[$cat] $msg".take(1000)
            cat != null -> "[$cat]"
            else -> msg
        }
    }
}

fun SafetyAlert.toDto() = SafetyAlertDto(id, familyId, deviceId, createdByUserId, alertType, severity, audience, title, message, createdAt, acknowledgedAt)

fun SafetySettings.toDto() = SafetySettingsDto(deviceId, dailyLimitMinutes, bedtimeStart, bedtimeEnd, limitAlertsEnabled,
    bedtimeAlertsEnabled, screenSharingEnabled, usageRetentionDays, updatedAt)
