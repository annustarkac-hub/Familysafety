package com.familysafety.backend.common

import org.springframework.http.HttpStatus

/** Domain error carrying a stable machine-readable [code] that clients can switch on. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class NotFoundException(code: String = "NOT_FOUND", message: String = "Resource not found") :
    ApiException(HttpStatus.NOT_FOUND, code, message)

class ForbiddenException(code: String = "FORBIDDEN", message: String = "You are not allowed to perform this action") :
    ApiException(HttpStatus.FORBIDDEN, code, message)

class ConflictException(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)

class BadRequestException(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)

class UnauthorizedException(code: String, message: String) : ApiException(HttpStatus.UNAUTHORIZED, code, message)

class RateLimitedException(val retryAfterSeconds: Long) :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Retry after $retryAfterSeconds seconds.")
