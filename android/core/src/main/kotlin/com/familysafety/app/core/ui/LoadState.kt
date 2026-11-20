package com.familysafety.app.core.ui

import androidx.compose.runtime.Composable
import com.familysafety.app.core.designsystem.ErrorState
import com.familysafety.app.core.designsystem.LoadingState
import com.familysafety.app.domain.model.AppResult

/** Generic screen state used by ViewModels. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Error(val message: String) : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
}

fun <T> AppResult<T>.toLoadState(): LoadState<T> = when (this) {
    is AppResult.Success -> LoadState.Ready(value)
    is AppResult.Failure -> LoadState.Error(error.message)
}

/** Renders loading / error states and delegates the ready state to [content]. */
@Composable
fun <T> LoadStateContent(state: LoadState<T>, onRetry: (() -> Unit)?, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> LoadingState()
        is LoadState.Error -> ErrorState(state.message, onRetry)
        is LoadState.Ready -> content(state.data)
    }
}
