package de.marcusleg.alertmanagerreceiver.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.marcusleg.alertmanagerreceiver.AppContainer
import de.marcusleg.alertmanagerreceiver.ui.alerts.AlertsScreen
import de.marcusleg.alertmanagerreceiver.ui.alerts.AlertsViewModel
import de.marcusleg.alertmanagerreceiver.ui.detail.AlertDetailScreen
import de.marcusleg.alertmanagerreceiver.ui.detail.AlertDetailViewModel
import de.marcusleg.alertmanagerreceiver.ui.receiver.ReceiverScreen
import de.marcusleg.alertmanagerreceiver.ui.receiver.ReceiverViewModel
import de.marcusleg.alertmanagerreceiver.ui.settings.SettingsScreen
import de.marcusleg.alertmanagerreceiver.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable data object AlertsRoute
@Serializable data object ReceiverRoute
@Serializable data object SettingsRoute
@Serializable data class AlertDetailRoute(val fingerprint: String)

sealed interface PendingNavigation {
    data class Alert(val fingerprint: String) : PendingNavigation
    data object Alerts : PendingNavigation
    data object Receiver : PendingNavigation
}

private data class TopLevel(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TOP_LEVEL = listOf(
    TopLevel(AlertsRoute, "Alerts", Icons.Outlined.NotificationsActive, Icons.Filled.NotificationsActive),
    TopLevel(ReceiverRoute, "Receiver", Icons.Outlined.Sensors, Icons.Filled.Sensors),
    TopLevel(SettingsRoute, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(
    container: AppContainer,
    pendingNavigation: PendingNavigation?,
    onNavigationHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val firing by container.alertRepository.observeFiringCount().collectAsStateWithLifecycle(0)

    RequestNotificationPermission()

    LaunchedEffect(pendingNavigation) {
        when (pendingNavigation) {
            is PendingNavigation.Alert -> navController.navigate(AlertDetailRoute(pendingNavigation.fingerprint)) {
                popUpTo(AlertsRoute)
                launchSingleTop = true
            }
            PendingNavigation.Alerts -> navController.navigate(AlertsRoute) { popUpTo(AlertsRoute) { inclusive = true } }
            PendingNavigation.Receiver -> navController.navigate(ReceiverRoute) { popUpTo(AlertsRoute) }
            null -> return@LaunchedEffect
        }
        onNavigationHandled()
    }

    val showBottomBar = TOP_LEVEL.any { item -> destination?.hasRoute(item.route::class) == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL.forEach { item ->
                        val selected = destination?.hasRoute(item.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                BadgedBox(badge = {
                                    if (item.route == AlertsRoute && firing > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.error) { Text(firing.toString()) }
                                    }
                                }) { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { outerPadding ->
        // Inner screens have their own Scaffold/TopAppBar; only consume the bottom bar's inset here.
        val bottom = PaddingValues(bottom = outerPadding.calculateBottomPadding())
        Box(Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
            NavHost(navController, startDestination = AlertsRoute) {
                composable<AlertsRoute> {
                    AlertsScreen(
                        viewModel = viewModel { AlertsViewModel(container) },
                        onOpenAlert = { navController.navigate(AlertDetailRoute(it)) },
                        onOpenReceiver = {
                            navController.navigate(ReceiverRoute) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable<ReceiverRoute> { ReceiverScreen(viewModel { ReceiverViewModel(container) }) }
                composable<SettingsRoute> { SettingsScreen(viewModel { SettingsViewModel(container) }) }
                composable<AlertDetailRoute> { entry ->
                    val route = entry.toRoute<AlertDetailRoute>()
                    AlertDetailScreen(
                        viewModel = viewModel(key = route.fingerprint) { AlertDetailViewModel(container, route.fingerprint) },
                        onBack = { if (!navController.popBackStack()) navController.navigate(AlertsRoute) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
