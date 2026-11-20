package com.familysafety.app.feature.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familysafety.app.core.designsystem.BarChart
import com.familysafety.app.core.designsystem.BarEntry
import com.familysafety.app.core.designsystem.CenteredContent
import com.familysafety.app.core.designsystem.ConfirmDialog
import com.familysafety.app.core.designsystem.EmptyState
import com.familysafety.app.core.designsystem.ErrorState
import com.familysafety.app.core.designsystem.LoadingState
import com.familysafety.app.core.designsystem.SectionHeader
import com.familysafety.app.core.designsystem.StatCard
import com.familysafety.app.core.designsystem.UsageBar
import com.familysafety.app.core.ui.LoadState
import com.familysafety.app.core.ui.LoadStateContent
import com.familysafety.app.domain.logic.Formatting
import com.familysafety.app.domain.model.AlertSeverity
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.DeviceSummary
import com.familysafety.app.domain.model.SafetyAlert
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

internal fun Instant?.relative(): String {
    if (this == null) return "never"
    val d = Duration.between(this, Instant.now())
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> timeFmt.format(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
    })
}

// ---------------- Dashboard ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(vm: ParentHomeViewModel, onOpenDevice: (String) -> Unit, onPair: () -> Unit, onAlerts: () -> Unit) {
    val devices by vm.deviceList.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val loaded by vm.loadedOnce.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val alerts by vm.unacknowledged.collectAsStateWithLifecycle()
    val name by vm.parentName.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Family Safety") }) }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding)) {
            CenteredContent {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(if (name.isNotBlank()) "Hello, $name" else "Hello", style = MaterialTheme.typography.headlineSmall) }
                    if (error != null && devices.isEmpty()) item { ErrorState(error!!, onRetry = { vm.refresh() }) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("Paired devices", devices.size.toString(), Modifier.weight(1f))
                            StatCard("Open alerts", alerts.size.toString(), Modifier.weight(1f).clickable(onClick = onAlerts))
                        }
                    }
                    if (alerts.isNotEmpty()) {
                        item { SectionHeader("Needs attention") }
                        items(alerts.take(3), key = { "a" + it.id }) { AlertRow(it, onAcknowledge = null) }
                    }
                    item { SectionHeader("Today") }
                    if (!loaded && devices.isEmpty()) item { LoadingState() }
                    else if (devices.isEmpty()) item {
                        EmptyState("No devices yet", "Pair your child's phone to see activity summaries and safety alerts.",
                            icon = Icons.Outlined.PhoneAndroid, actionLabel = "Pair a device", onAction = onPair)
                    } else items(devices, key = { it.id }) { DeviceCard(it) { onOpenDevice(it.id) } }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceSummary, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.ownerDisplayName, style = MaterialTheme.typography.titleMedium)
                Text(d.deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Last seen ${d.lastSeenAt.relative()}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(d.latestUsageForegroundMs?.let { Formatting.duration(it) } ?: "No data", style = MaterialTheme.typography.titleMedium)
                Text("screen time", style = MaterialTheme.typography.labelSmall)
                if (d.activeScreenShareSessionId != null) Text("Sharing", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- Device list ----------------

@Composable
fun DeviceListScreen(vm: ParentHomeViewModel, onOpenDevice: (String) -> Unit, onPair: () -> Unit) {
    val devices by vm.deviceList.collectAsStateWithLifecycle()
    val loaded by vm.loadedOnce.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onPair, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Pair device") })
    }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            when {
                !loaded && devices.isEmpty() -> LoadingState()
                error != null && devices.isEmpty() -> ErrorState(error!!, onRetry = { vm.refresh() })
                devices.isEmpty() -> EmptyState("No paired devices", "Create a pairing code and enter it on your child's phone.",
                    icon = Icons.Outlined.PhoneAndroid, actionLabel = "Pair a device", onAction = onPair)
                else -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SectionHeader("Child devices") }
                    items(devices, key = { it.id }) { DeviceCard(it) { onOpenDevice(it.id) } }
                }
            }
        }
    }
}

// ---------------- Pairing ----------------

@Composable
fun PairDeviceScreen(onDone: () -> Unit, vm: PairDeviceViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf("") }
    Scaffold(topBar = { BackTopBar("Pair a child device", onDone) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (val s = state) {
                    PairDeviceViewModel.UiState.Idle, is PairDeviceViewModel.UiState.Error, PairDeviceViewModel.UiState.Creating -> {
                        Text("1. Create a one-time code here.\n2. On your child's phone, sign in with a Child account and enter the code.\n" +
                            "Your child sees exactly what will be shared before they confirm.", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(label, { label = it.take(80) }, label = { Text("Child's name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (s is PairDeviceViewModel.UiState.Error) Text(s.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.create(label) }, enabled = s != PairDeviceViewModel.UiState.Creating, modifier = Modifier.fillMaxWidth()) {
                            if (s == PairDeviceViewModel.UiState.Creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Create pairing code")
                        }
                    }
                    is PairDeviceViewModel.UiState.ShowingCode -> {
                        var remaining by remember { mutableLongStateOf(Duration.between(Instant.now(), s.code.expiresAt).seconds) }
                        LaunchedEffect(s.code) {
                            while (remaining > 0) { delay(1000); remaining = Duration.between(Instant.now(), s.code.expiresAt).seconds }
                        }
                        Text("Enter this code on your child's device", style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Text(s.code.code, fontFamily = FontFamily.Monospace, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally)
                                    .semantics { contentDescription = "Pairing code " + s.code.code.toCharArray().joinToString(" ") })
                        }
                        if (remaining > 0) {
                            Text("Expires in %d:%02d. Waiting for your child to confirm…".format(remaining / 60, remaining % 60))
                        } else {
                            Text("This code has expired.", color = MaterialTheme.colorScheme.error)
                            Button(onClick = { vm.reset() }) { Text("Create a new code") }
                        }
                        Text("Codes work once and expire after 10 minutes. Never share a code with anyone outside your family.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    is PairDeviceViewModel.UiState.Paired -> {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(64.dp).align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.primary)
                        Text("${s.childName}'s device is paired", style = MaterialTheme.typography.headlineSmall)
                        Text("Activity appears after your child grants usage access and the first sync completes.")
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
            }
        }
    }
}

// ---------------- Device details ----------------

@Composable
fun DeviceDetailsScreen(
    onBack: () -> Unit, onAnalytics: (String) -> Unit, onApps: (String) -> Unit, onViewScreen: (String) -> Unit,
    onSettings: (String) -> Unit, onRevoked: () -> Unit, vm: DeviceDetailsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val revoked by vm.revoked.collectAsStateWithLifecycle()
    var confirmRevoke by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(revoked) { if (revoked) onRevoked() }

    Scaffold(topBar = { BackTopBar("Device", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LoadStateContent(state, onRetry = { vm.load() }) { ui ->
                val d = ui.device
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(d.ownerDisplayName, style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull(d.deviceName, d.osVersion).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Paired ${d.pairedAt.relative()} · last seen ${d.lastSeenAt.relative()}", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        val today = ui.today
                        val limit = today?.dailyLimitMinutes
                        StatCard("Screen time today", today?.let { Formatting.duration(it.totalForegroundMs) } ?: "No data yet", Modifier.fillMaxWidth(),
                            supporting = limit?.let { "Daily limit: ${Formatting.duration(it * 60_000L)}" })
                    }
                    ui.today?.apps?.takeIf { it.isNotEmpty() }?.let { apps ->
                        item { SectionHeader("Most used today") }
                        val max = apps.first().foregroundMs.coerceAtLeast(1)
                        items(apps.take(5), key = { it.packageName }) {
                            UsageBar(it.appLabel ?: it.packageName, Formatting.duration(it.foregroundMs), it.foregroundMs.toFloat() / max)
                        }
                    }
                    item { SectionHeader("Actions") }
                    item { ActionItem(Icons.Outlined.BarChart, "Screen-time reports", "Weekly and monthly trends") { onAnalytics(d.id) } }
                    item { ActionItem(Icons.Outlined.Apps, "Application activity", "Usage ranking by app") { onApps(d.id) } }
                    item { ActionItem(Icons.AutoMirrored.Outlined.ScreenShare, "View screen", "Your child must approve every session") { onViewScreen(d.id) } }
                    item { ActionItem(Icons.Outlined.WavingHand, "Ask to check in", "Sends a notification to your child") { vm.requestCheckIn() } }
                    item { ActionItem(Icons.Outlined.Shield, "Limits & safety settings", "Daily limit, bedtime, screen sharing") { onSettings(d.id) } }
                    item { HorizontalDivider() }
                    item {
                        OutlinedButton(onClick = { confirmRevoke = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.LinkOff, null); Spacer(Modifier.width(8.dp))
                            Text("Remove device", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (confirmRevoke) ConfirmDialog(
                    title = "Remove ${d.ownerDisplayName}'s device?",
                    message = "The device will be signed out, stop sharing activity, and any screen-sharing session ends immediately. " +
                        "To reconnect, you'll need to pair it again.",
                    confirmLabel = "Remove", destructive = true,
                    onConfirm = { confirmRevoke = false; vm.revoke() }, onDismiss = { confirmRevoke = false },
                )
            }
        }
    }
}

@Composable
private fun ActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) })
    }
}

// ---------------- Analytics ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageAnalyticsScreen(onBack: () -> Unit, vm: UsageAnalyticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val limit by vm.dailyLimit.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Screen-time reports", onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    UsageAnalyticsViewModel.Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(selected = period == p, onClick = { vm.select(p) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(if (p == UsageAnalyticsViewModel.Period.WEEK) "Last 7 days" else "This month")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                LoadStateContent(state, onRetry = { vm.load() }) { usage ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard("Total", Formatting.duration(usage.totalForegroundMs), Modifier.weight(1f))
                                StatCard("Daily average", Formatting.duration(usage.averageDailyMs), Modifier.weight(1f),
                                    supporting = "${usage.daysWithData} day(s) with data")
                            }
                        }
                        item {
                            if (usage.daysWithData == 0) EmptyState("No activity data", "Data appears after the child's device syncs.")
                            else BarChart(
                                usage.days.map { BarEntry(if (usage.days.size <= 7) it.date.dayOfWeek.name.take(3) else it.date.dayOfMonth.toString(),
                                    it.totalForegroundMs, Formatting.duration(it.totalForegroundMs)) },
                                referenceValue = limit?.let { it * 60_000L }, showLabels = usage.days.size <= 7,
                            )
                        }
                        if (usage.topApps.isNotEmpty()) {
                            item { SectionHeader("Top apps") }
                            val max = usage.topApps.first().totalForegroundMs.coerceAtLeast(1)
                            items(usage.topApps, key = { it.packageName }) {
                                UsageBar(it.appLabel ?: it.packageName, Formatting.duration(it.totalForegroundMs), it.totalForegroundMs.toFloat() / max)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- App activity ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActivityScreen(onBack: () -> Unit, vm: AppActivityViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Application activity", onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AppActivityViewModel.Range.entries.forEachIndexed { i, r ->
                        SegmentedButton(selected = range == r, onClick = { vm.select(r) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(r.label) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                LoadStateContent(state, onRetry = { vm.load() }) { apps ->
                    if (apps.isEmpty()) EmptyState("No app activity", "Nothing was recorded for this period.")
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(apps, key = { it.packageName }) { a ->
                            ListItem(
                                leadingContent = { Text("#${a.rank}", style = MaterialTheme.typography.titleMedium) },
                                headlineContent = { Text(a.appLabel ?: a.packageName) },
                                supportingContent = {
                                    Text("${a.launchCount} opens · ${a.daysUsed} day(s) · last used ${a.lastUsedAt.relative()}")
                                },
                                trailingContent = { Text(Formatting.duration(a.totalForegroundMs)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Alerts ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(vm: AlertsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Safety alerts") }) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LoadStateContent(state, onRetry = { vm.load() }) { alerts ->
                if (alerts.isEmpty()) EmptyState("No alerts", "Check-ins, help requests and limit alerts will appear here.", icon = Icons.Outlined.NotificationsNone)
                else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alerts, key = { it.id }) { AlertRow(it, onAcknowledge = { vm.acknowledge(it.id) }) }
                }
            }
        }
    }
}

@Composable
internal fun AlertRow(alert: SafetyAlert, onAcknowledge: (() -> Unit)?) {
    val urgent = alert.severity >= AlertSeverity.HIGH
    Card(
        Modifier.fillMaxWidth(),
        colors = if (urgent && alert.acknowledgedAt == null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(alert.title, style = MaterialTheme.typography.titleSmall)
            alert.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${alert.severity.name.lowercase().replaceFirstChar { it.uppercase() }} · ${alert.createdAt.relative()}",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                if (alert.acknowledgedAt == null && onAcknowledge != null) TextButton(onClick = onAcknowledge) { Text("Mark as seen") }
                else if (alert.acknowledgedAt != null) Text("Seen", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- Safety settings ----------------

@Composable
fun DeviceSafetySettingsScreen(onBack: () -> Unit, vm: SafetySettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    Scaffold(topBar = { BackTopBar("Limits & safety", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LoadStateContent(state, onRetry = { vm.load() }) { s ->
                Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Daily screen-time limit")
                    Text("You'll get an alert when the limit is exceeded. Apps are not blocked.", style = MaterialTheme.typography.bodySmall)
                    val options = listOf<Int?>(null, 60, 120, 180, 240)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, m ->
                            SegmentedButton(selected = s.dailyLimitMinutes == m, onClick = { vm.edit { it.copy(dailyLimitMinutes = m) } },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(m?.let { "${it / 60}h" } ?: "Off") }
                        }
                    }
                    ToggleRow("Alert when limit is exceeded", s.limitAlertsEnabled) { v -> vm.edit { it.copy(limitAlertsEnabled = v) } }
                    HorizontalDivider()
                    SectionHeader("Bedtime")
                    ToggleRow("Bedtime 21:00–07:00", s.bedtimeStart != null) { on ->
                        vm.edit { it.copy(bedtimeStart = if (on) LocalTime.of(21, 0) else null, bedtimeEnd = if (on) LocalTime.of(7, 0) else null,
                            bedtimeAlertsEnabled = on && it.bedtimeAlertsEnabled) }
                    }
                    ToggleRow("Alert on device use during bedtime", s.bedtimeAlertsEnabled, enabled = s.bedtimeStart != null) { v ->
                        vm.edit { it.copy(bedtimeAlertsEnabled = v) }
                    }
                    HorizontalDivider()
                    SectionHeader("Screen sharing")
                    ToggleRow("Allow screen-sharing requests", s.screenSharingEnabled) { v -> vm.edit { it.copy(screenSharingEnabled = v) } }
                    Text("Even when allowed, your child must approve each session and can stop it at any time.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    SectionHeader("Data retention")
                    val retention = listOf(30, 90, 180, 365)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        retention.forEachIndexed { i, d ->
                            SegmentedButton(selected = s.usageRetentionDays == d, onClick = { vm.edit { it.copy(usageRetentionDays = d) } },
                                shape = SegmentedButtonDefaults.itemShape(i, retention.size)) { Text("${d}d") }
                        }
                    }
                    Text("Usage history older than this is deleted automatically.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

// ---------------- Settings ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(vm: ParentSettingsViewModel = hiltViewModel()) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (auth as? AuthState.SignedIn)?.let {
                    SectionHeader("Account")
                    ListItem(headlineContent = { Text(it.user.displayName) }, supportingContent = { Text(it.user.email) })
                    it.user.familyName?.let { f -> ListItem(headlineContent = { Text("Family") }, supportingContent = { Text(f) }) }
                }
                SectionHeader("How Family Safety works")
                Text("• Activity: app names and time in foreground, synced from your child's phone after they grant usage access.\n" +
                    "• Screen sharing: live only, never recorded, and only after your child approves each request.\n" +
                    "• Messages, calls, keystrokes and notification content are never collected.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            }
        }
        if (confirm) ConfirmDialog("Sign out?", "You will stop receiving alerts on this phone until you sign in again.", "Sign out",
            onConfirm = { confirm = false; vm.signOut() }, onDismiss = { confirm = false })
    }
}

