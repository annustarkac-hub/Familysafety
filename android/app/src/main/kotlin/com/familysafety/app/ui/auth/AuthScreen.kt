package com.familysafety.app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familysafety.app.core.designsystem.CenteredContent
import com.familysafety.app.core.designsystem.PasswordField
import com.familysafety.app.domain.model.Role

@Composable
fun AuthScreen(vm: AuthViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    var showServer by rememberSaveable { mutableStateOf(false) }
    AuthContent(
        form, vm::update, vm::toggleMode, vm::submit,
        serverHost = server?.host, serverConfigured = server?.configured ?: true, onOpenServer = { showServer = true },
    )
    if (showServer) ServerSettingsSheet(onDismiss = { showServer = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthContent(
    form: AuthForm,
    onChange: ((AuthForm) -> AuthForm) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    serverHost: String? = null,
    serverConfigured: Boolean = true,
    onOpenServer: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val backdrop = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface))
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().background(backdrop).padding(padding)) {
            CenteredContent {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onOpenServer, modifier = Modifier.testTag("open_server")) {
                            Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (serverConfigured && serverHost != null) serverHost else "Server", maxLines = 1)
                        }
                    }
                    BrandHeader()
                    Spacer(Modifier.height(20.dp))

                    AnimatedVisibility(!serverConfigured, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        SetupServerCard(onOpenServer)
                    }

                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                        PrimaryTabRow(selectedTabIndex = if (form.registering) 1 else 0, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Tab(selected = !form.registering, onClick = { if (form.registering) onToggleMode() }, text = { Text("Sign in") })
                            Tab(selected = form.registering, onClick = { if (!form.registering) onToggleMode() }, text = { Text("Create account") })
                        }
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AnimatedContent(form.registering, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "mode") { registering ->
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (registering) {
                                        RoleSelector(form.role) { r -> onChange { it.copy(role = r) } }
                                        OutlinedTextField(
                                            form.displayName, { v -> onChange { it.copy(displayName = v.take(80)) } },
                                            label = { Text("Your name") }, singleLine = true,
                                            leadingIcon = { Icon(Icons.Outlined.Person, null) },
                                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                                            modifier = Modifier.fillMaxWidth().testTag("name"),
                                        )
                                    } else {
                                        Text("Welcome back", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                            OutlinedTextField(
                                form.email, { v -> onChange { it.copy(email = v.trim().take(254)) } },
                                label = { Text("Email") }, singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Email, null) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                                modifier = Modifier.fillMaxWidth().testTag("email"),
                            )
                            PasswordField(
                                form.password, { v -> onChange { it.copy(password = v.take(128)) } }, "Password",
                                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                                imeAction = ImeAction.Done, onImeAction = { focus.clearFocus(); onSubmit() },
                                modifier = Modifier.testTag("password"),
                            )
                            if (form.registering) PasswordRequirement(form.password.length >= 12)

                            AnimatedVisibility(form.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                ErrorBanner(form.error.orEmpty(), form.errorIsNetwork, onOpenServer)
                            }

                            Button(
                                onClick = { focus.clearFocus(); onSubmit() }, enabled = !form.busy,
                                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("submit"),
                            ) {
                                AnimatedContent(form.busy, label = "busy") { busy ->
                                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    else Text(if (form.registering) "Create account" else "Sign in")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Family Safety never reads messages, calls, notifications or what you type. Screen sharing is live-only and always needs the child's approval.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp, modifier = Modifier.size(84.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(46.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Family Safety", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text("Safety built on trust and consent", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoleSelector(role: Role, onSelect: (Role) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("I am a…", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Role.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = role == r, onClick = { onSelect(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, Role.entries.size), modifier = Modifier.testTag("role_${r.name}"),
                    icon = { SegmentedButtonDefaults.Icon(role == r) { Icon(if (r == Role.PARENT) Icons.Outlined.FamilyRestroom else Icons.Outlined.ChildCare, null, Modifier.size(18.dp)) } },
                ) { Text(if (r == Role.PARENT) "Parent" else "Child") }
            }
        }
        AnimatedContent(role, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "roleHint") { r ->
            Text(
                if (r == Role.PARENT) "Parents pair their children's devices and receive safety alerts."
                else "Children connect their own phone using a code from a parent. You'll always see what is shared.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasswordRequirement(met: Boolean) {
    val color by animateColorAsState(if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "pw")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(if (met) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (met) "At least 12 characters ✓" else "At least 12 characters", style = MaterialTheme.typography.bodySmall, color = color,
            modifier = Modifier.testTag("pw_requirement"))
    }
}

@Composable
private fun ErrorBanner(message: String, network: Boolean, onOpenServer: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(if (network) Icons.Outlined.WifiOff else Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("auth_error"))
            }
            if (network) TextButton(onClick = onOpenServer, modifier = Modifier.align(Alignment.End)) { Text("Server settings") }
        }
    }
}

@Composable
private fun SetupServerCard(onOpenServer: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("setup_server_card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connect to your server first", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("This copy of the app doesn't have a Family Safety server set. Enter the address of the server your family uses.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Button(onClick = onOpenServer) { Text("Set up server") }
        }
    }
}
