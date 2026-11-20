package com.familysafety.app.feature.child

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familysafety.app.core.designsystem.BarChart
import com.familysafety.app.core.designsystem.BarEntry
import com.familysafety.app.core.designsystem.CenteredContent
import com.familysafety.app.core.designsystem.ConfirmDialog
import com.familysafety.app.core.designsystem.EmptyState
import com.familysafety.app.core.designsystem.SectionHeader
import com.familysafety.app.core.designsystem.StatCard
import com.familysafety.app.core.designsystem.UsageBar
import com.familysafety.app.core.ui.LoadStateContent
import com.familysafety.app.domain.logic.Formatting
import com.familysafety.app.domain.logic.ShareState
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.DailyUsage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
    })
}

// ---------------- Pairing ----------------

@Composable
fun ChildPairingScreen(vm: ChildPairingViewModel = hiltViewModel()) {
    val code by vm.code.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = { BackBar("Connect to your family", null) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Ask your parent to open Family Safety and create a pairing code, then enter it here.", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = Formatting.displayPairingCode(code), onValueChange = vm::onCodeChange,
                    label = { Text("Pairing code") }, singleLine = true, isError = error != null,
                    supportingText = { Text(error ?: "8 characters, e.g. ABCD-2345") },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("pairing_code"),
                )
                WhatIsSharedCard()
                Button(onClick = { confirm = true }, enabled = !busy && code.length == 8, modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
                }
                TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
            }
        }
    }
    if (confirm) ConfirmDialog(
        "Connect this phone?", "Your parents will see how long you use each app and can ask to view your screen (you decide each time).",
        "Connect", onConfirm = { confirm = false; vm.submit() }, onDismiss = { confirm = false },
    )
}

@Composable
fun WhatIsSharedCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What your parents can see", style = MaterialTheme.typography.titleSmall)
            Text("• Which apps you used and for how long (after you turn on usage access)\n" +
                "• Check-ins, help requests and reports you send\n" +
                "• Your screen — only live, only when you approve, and you can stop any time")
            Text("What they can never see", style = MaterialTheme.typography.titleSmall)
            Text("• Your messages, calls, photos, passwords or what you type\n• Notification contents\n• Recordings — nothing is recorded")
        }
    }
}

// ---------------- Dashboard ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildDashboardScreen(
    onPermissions: () -> Unit, onConsent: () -> Unit, onStatus: () -> Unit, onSafety: () -> Unit,
    vm: ChildHomeViewModel = hiltViewModel(),
) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val share by vm.shareState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(share) { if (share is ShareState.Incoming) onConsent() }

    val user = (auth as? AuthState.SignedIn)?.user
    Scaffold(topBar = { TopAppBar(title = { Text("Family Safety") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Hi ${user?.displayName ?: ""}", style = MaterialTheme.typography.headlineSmall) }
                if (share is ShareState.Sharing || share is ShareState.Starting) item { SharingBanner(onConsent) }
                item {
                    Card(onClick = onStatus, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Connected to ${user?.familyName ?: "your family"}", style = MaterialTheme.typography.titleMedium)
                            StatusLine("Usage access", perms.usageAccess)
                            StatusLine("Notifications", perms.notifications)
                            Text("Tap to see exactly what's shared", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!perms.usageAccess || !perms.notifications) item {
                    Button(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("Review permissions") }
                }
                item {
                    StatCard("Your screen time today", today?.let { Formatting.duration(it.totalForegroundMs) } ?: "—", Modifier.fillMaxWidth(),
                        supporting = if (perms.usageAccess) "Calculated on this phone" else "Usage access is off")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.checkIn() }, modifier = Modifier.weight(1f)) { Text("I'm OK — check in") }
                        OutlinedButton(onClick = onSafety, modifier = Modifier.weight(1f)) { Text("Get help") }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning, contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("$label: ${if (ok) "on" else "off"}")
    }
}

@Composable
fun SharingBanner(onOpen: () -> Unit) {
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ScreenShare, null)
            Spacer(Modifier.width(12.dp))
            Text("Your screen is being shared. Tap to stop.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
}

// ---------------- Permissions ----------------

@Composable
fun PermissionsScreen(onBack: () -> Unit, vm: ChildHomeViewModel = hiltViewModel()) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onResume() }
    Scaffold(topBar = { BackBar("Permissions", onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionCard(
                    "Usage access", perms.usageAccess,
                    "Lets Family Safety read how long each app was in the foreground. App content is never visible. " +
                        "Turn it off at any time in Settings → Usage access; your parents will be told it was turned off.",
                    if (perms.usageAccess) "Manage" else "Turn on",
                ) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                PermissionCard(
                    "Notifications", perms.notifications,
                    "Needed to tell you about screen-sharing requests and check-in requests from your parents.",
                    if (perms.notifications) "Manage" else "Allow",
                ) {
                    if (!perms.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(appNotificationSettings(context))
                }
                PermissionCard(
                    "Screen capture", null,
                    "Not a standing permission. Android asks you every time a screen-sharing session starts, and a notification is visible while sharing.",
                    null,
                ) {}
                PermissionCard(
                    "Background activity", null,
                    "Activity summaries sync about every 15 minutes when Android allows it. Battery saver or restricted background " +
                        "usage can delay syncs; this is expected.",
                    "Battery settings",
                ) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
    }
}

private fun appNotificationSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

@Composable
private fun PermissionCard(title: String, granted: Boolean?, description: String, action: String?, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (granted) {
                    true -> Text("On", color = MaterialTheme.colorScheme.primary)
                    false -> Text("Off", color = MaterialTheme.colorScheme.error)
                    null -> Icon(Icons.Outlined.Info, null)
                }
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

// ---------------- Screen-sharing consent ----------------

@Composable
fun ScreenShareConsentScreen(onBack: () -> Unit, vm: ScreenShareConsentViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onCaptureResult(r.resultCode, r.data)
    }
    LaunchedEffect(state) {
        if (state is ShareState.AwaitingSystemPermission) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
    Scaffold(topBar = { BackBar("Screen sharing", onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ScreenShare, null, Modifier.size(56.dp))
                when (val s = state) {
                    is ShareState.Incoming -> {
                        Text("${s.session.requestedByDisplayName ?: "Your parent"} wants to see your screen", style = MaterialTheme.typography.headlineSmall)
                        Text("If you approve, Android will ask you once more. While sharing:\n" +
                            "• a notification shows that your screen is being shared\n" +
                            "• you can stop at any time\n• nothing is recorded and no sound is shared\n" +
                            "• it ends automatically after 30 minutes", style = MaterialTheme.typography.bodyLarge)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { vm.approve() }, modifier = Modifier.fillMaxWidth()) { Text("Approve") }
                        OutlinedButton(onClick = { vm.reject() }, modifier = Modifier.fillMaxWidth()) { Text("Decline") }
                    }
                    is ShareState.AwaitingSystemPermission -> Text("Waiting for Android's screen-capture permission…")
                    is ShareState.Starting -> { CircularProgressIndicator(); Text("Connecting…") }
                    is ShareState.Sharing -> {
                        Text("Your screen is being shared", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        Text("${s.session.requestedByDisplayName ?: "Your parent"} can see your screen right now.")
                        Button(onClick = { confirmStop = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
                    }
                    is ShareState.Ended -> {
                        Text(endedText(s.reason), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { vm.dismiss(); onBack() }) { Text("OK") }
                    }
                    ShareState.Idle -> {
                        EmptyState("No requests", "When a parent asks to see your screen, you'll decide here.", icon = Icons.Outlined.Cancel)
                    }
                }
            }
        }
    }
    if (confirmStop) ConfirmDialog("Stop sharing?", "Your parent will no longer see your screen.", "Stop",
        onConfirm = { confirmStop = false; vm.stop(); ScreenCaptureService.stop(context) }, onDismiss = { confirmStop = false })
}

private fun endedText(reason: String) = when (reason) {
    "REJECTED", "CHILD_REJECTED" -> "You declined the request."
    "PERMISSION_DENIED" -> "Screen capture was not allowed. Nothing was shared."
    "STOPPED_BY_CHILD" -> "You stopped sharing."
    "STOPPED_BY_PARENT" -> "Your parent stopped viewing."
    "REQUEST_TIMEOUT" -> "The request expired."
    "MAX_DURATION_REACHED" -> "Sharing ended after the time limit."
    "DEVICE_REVOKED" -> "This device was disconnected from your family."
    else -> "Screen sharing ended."
}

// ---------------- Monitoring status ----------------

@Composable
fun MonitoringStatusScreen(onBack: () -> Unit, vm: ChildHomeViewModel = hiltViewModel()) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val share by vm.shareState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    Scaffold(topBar = { BackBar("Monitoring status", onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Right now")
                ListItem(headlineContent = { Text("App usage summaries") },
                    supportingContent = { Text(if (perms.usageAccess) "Shared about every 15 minutes" else "Not shared — usage access is off") })
                ListItem(headlineContent = { Text("Last shared from this screen") },
                    supportingContent = { Text(lastSync?.let { java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(it) } ?: "Not yet this session") })
                ListItem(headlineContent = { Text("Screen sharing") },
                    supportingContent = { Text(if (share is ShareState.Sharing) "ACTIVE — your parent can see your screen" else "Not active") })
                ListItem(headlineContent = { Text("Live connection") },
                    supportingContent = { Text(when (connection) { ConnectionState.CONNECTED -> "Connected"; ConnectionState.CONNECTING -> "Connecting"; else -> "Offline (only while the app is open)" }) })
                Button(onClick = { vm.syncNow() }, enabled = !syncing && perms.usageAccess) { Text(if (syncing) "Sharing…" else "Share activity summary now") }
                HorizontalDivider()
                SectionHeader("Never collected")
                Text("Messages, calls, keystrokes, browsing content, photos, location and notification contents are never collected by Family Safety.")
            }
        }
    }
}

// ---------------- Activity summary ----------------

@Composable
fun ActivitySummaryScreen(vm: ActivitySummaryViewModel = hiltViewModel()) {
    val today by vm.today.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackBar("My activity", null) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!vm.hasAccess) item { EmptyState("Usage access is off", "Turn it on in Permissions to see your activity.") }
                item { TodaySection(today) }
                item { SectionHeader("Last 7 days (as your parents see it)") }
                item {
                    LoadStateContent(week, onRetry = { vm.load() }) { w ->
                        if (w.daysWithData == 0) Text("No synced data yet.")
                        else BarChart(w.days.map { BarEntry(it.date.dayOfWeek.name.take(3), it.totalForegroundMs, Formatting.duration(it.totalForegroundMs)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodaySection(today: DailyUsage?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Today", today?.let { Formatting.duration(it.totalForegroundMs) } ?: "—", Modifier.fillMaxWidth())
        val apps = today?.apps.orEmpty()
        if (apps.isNotEmpty()) {
            val max = apps.first().foregroundMs.coerceAtLeast(1)
            apps.take(8).forEach { UsageBar(it.appLabel ?: it.packageName, Formatting.duration(it.foregroundMs), it.foregroundMs.toFloat() / max) }
        }
    }
}

// ---------------- Safety ----------------

@Composable
fun ChildSafetyScreen(vm: ChildSafetyViewModel = hiltViewModel()) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    var confirmHelp by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    Scaffold(topBar = { BackBar("Safety", null) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Button(onClick = { vm.send(AlertType.CHECK_IN, "I'm OK") }, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text("Check in: I'm OK") } }
                item { OutlinedButton(onClick = { reportOpen = true }, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text("Report something that worried me") } }
                item {
                    Button(onClick = { confirmHelp = true }, enabled = !sending, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("I need help") }
                }
                item { Text("In an emergency, call your local emergency number.", style = MaterialTheme.typography.bodySmall) }
                item { SectionHeader("From and about you") }
                item {
                    LoadStateContent(alerts, onRetry = { vm.load() }) { list ->
                        if (list.isEmpty()) Text("Nothing yet.")
                        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { a ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(a.title, style = MaterialTheme.typography.titleSmall)
                                        a.message?.let { Text(it) }
                                        if (a.type == AlertType.CHECK_IN_REQUEST && a.acknowledgedAt == null) {
                                            Row {
                                                TextButton(onClick = { vm.send(AlertType.CHECK_IN, "I'm OK"); vm.acknowledge(a.id) }) { Text("Check in now") }
                                                TextButton(onClick = { vm.acknowledge(a.id) }) { Text("Dismiss") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmHelp) ConfirmDialog("Ask your parents for help?", "They'll get an urgent alert right away.", "Send",
        onConfirm = { confirmHelp = false; vm.send(AlertType.HELP_REQUEST, "I need help") }, onDismiss = { confirmHelp = false })
    if (reportOpen) ReportDialog(onDismiss = { reportOpen = false }) { cat, text -> reportOpen = false; vm.send(AlertType.INCIDENT_REPORT, text, cat) }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    val categories = listOf("Bullying", "Stranger contact", "Upsetting content", "Other")
    var category by rememberSaveable { mutableStateOf(categories.first()) }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a concern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Only what you write here is sent to your parents.")
                categories.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                    }
                }
                OutlinedTextField(text, { text = it.take(1000) }, label = { Text("What happened? (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSend(category, text) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Privacy ----------------

@Composable
fun PrivacySettingsScreen(onPermissions: () -> Unit, vm: PrivacyViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val auth by vm.authState.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    Scaffold(topBar = { BackBar("Privacy", null) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (auth as? AuthState.SignedIn)?.let { Text("Signed in as ${it.user.displayName} (${it.user.email})") }
                WhatIsSharedCard()
                SectionHeader("Settings your parents chose")
                LoadStateContent(settings, onRetry = { vm.load() }) { s ->
                    Column {
                        Text("Daily limit: ${s.dailyLimitMinutes?.let { Formatting.duration(it * 60_000L) } ?: "none"}")
                        Text("Bedtime: ${if (s.bedtimeStart != null) "${s.bedtimeStart}–${s.bedtimeEnd}" else "off"}")
                        Text("Screen-sharing requests: ${if (s.screenSharingEnabled) "allowed (you approve each one)" else "off"}")
                        Text("History kept for: ${s.usageRetentionDays} days")
                    }
                }
                SectionHeader("Your choices")
                OutlinedButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("Manage permissions") }
                OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) { Text("Ask to disconnect this phone") }
                Text("Disconnecting needs a parent's approval. You can always turn off usage access, which stops activity sharing (your parents are told).",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
        }
    }
    if (confirmDisconnect) ConfirmDialog("Ask to disconnect?", "Your parents will get a request to remove this phone from Family Safety.", "Send request",
        onConfirm = { confirmDisconnect = false; vm.requestDisconnect() }, onDismiss = { confirmDisconnect = false })
    if (confirmSignOut) ConfirmDialog("Sign out?", "Activity stops syncing until you sign in again. Your parents may notice.", "Sign out",
        onConfirm = { confirmSignOut = false; vm.signOut() }, onDismiss = { confirmSignOut = false })
}

