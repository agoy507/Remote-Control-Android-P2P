package com.agoy507.mdremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agoy507.mdremote.model.ControllerSessionState
import com.agoy507.mdremote.ui.RemoteViewModel
import com.agoy507.mdremote.ui.components.IncomingApprovalDialog
import com.agoy507.mdremote.ui.navigation.AppRoute
import com.agoy507.mdremote.ui.screens.BerandaScreen
import com.agoy507.mdremote.ui.screens.KanvasRemoteScreen
import com.agoy507.mdremote.ui.screens.KendaliScreen
import com.agoy507.mdremote.ui.screens.ServerConfigScreen
import com.agoy507.mdremote.ui.theme.KendaliP2PTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KendaliP2PTheme {
                KendaliP2PApp()
            }
        }
    }
}

private data class TopLevelNavItem(
    val route: AppRoute,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

@Composable
fun KendaliP2PApp(
    viewModel: RemoteViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val signalingStatus by viewModel.signalingStatus.collectAsStateWithLifecycle()
    val hostStatus by viewModel.hostStatus.collectAsStateWithLifecycle()
    val incomingRequest by viewModel.incomingRequest.collectAsStateWithLifecycle()
    val controllerState by viewModel.controllerState.collectAsStateWithLifecycle()
    val recentDevices by viewModel.recentDevices.collectAsStateWithLifecycle()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val requireVisualApproval by viewModel.requireVisualApproval.collectAsStateWithLifecycle()
    val allowTouchControl by viewModel.allowTouchControl.collectAsStateWithLifecycle()
    val qualityPreset by viewModel.qualityPreset.collectAsStateWithLifecycle()
    val serverHealthResult by viewModel.serverHealthResult.collectAsStateWithLifecycle()
    val isCheckingServer by viewModel.isCheckingServer.collectAsStateWithLifecycle()

    val isKanvasRoute = currentDest?.hasRoute<AppRoute.KanvasRemote>() == true

    // Navigate automatically to fullscreen KanvasRemote when Controller session connects
    LaunchedEffect(controllerState) {
        if (controllerState is ControllerSessionState.Connected && !isKanvasRoute) {
            navController.navigate(AppRoute.KanvasRemote) {
                launchSingleTop = true
            }
        } else if (controllerState !is ControllerSessionState.Connected && isKanvasRoute) {
            navController.popBackStack()
        }
    }

    val navItems = remember {
        listOf(
            TopLevelNavItem(
                route = AppRoute.Beranda,
                labelRes = R.string.nav_beranda,
                selectedIcon = Icons.Filled.ScreenShare,
                unselectedIcon = Icons.Outlined.ScreenShare,
                testTag = "nav_tab_beranda",
            ),
            TopLevelNavItem(
                route = AppRoute.Kendali,
                labelRes = R.string.nav_kendali,
                selectedIcon = Icons.Filled.CastConnected,
                unselectedIcon = Icons.Outlined.CastConnected,
                testTag = "nav_tab_kendali",
            ),
            TopLevelNavItem(
                route = AppRoute.ServerConfig,
                labelRes = R.string.nav_server,
                selectedIcon = Icons.Filled.Dns,
                unselectedIcon = Icons.Outlined.Dns,
                testTag = "nav_tab_server",
            ),
        )
    }

    fun navigateTopLevel(route: AppRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp && !isKanvasRoute

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (isKanvasRoute) WindowInsets(0) else WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail && !isKanvasRoute) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                    ) {
                        navItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes),
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        navItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes),
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag),
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = AppRoute.Beranda,
                    modifier = Modifier.weight(1f),
                ) {
                    composable<AppRoute.Beranda> {
                        BerandaScreen(
                            identity = identity,
                            signalingStatus = signalingStatus,
                            hostStatus = hostStatus,
                            accessibilityEnabled = accessibilityEnabled,
                            requireVisualApproval = requireVisualApproval,
                            allowTouchControl = allowTouchControl,
                            snackbarHostState = snackbarHostState,
                            onRefreshPermissions = viewModel::refreshAccessibilityStatus,
                            onNavigateToServer = { navigateTopLevel(AppRoute.ServerConfig) },
                            onRegeneratePassword = viewModel::regenerateSessionPassword,
                            onUpdateCustomPassword = viewModel::updateSessionPassword,
                            onToggleVisualApproval = viewModel::setRequireVisualApproval,
                            onToggleAllowTouchControl = viewModel::setAllowTouchControl,
                            onStartHostWithProjection = viewModel::startHostMode,
                            onStopHost = viewModel::stopHostMode,
                        )
                    }
                    composable<AppRoute.Kendali> {
                        KendaliScreen(
                            signalingStatus = signalingStatus,
                            controllerState = controllerState,
                            recentDevices = recentDevices,
                            onConnect = viewModel::connectToTarget,
                            onCancelConnect = viewModel::disconnectControllerSession,
                            onRemoveRecentDevice = viewModel::removeRecentDevice,
                            onNavigateToServer = { navigateTopLevel(AppRoute.ServerConfig) },
                        )
                    }
                    composable<AppRoute.ServerConfig> {
                        ServerConfigScreen(
                            currentConfig = serverConfig,
                            signalingStatus = signalingStatus,
                            healthResult = serverHealthResult,
                            isCheckingServer = isCheckingServer,
                            snackbarHostState = snackbarHostState,
                            onSaveAndReconnect = viewModel::saveAndReconnectServer,
                            onTestServerHealth = viewModel::testServerHealth,
                        )
                    }
                    composable<AppRoute.KanvasRemote> {
                        val active = controllerState as? ControllerSessionState.Connected
                        if (active != null) {
                            KanvasRemoteScreen(
                                targetId = active.targetId,
                                targetAlias = active.targetAlias,
                                telemetry = active.telemetry,
                                currentQuality = qualityPreset,
                                webRtcManager = viewModel.webRtcManager,
                                onSendTap = viewModel::sendTouchTap,
                                onSendSwipe = viewModel::sendTouchSwipe,
                                onSendSystemAction = viewModel::sendSystemAction,
                                onSendTextInput = viewModel::sendTextInput,
                                onChangeQuality = viewModel::changeStreamQuality,
                                onEndSession = viewModel::disconnectControllerSession,
                            )
                        }
                    }
                }
            }
        }
    }

    incomingRequest?.let { req ->
        IncomingApprovalDialog(
            request = req,
            onApprove = viewModel::approveIncomingConnection,
            onReject = viewModel::rejectIncomingConnection,
        )
    }
}
