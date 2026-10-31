package dev.pcremote.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val RemoteSuccess = Color(0xFF28C98B)
private val RemoteColors = darkColorScheme(
    primary = Color(0xFFA99FFF), onPrimary = Color(0xFF17122F),
    primaryContainer = Color(0xFF302A50), onPrimaryContainer = Color(0xFFE5DFFF),
    secondary = Color(0xFF82AEFF), onSecondary = Color(0xFF0D2448),
    secondaryContainer = Color(0xFF203552), onSecondaryContainer = Color(0xFFD9E7FF),
    background = Color(0xFF0C1018), onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF161E2B), onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0xFF1C2635), onSurfaceVariant = Color(0xFFA7B2C4),
    surfaceContainer = Color(0xFF161E2B), surfaceContainerLow = Color(0xFF111722),
    surfaceContainerHigh = Color(0xFF1C2635), surfaceContainerHighest = Color(0xFF263249),
    outline = Color(0xFF738096), outlineVariant = Color(0xFF2B374A),
    error = Color(0xFFF38B9B), onError = Color(0xFF3C1020),
    errorContainer = Color(0xFF3A202D), onErrorContainer = Color(0xFFFFD9E0)
)

@Composable internal fun PCRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemoteColors,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        typography = Typography(
            headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        ), content = content
    )
}

@Composable internal fun RemoteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = content)
}
