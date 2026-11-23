package com.touchbridge.rpe.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.touchbridge.rpe.ui.pages.AutoChartPage
import com.touchbridge.rpe.ui.pages.PreviewPage
import com.touchbridge.rpe.ui.pages.ProjectPage
import com.touchbridge.rpe.ui.pages.RecordPage
import com.touchbridge.rpe.ui.pages.SettingsPage
import com.touchbridge.rpe.ui.theme.AppColors

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOfNotNull(
    NavItem("project", "工程", Icons.Rounded.Home),
    NavItem("record", "录制", Icons.Rounded.Gesture),
    NavItem("autochart", "制谱", Icons.Rounded.AutoAwesome).takeIf { com.touchbridge.rpe.domain.autochart.AutoChartFeature.ENABLED },
    NavItem("settings", "参数", Icons.Rounded.Tune),
    NavItem("preview", "预览", Icons.Rounded.PlayCircle),
)

private fun NavHostController.go(route: String, current: String) {
    if (current == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNavHost(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "project"
    val immersive = route == "record" || route == "autochart"
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.toast?.id) {
        val t = vm.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(t.text)
        vm.clearToast(t.id)
    }

    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = AppColors.Bg0,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!immersive && !wide) {
                    NavigationBar(containerColor = Color(0xF20B1424), tonalElevation = 0.dp) {
                        navItems.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = { nav.go(item.route, route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AppColors.PrimaryBright,
                                    selectedTextColor = AppColors.PrimaryBright,
                                    indicatorColor = AppColors.PrimaryDeep,
                                    unselectedIconColor = AppColors.TextMuted,
                                    unselectedTextColor = AppColors.TextMuted,
                                ),
                            )
                        }
                    }
                }
            },
        ) { inner ->
            Row(modifier = Modifier.padding(inner)) {
                if (!immersive && wide) {
                    NavigationRail(containerColor = Color(0xF20B1424)) {
                        Spacer(Modifier.height(18.dp))
                        navItems.forEach { item ->
                            NavigationRailItem(
                                selected = route == item.route,
                                onClick = { nav.go(item.route, route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = AppColors.PrimaryBright,
                                    selectedTextColor = AppColors.PrimaryBright,
                                    indicatorColor = AppColors.PrimaryDeep,
                                    unselectedIconColor = AppColors.TextMuted,
                                    unselectedTextColor = AppColors.TextMuted,
                                ),
                            )
                        }
                    }
                }
                NavHost(
                    navController = nav,
                    startDestination = "project",
                    modifier = Modifier.weight(1f),
                ) {
                    composable("project") {
                        ProjectPage(
                            vm = vm,
                            onRecord = { nav.navigate("record") },
                            onPreview = { nav.navigate("preview") },
                            onAutoChart = { nav.navigate("autochart") },
                        )
                    }
                    composable("autochart") {
                        AutoChartPage(appVm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("record") {
                        RecordPage(
                            vm = vm,
                            onExit = {
                                if (nav.previousBackStackEntry != null) nav.popBackStack()
                                else nav.navigate("project")
                            },
                            onPreview = { nav.navigate("preview") },
                        )
                    }
                    composable("settings") { SettingsPage(vm) }
                    composable("preview") {
                        PreviewPage(
                            vm = vm,
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                }
            }
        }
    }
}
