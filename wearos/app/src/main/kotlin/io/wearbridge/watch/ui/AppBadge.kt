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
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.notifications.AppInfo

/** The app's monogram badge: its initial on its accent colour. Decorative, since the name is always shown next to it. */
@Composable
fun AppBadge(app: AppInfo, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(Color(app.color), CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            app.initial,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
            maxLines = 1,
        )
    }
}
