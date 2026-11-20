package com.familysafety.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familysafety.app.core.designsystem.CenteredContent
import com.familysafety.app.core.designsystem.PasswordField
import com.familysafety.app.domain.model.Role

@Composable
fun AuthScreen(vm: AuthViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    AuthContent(form, vm::update, vm::toggleMode, vm::submit)
}

@Composable
fun AuthContent(form: AuthForm, onChange: ((AuthForm) -> AuthForm) -> Unit, onToggleMode: () -> Unit, onSubmit: () -> Unit) {
    Scaffold { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Family Safety", style = MaterialTheme.typography.headlineMedium)
                Text(if (form.registering) "Create your account" else "Sign in", style = MaterialTheme.typography.titleMedium)

                if (form.registering) {
                    Text("I am a…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Role.entries.forEachIndexed { i, role ->
                            SegmentedButton(selected = form.role == role, onClick = { onChange { it.copy(role = role) } },
                                shape = SegmentedButtonDefaults.itemShape(i, Role.entries.size), modifier = Modifier.testTag("role_${role.name}")) {
                                Text(if (role == Role.PARENT) "Parent" else "Child")
                            }
                        }
                    }
                    Text(
                        if (form.role == Role.PARENT) "Parents pair their children's devices and receive safety alerts."
                        else "Children connect their own phone using a code from a parent. You'll always see what is shared.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(form.displayName, { v -> onChange { it.copy(displayName = v.take(80)) } }, label = { Text("Your name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("name"))
                }
                OutlinedTextField(form.email, { v -> onChange { it.copy(email = v.trim().take(254)) } }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("email"))
                PasswordField(form.password, { v -> onChange { it.copy(password = v.take(128)) } }, "Password",
                    supportingText = if (form.registering) "At least 12 characters" else null, modifier = Modifier.testTag("password"))

                form.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("auth_error"))
                }
                Button(onClick = onSubmit, enabled = !form.busy, modifier = Modifier.fillMaxWidth().testTag("submit")) {
                    if (form.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (form.registering) "Create account" else "Sign in")
                }
                TextButton(onClick = onToggleMode) {
                    Text(if (form.registering) "I already have an account" else "Create a new account")
                }
            }
        }
    }
}
