package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.settings.PopupStyle
import io.wearbridge.watch.ui.theme.BridgeList
import io.wearbridge.watch.ui.theme.BridgePalette
import io.wearbridge.watch.ui.theme.NavButton
import io.wearbridge.watch.ui.theme.Note
import io.wearbridge.watch.ui.theme.SectionHeader
import io.wearbridge.watch.ui.theme.SettingSwitch
import io.wearbridge.watch.ui.theme.morph
import io.wearbridge.watch.ui.theme.surface

/** Watch settings, grouped the way people think about them. The same settings are in the iPhone app. */
@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit) {
    val settings = BridgeGraph.settings
    val popup by settings.popupStyle.collectAsStateWithLifecycle()
    val endCall by settings.endCallFromWatch.collectAsStateWithLifecycle()
    val history by settings.history.collectAsStateWithLifecycle()
    val shakeDecline by settings.shakeToDecline.collectAsStateWithLifecycle()
    val shakeEnd by settings.shakeToEnd.collectAsStateWithLifecycle()
    val bezel by settings.bezelToAnswer.collectAsStateWithLifecycle()
    val version = LocalContext.current.let { c ->
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    BridgeList { spec ->
        item { SectionHeader("Settings", spec) }
        item { Note(spec, "New notifications", BridgePalette.TextSecondary) }
        PopupStyle.entries.forEach { style ->
            item {
                RadioButton(
                    selected = popup == style,
                    onSelect = { settings.setPopupStyle(style) },
                    modifier = morph(spec),
                    transformation = surface(spec),
                    icon = {
                        Icon(
                            painterResource(if (style == PopupStyle.FULL) R.drawable.ic_notifications else R.drawable.ic_vibration),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    },
                    label = { Text(style.label) },
                    secondaryLabel = { Text(style.description, maxLines = 3) },
                )
            }
        }
        item {
            SettingSwitch(spec, R.drawable.ic_history, "Keep history", "24 hours after you clear them on iPhone. Never for hidden apps.", history, onChange = BridgeGraph::setHistory)
        }

        item { SectionHeader("Calls", spec) }
        item { SettingSwitch(spec, R.drawable.ic_rotate, "Bezel to answer", "3 clicks clockwise answers, anticlockwise declines", bezel, onChange = settings::setBezelToAnswer) }
        item { SettingSwitch(spec, R.drawable.ic_vibration, "Shake to decline", "A firm shake while a call rings", shakeDecline, onChange = settings::setShakeToDecline) }
        item {
            SettingSwitch(
                spec, R.drawable.ic_call_end, "Shake to end",
                if (endCall) "Ends a connected call" else "Needs End calls from watch",
                shakeEnd, enabled = endCall, onChange = settings::setShakeToEnd,
            )
        }
        item { Note(spec, "Silence stops the watch only. iOS doesn't let accessories silence the iPhone.") }

        item { SectionHeader("Experimental", spec) }
        item {
            SettingSwitch(
                spec, R.drawable.ic_science, "End calls from watch",
                "Uses iPhone behaviour Apple doesn't document. An iOS update may remove it.",
                endCall, onChange = settings::setEndCallFromWatch,
            )
        }

        item { SectionHeader("Security", spec) }
        item {
            NavButton(spec, R.drawable.ic_lock, "Forget iPhone app", "It will need your OK again", iconTint = BridgePalette.Destructive) {
                BridgeGraph.forgetIphoneApps()
            }
        }

        item { SectionHeader("About", spec) }
        item { NavButton(spec, R.drawable.ic_info, "Diagnostics", "Connection details for troubleshooting", onClick = onOpenDiagnostics) }
        item { Note(spec, "Bridge $version · Your data stays on your devices.", BridgePalette.TextTertiary) }
    }
}
