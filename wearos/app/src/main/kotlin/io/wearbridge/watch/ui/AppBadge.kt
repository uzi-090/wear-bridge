package io.wearbridge.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.notifications.AppInfo

/** The app's badge: its logo (or initial) in white on its brand colour. Decorative, since the name is always shown next to it. */
@Composable
fun AppBadge(app: AppInfo, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(
        modifier
            .size(size)
            .background(Color(app.color), CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val logo = AppLogos.drawable(app.logo)
        if (logo != null) {
            Icon(painterResource(logo), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.58f))
        } else {
            Text(
                app.initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.5f).sp,
                maxLines = 1,
            )
        }
    }
}
