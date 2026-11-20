package com.familysafety.backend.common

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ErrorResponse(
    val code: String,
    val message: String,
    val status: Int,
    val path: String?,
    val requestId: String?,
    val timestamp: Instant = Instant.now(),
    val fieldErrors: List<FieldError> = emptyList(),
) {
    data class FieldError(val field: String, val message: String)
}

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun build(status: HttpStatus, code: String, message: String, req: HttpServletRequest, fields: List<ErrorResponse.FieldError> = emptyList()) =
        ResponseEntity.status(status).body(ErrorResponse(code, message, status.value(), req.requestURI, MDC.get("requestId"), fieldErrors = fields))

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val resp = build(ex.status, ex.code, ex.message, req)
        return if (ex is RateLimitedException) {
            ResponseEntity.status(ex.status).header("Retry-After", ex.retryAfterSeconds.toString()).body(resp.body)
        } else resp
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val fields = ex.bindingResult.fieldErrors.map { ErrorResponse.FieldError(it.field, it.defaultMessage ?: "invalid") } +
            ex.bindingResult.globalErrors.map { ErrorResponse.FieldError(it.objectName, it.defaultMessage ?: "invalid") }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req, fields)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(ex: ConstraintViolationException, req: HttpServletRequest) =
        build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req,
            ex.constraintViolations.map { ErrorResponse.FieldError(it.propertyPath.toString(), it.message) })

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, req: HttpServletRequest) =
        build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed", req)

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun handleParams(ex: Exception, req: HttpServletRequest) =
        build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", ex.message ?: "Invalid request parameter", req)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleDenied(ex: AccessDeniedException, req: HttpServletRequest) =
        build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this action", req)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(ex: HttpRequestMethodNotSupportedException, req: HttpServletRequest) =
        build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported", req)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException, req: HttpServletRequest) =
        build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", req)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("Data integrity violation on {}: {}", req.requestURI, ex.mostSpecificCause.message)
        return build(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with the current state of the resource", req)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("Unhandled error on {}", req.requestURI, ex)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", req)
    }
}
