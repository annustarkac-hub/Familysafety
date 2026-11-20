package com.familysafety.app.feature.child

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object ChildRoutes {
    const val HOME = "child/home"
    const val ACTIVITY = "child/activity"
    const val SAFETY = "child/safety"
    const val PRIVACY = "child/privacy"
    const val PERMISSIONS = "child/permissions"
    const val CONSENT = "child/screen-share"
    const val STATUS = "child/status"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(ChildRoutes.HOME, "Home", Icons.Outlined.Home),
    Tab(ChildRoutes.ACTIVITY, "Activity", Icons.Outlined.BarChart),
    Tab(ChildRoutes.SAFETY, "Safety", Icons.Outlined.HealthAndSafety),
    Tab(ChildRoutes.PRIVACY, "Privacy", Icons.Outlined.PrivacyTip),
)

/** @param startDestination optional deep link target, e.g. from a notification. */
@Composable
fun ChildRoot(startDestination: String? = null) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(bottomBar = {
        if (tabs.any { it.route == route }) NavigationBar {
            tabs.forEach { tab ->
                NavigationBarItem(selected = route == tab.route, onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true; restoreState = true
                    }
                }, icon = { Icon(tab.icon, null) }, label = { Text(tab.label) })
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = ChildRoutes.HOME, modifier = Modifier.padding(padding)) {
            composable(ChildRoutes.HOME) {
                ChildDashboardScreen(
                    onPermissions = { nav.navigate(ChildRoutes.PERMISSIONS) },
                    onConsent = { nav.navigate(ChildRoutes.CONSENT) { launchSingleTop = true } },
                    onStatus = { nav.navigate(ChildRoutes.STATUS) },
                    onSafety = { nav.navigate(ChildRoutes.SAFETY) },
                )
            }
            composable(ChildRoutes.ACTIVITY) { ActivitySummaryScreen() }
            composable(ChildRoutes.SAFETY) { ChildSafetyScreen() }
            composable(ChildRoutes.PRIVACY) { PrivacySettingsScreen(onPermissions = { nav.navigate(ChildRoutes.PERMISSIONS) }) }
            composable(ChildRoutes.PERMISSIONS) { PermissionsScreen(onBack = { nav.popBackStack() }) }
            composable(ChildRoutes.CONSENT) { ScreenShareConsentScreen(onBack = { nav.popBackStack() }) }
            composable(ChildRoutes.STATUS) { MonitoringStatusScreen(onBack = { nav.popBackStack() }) }
        }
        if (startDestination != null) {
            androidx.compose.runtime.LaunchedEffect(startDestination) {
                if (startDestination != ChildRoutes.HOME) nav.navigate(startDestination) { launchSingleTop = true }
            }
        }
    }
}
