package com.familysafety.app.feature.parent

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object ParentRoutes {
    const val DASHBOARD = "parent/dashboard"
    const val DEVICES = "parent/devices"
    const val ALERTS = "parent/alerts"
    const val SETTINGS = "parent/settings"
    const val PAIR = "parent/pair"
    const val DEVICE = "parent/device/{deviceId}"
    const val ANALYTICS = "parent/device/{deviceId}/analytics"
    const val APPS = "parent/device/{deviceId}/apps"
    const val VIEWER = "parent/device/{deviceId}/screen"
    const val DEVICE_SETTINGS = "parent/device/{deviceId}/settings"
    fun device(id: String) = "parent/device/$id"
    fun analytics(id: String) = "parent/device/$id/analytics"
    fun apps(id: String) = "parent/device/$id/apps"
    fun viewer(id: String) = "parent/device/$id/screen"
    fun deviceSettings(id: String) = "parent/device/$id/settings"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(ParentRoutes.DASHBOARD, "Home", Icons.Outlined.Dashboard),
    Tab(ParentRoutes.DEVICES, "Devices", Icons.Outlined.Devices),
    Tab(ParentRoutes.ALERTS, "Alerts", Icons.Outlined.NotificationsActive),
    Tab(ParentRoutes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun ParentRoot(startOnAlerts: Boolean = false) {
    val nav = rememberNavController()
    val home: ParentHomeViewModel = hiltViewModel()
    val unack by home.unacknowledged.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == ParentRoutes.ALERTS && unack.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text(unack.size.coerceAtMost(99).toString()) } }) { Icon(tab.icon, null) }
                                } else Icon(tab.icon, null)
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = if (startOnAlerts) ParentRoutes.ALERTS else ParentRoutes.DASHBOARD, modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 } },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { it / 10 } },
        ) {
            composable(ParentRoutes.DASHBOARD) {
                ParentDashboardScreen(home, onOpenDevice = { nav.navigate(ParentRoutes.device(it)) },
                    onPair = { nav.navigate(ParentRoutes.PAIR) }, onAlerts = { nav.navigate(ParentRoutes.ALERTS) })
            }
            composable(ParentRoutes.DEVICES) {
                DeviceListScreen(home, onOpenDevice = { nav.navigate(ParentRoutes.device(it)) }, onPair = { nav.navigate(ParentRoutes.PAIR) })
            }
            composable(ParentRoutes.ALERTS) { AlertsScreen() }
            composable(ParentRoutes.SETTINGS) { ParentSettingsScreen() }
            composable(ParentRoutes.PAIR) { PairDeviceScreen(onDone = { home.refresh(); nav.popBackStack() }) }
            composable(ParentRoutes.DEVICE) {
                DeviceDetailsScreen(
                    onBack = { nav.popBackStack() },
                    onAnalytics = { nav.navigate(ParentRoutes.analytics(it)) },
                    onApps = { nav.navigate(ParentRoutes.apps(it)) },
                    onViewScreen = { nav.navigate(ParentRoutes.viewer(it)) },
                    onSettings = { nav.navigate(ParentRoutes.deviceSettings(it)) },
                    onRevoked = { home.refresh(); nav.popBackStack() },
                )
            }
            composable(ParentRoutes.ANALYTICS) { UsageAnalyticsScreen(onBack = { nav.popBackStack() }) }
            composable(ParentRoutes.APPS) { AppActivityScreen(onBack = { nav.popBackStack() }) }
            composable(ParentRoutes.VIEWER) { ScreenViewerScreen(onBack = { nav.popBackStack() }) }
            composable(ParentRoutes.DEVICE_SETTINGS) { DeviceSafetySettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
