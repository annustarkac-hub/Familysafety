package com.familysafety.backend.audit

import com.familysafety.backend.model.AuditLog
import com.familysafety.backend.model.AuditOutcome
import com.familysafety.backend.repository.AuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

object AuditAction {
    const val REGISTER = "auth.register"
    const val LOGIN = "auth.login"
    const val LOGIN_FAILED = "auth.login_failed"
    const val LOGOUT = "auth.logout"
    const val REFRESH = "auth.refresh"
    const val REFRESH_REUSE = "auth.refresh_token_reuse"
    const val PAIRING_CREATE = "pairing.create"
    const val PAIRING_ACCEPT = "pairing.accept"
    const val PAIRING_ACCEPT_FAILED = "pairing.accept_failed"
    const val DEVICE_REVOKE = "device.revoke"
    const val DEVICE_DISCONNECT_REQUEST = "device.disconnect_request"
    const val USAGE_SYNC = "usage.sync"
    const val SCREEN_SHARE_REQUEST = "screen_share.request"
    const val SCREEN_SHARE_APPROVE = "screen_share.approve"
    const val SCREEN_SHARE_REJECT = "screen_share.reject"
    const val SCREEN_SHARE_START = "screen_share.start"
    const val SCREEN_SHARE_END = "screen_share.end"
    const val SAFETY_ALERT_CREATE = "safety.alert_create"
    const val SAFETY_SETTINGS_UPDATE = "safety.settings_update"
    const val ACCESS_DENIED = "authz.denied"
}

/**
 * Writes audit events in their own transaction so that failures (e.g. denied access) are recorded even when
 * the business transaction rolls back. Details must never contain secrets, tokens, codes or passwords.
 */
@Service
class AuditService(private val repo: AuditLogRepository) {
    private val log = LoggerFactory.getLogger("audit")

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        action: String,
        actorUserId: UUID?,
        actorDeviceId: UUID? = null,
        targetType: String? = null,
        targetId: Any? = null,
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
        details: Map<String, Any?>? = null,
    ) {
        val ip = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr
        repo.save(AuditLog(actorUserId = actorUserId, actorDeviceId = actorDeviceId, action = action, targetType = targetType,
            targetId = targetId?.toString(), outcome = outcome, ipAddress = ip, details = details))
        log.info("audit action={} outcome={} actor={} target={}:{}", action, outcome, actorUserId, targetType, targetId)
    }
}
