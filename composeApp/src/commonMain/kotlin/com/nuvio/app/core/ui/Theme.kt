package com.nuvio.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.jetbrains_sans_bold
import nuvio.composeapp.generated.resources.jetbrains_sans_regular
import nuvio.composeapp.generated.resources.jetbrains_sans_semibold
import org.jetbrains.compose.resources.Font

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }
val LocalThemePalette = staticCompositionLocalOf { ThemeColors.White }

val MaterialTheme.themePalette: ThemeColorPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalThemePalette.current

internal val LocalNuvioPlatformDensity = staticCompositionLocalOf<Density> {
    error("Platform density is unavailable outside NuvioTheme")
}

val MaterialTheme.appTheme: AppTheme
    @Composable
    @ReadOnlyComposable
    get() = LocalAppTheme.current

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF111111) else Color(0xFFF5F7F8)

private fun buildColorScheme(palette: ThemeColorPalette, amoled: Boolean = false) = darkColorScheme(
    primary = palette.secondary,
    onPrimary = palette.onSecondary,
    primaryContainer = palette.focusBackground,
    onPrimaryContainer = contentColorFor(palette.focusBackground),
    secondary = palette.secondaryVariant,
    onSecondary = palette.onSecondaryVariant,
    background = if (amoled) Color.Black else palette.background,
    onBackground = Color(0xFFF5F7F8),
    surface = palette.backgroundElevated,
    onSurface = Color(0xFFF5F7F8),
    surfaceVariant = palette.backgroundCard,
    onSurfaceVariant = Color(0xFF969CA3),
    outline = Color(0xFF252A2A),
    error = Color(0xFFE36A8A),
    onError = Color(0xFFFCE5EC),
)

private val JetBrainsSans: FontFamily
    @Composable
    get() = FontFamily(
        Font(Res.font.jetbrains_sans_bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.jetbrains_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
        Font(Res.font.jetbrains_sans_regular, FontWeight.Normal, FontStyle.Normal),
    )

private val NuvioTypography: Typography
    @Composable
    get() = Typography(
        displayLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.pageDisplay,
            lineHeight = NuvioTokens.LineHeight.pageDisplay,
            fontWeight = FontWeight.Bold,
            letterSpacing = NuvioTokens.LetterSpacing.pageDisplay,
        ),
        headlineLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.headline,
            lineHeight = NuvioTokens.LineHeight.headline,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.headline,
        ),
        titleLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleSm,
            lineHeight = NuvioTokens.LineHeight.materialTitleLarge,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyLg,
            lineHeight = NuvioTokens.LineHeight.bodyMd,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyApp,
            lineHeight = NuvioTokens.LineHeight.bodyApp,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyMd,
            lineHeight = NuvioTokens.LineHeight.bodyMd,
            fontWeight = FontWeight.Normal,
        ),
        labelLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyMd,
            lineHeight = NuvioTokens.LineHeight.bodySm,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.labelSm,
            lineHeight = NuvioTokens.LineHeight.labelXs,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.label,
        ),
    )

private val NuvioTypeTokens: NuvioTypeScale
    @Composable
    get() = NuvioTypeScale(
        labelXs = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.labelXs,
            lineHeight = NuvioTokens.LineHeight.labelXs,
            fontWeight = FontWeight.SemiBold,
        ),
        labelSm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.labelSm,
            lineHeight = NuvioTokens.LineHeight.labelSm,
            fontWeight = FontWeight.SemiBold,
        ),
        bodySm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodySm,
            lineHeight = NuvioTokens.LineHeight.bodySm,
            fontWeight = FontWeight.Normal,
        ),
        bodyMd = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyMd,
            lineHeight = NuvioTokens.LineHeight.bodyMd,
            fontWeight = FontWeight.Normal,
        ),
        bodyLg = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyLg,
            lineHeight = NuvioTokens.LineHeight.bodyLg,
            fontWeight = FontWeight.Normal,
        ),
        titleSm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleSm,
            lineHeight = NuvioTokens.LineHeight.titleSm,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMd = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleMd,
            lineHeight = NuvioTokens.LineHeight.titleMd,
            fontWeight = FontWeight.SemiBold,
        ),
        titleLg = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleLg,
            lineHeight = NuvioTokens.LineHeight.titleLg,
            fontWeight = FontWeight.SemiBold,
        ),
        displaySm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.displaySm,
            lineHeight = NuvioTokens.LineHeight.displaySm,
            fontWeight = FontWeight.Bold,
        ),
        displayMd = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.displayMd,
            lineHeight = NuvioTokens.LineHeight.displayMd,
            fontWeight = FontWeight.Bold,
        ),
    )

private val NuvioRippleConfiguration = RippleConfiguration(
    color = Color.Black,
)

private const val NuvioDesktopFontScale = 1.08f
private const val NuvioDesktopBaseWidthDp = 1280f
private const val NuvioDesktopBaseHeightDp = 820f
private const val NuvioDesktopMinUiScale = 1f

/**
 * How far the automatic scale is allowed to go.
 *
 * ⚠ **This was 1.18, and that number was the reason the app looked tiny on a 4K display.** The
 * formula below is a ratio against a 1280x820 base, so a 3840x2160 window at 100% Windows scaling
 * asks for `min(3.0, 2.63) = 2.63` and used to be handed 1.18 - which laid the whole app out into
 * a **3254 x 1831 dp** space. Every fixed dp in the app (the 84 dp sidebar, a 126 dp poster, every
 * type size) was then drawn at roughly a third of the size it occupies on a 1280 window. Nothing
 * was broken; there was simply no headroom.
 *
 * The formula was always right. The ceiling was the bug.
 */
private const val NuvioDesktopMaxUiScale = 2.2f

/**
 * The widest range a *user-chosen* zoom is allowed to reach.
 *
 * Separate from [NuvioDesktopMaxUiScale] because that one caps what the app decides on its own,
 * while this caps what the user can ask for on top of it - see `DesktopUiZoom`. The lower bound is
 * below 1.0 deliberately: automatic already refuses to shrink below 1.0, but somebody on a large
 * monitor who wants more content on screen has no other way to ask for it.
 */
private const val NuvioDesktopMinEffectiveUiScale = 0.5f
private const val NuvioDesktopMaxEffectiveUiScale = 4f

/**
 * The scale the app picks for itself, before the user's zoom is applied.
 *
 * `min` of the two ratios, so it only grows when **both** dimensions have room - a short, wide
 * window is driven by its height and does not get scaled past what it can show. It never returns
 * less than 1.0: a small window is not shrunk, it just shows less.
 */
internal fun desktopUiScaleForWindow(widthDp: Float, heightDp: Float): Float {
    if (!isDesktop || widthDp <= 0f || heightDp <= 0f) return NuvioDesktopMinUiScale

    val rawScale = minOf(
        widthDp / NuvioDesktopBaseWidthDp,
        heightDp / NuvioDesktopBaseHeightDp,
    )
    return rawScale.coerceIn(NuvioDesktopMinUiScale, NuvioDesktopMaxUiScale)
}

@Composable
fun NuvioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppTheme = AppTheme.WHITE,
    amoled: Boolean = false,
    desktopUiScale: Float = NuvioDesktopMinUiScale,
    customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    content: @Composable () -> Unit,
) {
    val palette = remember(appTheme, customThemeColors) {
        ThemeColors.getColorPalette(appTheme, customThemeColors)
    }
    val colorScheme = buildColorScheme(palette, amoled = amoled)
    val tokens = defaultNuvioThemeTokens(palette, amoled = amoled, colorScheme = colorScheme)

    val density = LocalDensity.current
    // ⚠ Clamped against the *effective* range, not [NuvioDesktopMaxUiScale]. The caller passes
    // `automatic x the user's zoom`, so re-clamping to the automatic ceiling here would silently
    // discard every zoom step above it and the setting would appear to stop working at 100%.
    val effectiveDesktopUiScale = if (isDesktop) {
        desktopUiScale.coerceIn(NuvioDesktopMinEffectiveUiScale, NuvioDesktopMaxEffectiveUiScale)
    } else {
        NuvioDesktopMinUiScale
    }
    CompositionLocalProvider(
        LocalNuvioPlatformDensity provides density,
        LocalDensity provides Density(
            density = density.density * effectiveDesktopUiScale,
            fontScale = if (isDesktop) NuvioDesktopFontScale else 1f,
        ),
        LocalNuvioThemeTokens provides tokens,
        LocalNuvioTypeScale provides NuvioTypeTokens,
        LocalRippleConfiguration provides NuvioRippleConfiguration,
        LocalAppTheme provides appTheme,
        LocalThemePalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NuvioTypography,
        ) {
            SkeletonAnimationProvider(content = content)
        }
    }
}
