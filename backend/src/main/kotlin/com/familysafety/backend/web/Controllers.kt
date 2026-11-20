package com.familysafety.backend.web

import com.familysafety.backend.common.ClientIp
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.service.AuthService
import com.familysafety.backend.service.ClientContext
import com.familysafety.backend.service.DeviceService
import com.familysafety.backend.service.PairingService
import com.familysafety.backend.service.SafetyService
import com.familysafety.backend.service.ScreenSharingService
import com.familysafety.backend.service.UsageService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private fun HttpServletRequest.ctx() = ClientContext(ClientIp.of(this), getHeader("User-Agent"))

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val auth: AuthService) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody req: RegisterRequest, http: HttpServletRequest) = auth.register(req, http.ctx())

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest, http: HttpServletRequest) = auth.login(req, http.ctx())

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest, http: HttpServletRequest) = auth.refresh(req.refreshToken, http.ctx())

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal p: AuthPrincipal) = auth.logout(p)

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal p: AuthPrincipal) = auth.me(p)
}

@RestController
@RequestMapping("/api/v1/pairing")
class PairingController(private val pairing: PairingService) {
    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody(required = false) req: CreatePairingRequest?) =
        pairing.create(p, req?.childLabel)

    @PostMapping("/accept")
    fun accept(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: AcceptPairingRequest) = pairing.accept(p, req.code)
}

@RestController
@RequestMapping("/api/v1/devices")
class DeviceController(private val devices: DeviceService) {
    @GetMapping
    fun list(@AuthenticationPrincipal p: AuthPrincipal) = devices.list(p)

    @GetMapping("/{deviceId}")
    fun get(@AuthenticationPrincipal p: AuthPrincipal, @PathVariable deviceId: UUID) = devices.get(p, deviceId)

    @DeleteMapping("/{deviceId}")
    fun revoke(
        @AuthenticationPrincipal p: AuthPrincipal, @PathVariable deviceId: UUID,
        @Valid @RequestBody(required = false) req: RevokeDeviceRequest?,
    ): ResponseEntity<Void> {
        devices.revoke(p, deviceId, req?.reason)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{deviceId}/disconnect-request")
    fun disconnectRequest(@AuthenticationPrincipal p: AuthPrincipal, @PathVariable deviceId: UUID): ResponseEntity<Void> {
        devices.requestDisconnect(p, deviceId)
        return ResponseEntity.accepted().build()
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/usage")
class UsageController(private val usage: UsageService) {
    @PostMapping("/sync")
    fun sync(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: UsageSyncRequest) = usage.sync(p, req)

    @GetMapping("/daily")
    fun daily(
        @AuthenticationPrincipal p: AuthPrincipal, @RequestParam deviceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = usage.daily(p, deviceId, date)

    @GetMapping("/weekly")
    fun weekly(
        @AuthenticationPrincipal p: AuthPrincipal, @RequestParam deviceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ) = usage.weekly(p, deviceId, endDate)

    @GetMapping("/monthly")
    fun monthly(@AuthenticationPrincipal p: AuthPrincipal, @RequestParam deviceId: UUID, @RequestParam month: String) =
        usage.monthly(p, deviceId, runCatching { YearMonth.parse(month) }.getOrElse {
            throw com.familysafety.backend.common.BadRequestException("INVALID_PARAMETER", "month must be formatted yyyy-MM")
        })

    @GetMapping("/applications")
    fun applications(
        @AuthenticationPrincipal p: AuthPrincipal, @RequestParam deviceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ) = usage.applications(p, deviceId, from, to, limit)
}

@RestController
@RequestMapping("/api/v1/screen-sharing")
class ScreenSharingController(private val screen: ScreenSharingService) {
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: ScreenShareRequest) = screen.request(p, req.deviceId)

    @PostMapping("/approve")
    fun approve(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: ScreenShareSessionRef) = screen.approve(p, req.sessionId)

    @PostMapping("/reject")
    fun reject(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: ScreenShareSessionRef) = screen.reject(p, req.sessionId)

    @PostMapping("/stop")
    fun stop(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: ScreenShareStopRequest) = screen.stop(p, req.sessionId, req.reason)

    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal p: AuthPrincipal,
        @RequestParam(required = false) sessionId: UUID?, @RequestParam(required = false) deviceId: UUID?,
    ) = screen.status(p, sessionId, deviceId)
}

@Validated
@RestController
@RequestMapping("/api/v1/safety")
class SafetyController(private val safety: SafetyService) {
    @GetMapping("/alerts")
    fun alerts(
        @AuthenticationPrincipal p: AuthPrincipal,
        @RequestParam(required = false) deviceId: UUID?,
        @RequestParam(defaultValue = "false") unacknowledgedOnly: Boolean,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        @RequestParam(required = false) before: Instant?,
    ) = safety.list(p, deviceId, unacknowledgedOnly, limit, before)

    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: CreateAlertRequest) = safety.create(p, req)

    @PostMapping("/alerts/{alertId}/acknowledge")
    fun acknowledge(@AuthenticationPrincipal p: AuthPrincipal, @PathVariable alertId: UUID) = safety.acknowledge(p, alertId)

    @GetMapping("/settings")
    fun settings(@AuthenticationPrincipal p: AuthPrincipal, @RequestParam deviceId: UUID) = safety.getSettings(p, deviceId)

    @PutMapping("/settings")
    fun updateSettings(@AuthenticationPrincipal p: AuthPrincipal, @Valid @RequestBody req: UpdateSafetySettingsRequest) =
        safety.updateSettings(p, req)
}
