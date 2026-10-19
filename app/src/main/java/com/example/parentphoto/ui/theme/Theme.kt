package com.example.parentphoto.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// 色トークン(ds 3.4)。ダイナミックカラーは使わず固定値にする
private val LightColors = lightColorScheme(
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF625B71),
    outline = Color(0xFF79747E),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    scrim = Color(0xFF000000),
)

/** Material3に無い追加色。警告色(白文字を載せる) */
@Immutable
data class ExtraColors(
    val warning: Color = Color(0xFFE65100),
    val onWarning: Color = Color(0xFFFFFFFF),
)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors() }

// ボタンは角丸12dp。バッジの丸薬形は各画面で CircleShape 等を使う
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
)

/** アプリ共通テーマ。端末のフォント倍率には従う(設定画面用)。 */
@Composable
fun FamilyPhotoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides ExtraColors()) {
        MaterialTheme(colorScheme = LightColors, shapes = AppShapes) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** 父親向け画面用。端末のフォント倍率を無視して fontScale = 1 に固定する。 */
@Composable
fun FatherTheme(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1f)) {
        FamilyPhotoTheme(content)
    }
}
