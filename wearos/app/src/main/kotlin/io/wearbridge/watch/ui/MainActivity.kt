package io.wearbridge.watch.ui

import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wearbridge.watch.BridgeGraph
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.service.BridgeService

class MainActivity : ComponentActivity() {

    private var hasPermissions by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            bluetoothEnabled = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
        }
    }
    private var deepLinkUid by mutableStateOf<Long?>(null)
    private var debugRoute by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        readDeepLink(intent)
        setContent {
            io.wearbridge.watch.ui.theme.BridgeTheme {
                WatchApp(
                    hasPermissions = hasPermissions,
                    bluetoothEnabled = bluetoothEnabled,
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

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeepLink(intent)
    }

    private fun refreshPermissions() {
        hasPermissions = BridgePermissions.hasRequired(this)
        bluetoothEnabled = hasPermissions && runCatching { getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
        if (hasPermissions) BridgeService.start(this) // idempotent
    }

    private fun requestPermissions() {
        permissionLauncher.launch(BridgePermissions.required + BridgePermissions.optional)
    }

    private fun readDeepLink(intent: Intent?) {
        intent?.getLongExtra(EXTRA_UID, -1)?.takeIf { it >= 0 }?.let { deepLinkUid = it }
        // Debuggable builds only: `am start ... --es route apps` opens a screen for UI checks.
        if (resources.getBoolean(io.wearbridge.watch.R.bool.demo_tools)) intent?.getStringExtra("route")?.let { debugRoute = it }
        // From the tile: only known screen names are accepted.
        intent?.getStringExtra(EXTRA_OPEN)?.takeIf { it in OPENABLE }?.let { debugRoute = if (it == "home") null else it }
    }

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_OPEN = "open"
        private val OPENABLE = setOf("home", "notifications", "media", "find", "contacts")
    }
}

@Composable
private fun WatchApp(
    hasPermissions: Boolean,
    bluetoothEnabled: Boolean,
    deepLinkUid: Long?,
    onDeepLinkConsumed: () -> Unit,
    debugRoute: String?,
    onDebugRouteConsumed: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val pairing by BridgeGraph.pairing.state.collectAsStateWithLifecycle()
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val approval by BridgeGraph.pendingApproval.collectAsStateWithLifecycle()
    val available = pairing.canAccess(link)
    val nav = rememberBridgeNavigator("home")
    LaunchedEffect(available, nav.top.route) {
        if (!available) nav.removePeerScreens()
    }
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
        val code = approval
        if (code != null) {
            PairingApprovalScreen(code, onAllow = { BridgeGraph.approveIphoneApp(true) },
                onDeny = { BridgeGraph.approveIphoneApp(false) })
        } else BridgeNavHost(nav) { route ->
            val arg = route.substringAfter('/', "")
            val safeRoute = if (!available && route.substringBefore('/') !in setOf("home", "device", "diagnostics")) "home" else route.substringBefore('/')
            when (safeRoute) {
                "home" -> HomeScreen(
                    hasPermissions = hasPermissions,
                    bluetoothEnabled = bluetoothEnabled,
                    onRequestPermissions = onRequestPermissions,
                    onOpenNotifications = { nav.navigate("notifications") },
                    onOpenMedia = { nav.navigate("media") },
                    onOpenFindPhone = { nav.navigate("find") },
                    onOpenContacts = { nav.navigate("contacts") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenDevice = { nav.navigate("device") },
                )
                "device" -> DeviceScreen(hasPermissions, bluetoothEnabled, onRequestPermissions, onDiagnostics = { nav.navigate("diagnostics") })
                "settings" -> SettingsScreen(onOpenDiagnostics = { nav.navigate("diagnostics") })
                "media" -> MediaScreen()
                "contacts" -> io.wearbridge.watch.contacts.ContactsScreen(onOpen = { id -> nav.navigate("contact/" + android.net.Uri.encode(id)) })
                "contact" -> io.wearbridge.watch.contacts.ContactDetailScreen(android.net.Uri.decode(arg))
                "find" -> io.wearbridge.watch.find.FindPhoneScreen()
                "notifications" -> NotificationListScreen(
                    onOpen = { uid -> nav.navigate("notification/$uid") },
                    onOpenThread = { uid -> nav.navigate("thread/$uid") },
                )
                "thread" -> ThreadScreen(arg.toLongOrNull() ?: -1L, onOpen = { nav.navigate("notification/$it") })
                "notification" -> NotificationDetailScreen(arg.toLongOrNull() ?: -1L, onDeleted = { nav.popBackStack() })
                "diagnostics" -> DiagnosticsScreen()
            }
        }
    }
}
