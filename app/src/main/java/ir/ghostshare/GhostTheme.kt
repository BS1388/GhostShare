package ir.ghostshare

import android.text.TextUtils
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.LayoutDirection

@Composable
private fun homeColorScheme(): ColorScheme {
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colorResource(R.color.m3_sys_color_primary),
        onPrimary = colorResource(R.color.m3_sys_color_on_primary),
        primaryContainer = colorResource(R.color.m3_sys_color_primary_container),
        onPrimaryContainer = colorResource(R.color.m3_sys_color_on_primary_container),
        secondary = colorResource(R.color.m3_sys_color_secondary),
        onSecondary = colorResource(R.color.m3_sys_color_on_secondary),
        secondaryContainer = colorResource(R.color.m3_sys_color_secondary_container),
        onSecondaryContainer = colorResource(R.color.m3_sys_color_on_secondary_container),
        tertiary = colorResource(R.color.m3_sys_color_tertiary),
        onTertiary = colorResource(R.color.m3_sys_color_on_tertiary),
        tertiaryContainer = colorResource(R.color.m3_sys_color_tertiary_container),
        onTertiaryContainer = colorResource(R.color.m3_sys_color_on_tertiary_container),
        error = colorResource(R.color.m3_sys_color_error),
        onError = colorResource(R.color.m3_sys_color_on_error),
        errorContainer = colorResource(R.color.m3_sys_color_error_container),
        onErrorContainer = colorResource(R.color.m3_sys_color_on_error_container),
        background = colorResource(R.color.m3_sys_color_background),
        onBackground = colorResource(R.color.m3_sys_color_on_background),
        surface = colorResource(R.color.m3_sys_color_surface),
        onSurface = colorResource(R.color.m3_sys_color_on_surface),
        surfaceVariant = colorResource(R.color.m3_sys_color_surface_variant),
        onSurfaceVariant = colorResource(R.color.m3_sys_color_on_surface_variant),
        surfaceContainerLowest = colorResource(R.color.m3_sys_color_surface_container_lowest),
        surfaceContainerLow = colorResource(R.color.m3_sys_color_surface_container_low),
        surfaceContainer = colorResource(R.color.m3_sys_color_surface_container),
        surfaceContainerHigh = colorResource(R.color.m3_sys_color_surface_container_high),
        surfaceContainerHighest = colorResource(R.color.m3_sys_color_surface_container_highest),
        outline = colorResource(R.color.m3_sys_color_outline),
        outlineVariant = colorResource(R.color.m3_sys_color_outline_variant)
    )
}

@Composable
fun GhostTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val direction =
        if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(colorScheme = homeColorScheme(), content = content)
    }
}
