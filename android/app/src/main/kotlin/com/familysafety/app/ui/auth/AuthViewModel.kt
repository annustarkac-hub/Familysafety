package com.familysafety.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familysafety.app.domain.logic.Formatting
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthForm(
    val registering: Boolean = false,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val role: Role = Role.PARENT,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _form = MutableStateFlow(AuthForm())
    val form = _form.asStateFlow()

    fun update(transform: (AuthForm) -> AuthForm) = _form.update { transform(it).copy(error = null) }
    fun toggleMode() = _form.update { it.copy(registering = !it.registering, error = null) }

    /** Client-side validation mirrors the server's rules for fast feedback; the server re-validates everything. */
    fun validate(f: AuthForm): String? = when {
        !Formatting.isValidEmail(f.email) -> "Enter a valid email address."
        f.registering && !Formatting.isValidPassword(f.password) -> "Password must be 12–128 characters."
        !f.registering && f.password.isEmpty() -> "Enter your password."
        f.registering && f.displayName.isBlank() -> "Enter your name."
        else -> null
    }

    fun submit() {
        val f = _form.value
        if (f.busy) return
        validate(f)?.let { msg -> _form.update { it.copy(error = msg) }; return }
        _form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = if (f.registering) auth.register(f.email, f.password, f.displayName, f.role) else auth.login(f.email, f.password)
            _form.update {
                when (r) {
                    is AppResult.Success -> it.copy(busy = false, password = "")
                    is AppResult.Failure -> it.copy(busy = false, error = r.error.message)
                }
            }
        }
    }
}
