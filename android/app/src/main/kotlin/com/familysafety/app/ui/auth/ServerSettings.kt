package com.familysafety.app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.familysafety.app.data.server.HealthResult
import com.familysafety.app.data.server.ServerConfigStore
import com.familysafety.app.data.server.ServerHealthChecker
import com.familysafety.app.data.server.ServerUrlValidation
import com.familysafety.app.data.server.ServerUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionCheck {
    data object Idle : ConnectionCheck
    data object Checking : ConnectionCheck
    data object Ok : ConnectionCheck
    data class Failed(val message: String) : ConnectionCheck
}

data class ServerSettingsState(
    val input: String,
    val check: ConnectionCheck = ConnectionCheck.Idle,
    val validationError: String? = null,
    val saved: Boolean = false,
    val allowCleartext: Boolean = false,
)

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val server: ServerConfigStore,
    private val checker: ServerHealthChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(initial())
    val state = _state.asStateFlow()

    private fun initial() = ServerSettingsState(
        input = if (server.isPlaceholder) "" else server.current.toString(),
        allowCleartext = server.allowCleartext,
    )

    fun reload() { _state.value = initial() }

    fun onInput(value: String) = _state.update { it.copy(input = value.take(300), check = ConnectionCheck.Idle, validationError = null, saved = false) }

    private fun validated() = when (val v = ServerUrls.validate(_state.value.input, server.allowCleartext)) {
        is ServerUrlValidation.Valid -> v.url
        is ServerUrlValidation.Invalid -> { _state.update { it.copy(validationError = v.reason) }; null }
    }

    fun test() {
        val url = validated() ?: return
        _state.update { it.copy(check = ConnectionCheck.Checking) }
        viewModelScope.launch {
            val result = checker.check(url)
            _state.update {
                it.copy(check = when (result) {
                    HealthResult.Reachable -> ConnectionCheck.Ok
                    is HealthResult.Unreachable -> ConnectionCheck.Failed(result.message)
                })
            }
        }
    }

    /** Saves after a successful connection test, so a typo can't silently break sign-in. */
    fun save() {
        val url = validated() ?: return
        _state.update { it.copy(check = ConnectionCheck.Checking) }
        viewModelScope.launch {
            when (val result = checker.check(url)) {
                HealthResult.Reachable -> {
                    server.save(url)
                    _state.update { it.copy(input = url.toString(), check = ConnectionCheck.Ok, saved = true) }
                }
                is HealthResult.Unreachable -> _state.update { it.copy(check = ConnectionCheck.Failed(result.message)) }
            }
        }
    }

    fun resetToDefault() = viewModelScope.launch { server.reset(); _state.value = initial() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsSheet(onDismiss: () -> Unit, vm: ServerSettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { vm.reload() }
    LaunchedEffect(state.saved) { if (state.saved) onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ServerSettingsContent(state, vm::onInput, vm::test, vm::save, { vm.resetToDefault() })
    }
}

@Composable
fun ServerSettingsContent(
    state: ServerSettingsState,
    onInput: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Server", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Family Safety connects to a server run by your family or organisation. Parent and child phones must use the same server.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val checking = state.check == ConnectionCheck.Checking
        OutlinedTextField(
            value = state.input, onValueChange = onInput, singleLine = true, enabled = !checking,
            label = { Text("Server address") },
            placeholder = { Text("https://api.example.com") },
            isError = state.validationError != null,
            supportingText = {
                Text(state.validationError ?: if (state.allowCleartext) "https:// recommended; http:// allowed in this debug build" else "Must start with https://")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onSave() }),
            modifier = Modifier.fillMaxWidth().testTag("server_url"),
        )
        AnimatedContent(state.check, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "check") { check ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                when (check) {
                    ConnectionCheck.Idle -> Unit
                    ConnectionCheck.Checking -> {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Contacting server… (a sleeping free server can take up to a minute)")
                    }
                    ConnectionCheck.Ok -> {
                        Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                        Text("Connected to a Family Safety server", modifier = Modifier.testTag("server_ok"))
                    }
                    is ConnectionCheck.Failed -> {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                        Text(check.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("server_error"))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onTest, enabled = !checking, modifier = Modifier.weight(1f)) { Text("Test connection") }
            Button(onClick = onSave, enabled = !checking && state.input.isNotBlank(), modifier = Modifier.weight(1f).testTag("server_save")) { Text("Save") }
        }
        TextButton(onClick = onReset, enabled = !checking) { Text("Use default") }
        Text(
            "No server yet? Run it yourself in a few minutes with Docker — see docs/SERVER_QUICKSTART.md in the project.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
