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
    private var debugRoute by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDeepLink(intent)
        setContent {
            io.wearbridge.watch.ui.theme.BridgeTheme {
                WatchApp(
                    hasPermissions = hasPermissions,
                    deepLinkUid = deepLinkUid,
                    onDeepLinkConsumed = { deepLinkUid = null },
                    debugRoute = debugRoute,
                    onDebugRouteConsumed = { debugRoute = null },
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
        // Debuggable builds only: `am start ... --es route apps` opens a screen for UI checks.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) intent?.getStringExtra("route")?.let { debugRoute = it }
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
    debugRoute: String?,
    onDebugRouteConsumed: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val nav = rememberSwipeDismissableNavController()
    LaunchedEffect(deepLinkUid) {
        if (deepLinkUid != null) {
            nav.navigate("notification/$deepLinkUid")
            onDeepLinkConsumed()
        }
    }
    LaunchedEffect(debugRoute) {
        if (debugRoute != null) {
            nav.navigate(debugRoute)
            onDebugRouteConsumed()
        }
    }
    AppScaffold {
        SwipeDismissableNavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    hasPermissions = hasPermissions,
                    onRequestPermissions = onRequestPermissions,
                    onOpenNotifications = { nav.navigate("notifications") },
                    onOpenMedia = { nav.navigate("media") },
                    onOpenFindPhone = { nav.navigate("find") },
                    onOpenContacts = { nav.navigate("contacts") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") { SettingsScreen(onOpenDiagnostics = { nav.navigate("diagnostics") }) }
            composable("media") { MediaScreen() }
            composable("contacts") {
                io.wearbridge.watch.contacts.ContactsScreen(onOpen = { id -> nav.navigate("contact/" + android.net.Uri.encode(id)) })
            }
            composable("contact/{id}") { entry ->
                io.wearbridge.watch.contacts.ContactDetailScreen(entry.arguments?.getString("id").orEmpty())
            }
            composable("find") { io.wearbridge.watch.find.FindPhoneScreen() }
            composable("notifications") {
                NotificationListScreen(
                    onOpen = { uid -> nav.navigate("notification/$uid") },
                    onOpenThread = { uid -> nav.navigate("thread/$uid") },
                )
            }
            composable("thread/{uid}") { entry ->
                val uid = entry.arguments?.getString("uid")?.toLongOrNull() ?: -1L
                ThreadScreen(uid, onOpen = { nav.navigate("notification/$it") })
            }
            composable("notification/{uid}") { entry ->
                val uid = entry.arguments?.getString("uid")?.toLongOrNull() ?: -1L
                NotificationDetailScreen(uid, onDeleted = { nav.popBackStack() })
            }
            composable("diagnostics") { DiagnosticsScreen() }
        }
    }
}
