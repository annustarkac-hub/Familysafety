package com.familysafety.app.data.network

import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

private val errorJson = Json { ignoreUnknownKeys = true }

/** Executes an API call and maps transport/HTTP failures to [AppError] using the backend's stable error codes. */
suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    AppResult.Failure(httpError(e.code(), e.response()?.errorBody()?.string()))
} catch (e: IOException) {
    AppResult.Failure(AppError.Network(NetworkErrors.describe(e, null)))
} catch (e: SerializationException) {
    AppResult.Failure(AppError.Unexpected("The server returned an unexpected response."))
} catch (e: IllegalArgumentException) {
    AppResult.Failure(AppError.Unexpected())
}

/** For endpoints returning no body (204/202). */
suspend fun safeUnitCall(block: suspend () -> Response<Unit>): AppResult<Unit> = when (val r = safeApiCall(block)) {
    is AppResult.Failure -> r
    is AppResult.Success -> if (r.value.isSuccessful) AppResult.Success(Unit)
        else AppResult.Failure(httpError(r.value.code(), r.value.errorBody()?.string()))
}

fun httpError(status: Int, body: String?): AppError {
    val parsed = body?.let { runCatching { errorJson.decodeFromString(ApiErrorDto.serializer(), it) }.getOrNull() }
    return when {
        status == 401 && parsed?.code in setOf(null, "UNKNOWN", "SESSION_REVOKED", "INVALID_REFRESH_TOKEN", "REFRESH_TOKEN_REUSED", "REFRESH_TOKEN_EXPIRED") ->
            AppError.Unauthorized()
        parsed != null && parsed.message.isNotBlank() -> AppError.Api(status, parsed.code, friendlyMessage(parsed.code, parsed.message))
        status == 429 -> AppError.Api(status, "RATE_LIMITED", "Too many attempts. Please wait a minute and try again.")
        status >= 500 -> AppError.Api(status, "SERVER_ERROR", "The server is having trouble. Please try again later.")
        else -> AppError.Api(status, "HTTP_$status", "Request failed ($status).")
    }
}

private fun friendlyMessage(code: String, fallback: String): String = when (code) {
    "INVALID_CREDENTIALS" -> "Email or password is incorrect."
    "EMAIL_ALREADY_REGISTERED" -> "An account with this email already exists."
    "ACCOUNT_LOCKED" -> "Too many failed attempts. Try again in 15 minutes."
    "INVALID_PAIRING_CODE" -> "That pairing code isn't valid. Check it and try again."
    "PAIRING_CODE_EXPIRED" -> "That pairing code has expired. Ask your parent for a new one."
    "RATE_LIMITED" -> "Too many attempts. Please wait a minute and try again."
    "VALIDATION_FAILED" -> "Some fields are invalid. Please check your input."
    else -> fallback
}
