package io.wearbridge.watch.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.service.BridgeService

class MainActivity : ComponentActivity() {

    private var hasPermissions by mutableStateOf(false)
    private var deepLinkUid by mutableStateOf<Long?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDeepLink(intent)
        setContent {
            MaterialTheme {
                WatchApp(
                    hasPermissions = hasPermissions,
                    deepLinkUid = deepLinkUid,
                    onDeepLinkConsumed = { deepLinkUid = null },
                    onRequestPermissions = ::requestPermissions,
                )
            }
        }
        if (!BridgePermissions.hasRequired(this)) requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeepLink(intent)
    }

    private fun refreshPermissions() {
        hasPermissions = BridgePermissions.hasRequired(this)
        if (hasPermissions) BridgeService.start(this) // idempotent
    }

    private fun requestPermissions() {
        permissionLauncher.launch(BridgePermissions.required + BridgePermissions.optional)
    }

    private fun readDeepLink(intent: Intent?) {
        intent?.getLongExtra(EXTRA_UID, -1)?.takeIf { it >= 0 }?.let { deepLinkUid = it }
    }

    companion object {
        const val EXTRA_UID = "uid"
    }
}

@Composable
private fun WatchApp(
    hasPermissions: Boolean,
    deepLinkUid: Long?,
    onDeepLinkConsumed: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val nav = rememberSwipeDismissableNavController()
    LaunchedEffect(deepLinkUid) {
        if (deepLinkUid != null) {
            nav.navigate("notification/$deepLinkUid")
            onDeepLinkConsumed()
        }
    }
    AppScaffold {
        SwipeDismissableNavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    hasPermissions = hasPermissions,
                    onRequestPermissions = onRequestPermissions,
                    onOpenNotifications = { nav.navigate("notifications") },
                    onOpenDiagnostics = { nav.navigate("diagnostics") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") { SettingsScreen(onOpenApps = { nav.navigate("apps") }) }
            composable("apps") { AppsScreen(onOpenApp = { id -> nav.navigate("app/$id") }) }
            composable("app/{bundleId}") { entry ->
                AppSettingsScreen(entry.arguments?.getString("bundleId").orEmpty())
            }
            composable("notifications") {
                NotificationListScreen(onOpen = { uid -> nav.navigate("notification/$uid") })
            }
            composable("notification/{uid}") { entry ->
                val uid = entry.arguments?.getString("uid")?.toLongOrNull() ?: -1L
                NotificationDetailScreen(uid)
            }
            composable("diagnostics") { DiagnosticsScreen() }
        }
    }
}
