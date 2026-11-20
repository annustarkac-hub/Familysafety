package com.familysafety.app.domain.model

/** Error categories surfaced to the UI. [code] is the backend's stable error code when available. */
sealed class AppError(open val message: String) {
    data class Network(override val message: String = "Unable to reach the server. Check your connection.") : AppError(message)
    data class Unauthorized(override val message: String = "Your session has expired. Please sign in again.") : AppError(message)
    data class Api(val status: Int, val code: String, override val message: String) : AppError(message)
    data class Validation(override val message: String) : AppError(message)
    data class Unexpected(override val message: String = "Something went wrong. Please try again.") : AppError(message)
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> = also { if (it is AppResult.Success) block(it.value) }
inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> = also { if (it is AppResult.Failure) block(it.error) }
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
