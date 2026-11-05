package com.risktracker.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize

/**
 * Quiet Trading Desk semantic design tokens.
 * Keep financial meaning attached to labels/icons as well as color.
 * Typeface fallbacks use Android's supported sans-serif and monospace families
 * until bundled Geist font files are added to res/font.
 */
object RiskTrackerDesign {
    val pagePadding = 16.dp
    val sectionGap = 24.dp
    val cardGap = 16.dp
    val cardPadding = 18.dp
    val compactCardPadding = 12.dp
    val pageTitleSize = 28.sp
    val navigationIconSize = 24.dp
    val cardRadius = 16.dp

    // Light semantic palette
    val cloudCanvas = Color(0xFFF5F7F8)
    val paperSurface = Color(0xFFFFFFFF)
    val raisedSurface = Color(0xFFEEF2F3)
    val charcoalInk = Color(0xFF202A32)
    val slateInk = Color(0xFF586570)
    val quietInk = Color(0xFF7C8993)
    val structuralLine = Color(0xFFDCE3E6)
    val evergreen = Color(0xFF356859)
    val evergreenTint = Color(0xFFE5F0EB)
    val criticalRed = Color(0xFFB42332)
    val highAmber = Color(0xFFA85B18)
    val cautionGold = Color(0xFF8A6B12)
    val positiveGreen = Color(0xFF287A54)
    val informationBlue = Color(0xFF456D9B)

    // Dark semantic palette
    val nightCanvas = Color(0xFF111719)
    val nightSurface = Color(0xFF192124)
    val raisedNightSurface = Color(0xFF222D31)
    val nightInk = Color(0xFFE8EEEF)
    val mutedNightInk = Color(0xFFA6B3B8)
    val quietNightInk = Color(0xFF829197)
    val nightStructuralLine = Color(0xFF344247)
    val nightEvergreen = Color(0xFF83B6A3)
    val evergreenNightTint = Color(0xFF243B34)
    val nightCriticalRed = Color(0xFFFF7A83)
    val nightHighAmber = Color(0xFFEBA76C)
    val nightCautionGold = Color(0xFFE3C46C)
    val nightPositiveGreen = Color(0xFF80C9A2)
    val nightInformationBlue = Color(0xFF9AB9E5)

    // Compatibility aliases for existing screens; meanings now map to semantic roles.
    val canvasDark get() = nightCanvas
    val surfaceDark get() = nightSurface
    val raisedDark get() = raisedNightSurface
    val borderDark get() = nightStructuralLine
    val accent get() = nightEvergreen
    val success get() = nightPositiveGreen
    val warning get() = nightHighAmber
    val danger get() = nightCriticalRed
    val info get() = mutedNightInk
    val profitOnLight get() = positiveGreen
    val lossOnLight get() = criticalRed
    val profitOnDark get() = nightPositiveGreen
    val lossOnDark get() = nightCriticalRed
}

val RiskTrackerLightColors = lightColorScheme(
    primary = RiskTrackerDesign.evergreen,
    onPrimary = Color.White,
    primaryContainer = RiskTrackerDesign.evergreenTint,
    onPrimaryContainer = RiskTrackerDesign.charcoalInk,
    secondary = RiskTrackerDesign.slateInk,
    onSecondary = Color.White,
    secondaryContainer = RiskTrackerDesign.raisedSurface,
    onSecondaryContainer = RiskTrackerDesign.charcoalInk,
    tertiary = RiskTrackerDesign.informationBlue,
    onTertiary = Color.White,
    background = RiskTrackerDesign.cloudCanvas,
    onBackground = RiskTrackerDesign.charcoalInk,
    surface = RiskTrackerDesign.paperSurface,
    onSurface = RiskTrackerDesign.charcoalInk,
    surfaceVariant = RiskTrackerDesign.raisedSurface,
    onSurfaceVariant = RiskTrackerDesign.slateInk,
    outline = RiskTrackerDesign.structuralLine,
    outlineVariant = RiskTrackerDesign.structuralLine,
    error = RiskTrackerDesign.criticalRed,
    onError = Color.White,
    errorContainer = Color(0xFFFCE8EA),
    onErrorContainer = RiskTrackerDesign.criticalRed
)

val RiskTrackerDarkColors = darkColorScheme(
    primary = RiskTrackerDesign.nightEvergreen,
    onPrimary = Color(0xFF10231C),
    primaryContainer = RiskTrackerDesign.evergreenNightTint,
    onPrimaryContainer = RiskTrackerDesign.nightInk,
    secondary = RiskTrackerDesign.mutedNightInk,
    onSecondary = Color(0xFF182124),
    secondaryContainer = RiskTrackerDesign.raisedNightSurface,
    onSecondaryContainer = RiskTrackerDesign.nightInk,
    tertiary = RiskTrackerDesign.nightInformationBlue,
    onTertiary = Color(0xFF14202B),
    background = RiskTrackerDesign.nightCanvas,
    onBackground = RiskTrackerDesign.nightInk,
    surface = RiskTrackerDesign.nightSurface,
    onSurface = RiskTrackerDesign.nightInk,
    surfaceVariant = RiskTrackerDesign.raisedNightSurface,
    onSurfaceVariant = RiskTrackerDesign.mutedNightInk,
    outline = RiskTrackerDesign.nightStructuralLine,
    outlineVariant = RiskTrackerDesign.nightStructuralLine,
    error = RiskTrackerDesign.nightCriticalRed,
    onError = Color(0xFF351014),
    errorContainer = Color(0xFF4A2529),
    onErrorContainer = RiskTrackerDesign.nightInk
)

private val InterfaceFont = FontFamily.SansSerif
private val NumericFont = FontFamily.Monospace

val RiskTrackerTypography = Typography(
    headlineMedium = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = InterfaceFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterfaceFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = InterfaceFont, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

@Composable
@ReadOnlyComposable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
@ReadOnlyComposable
fun profitColor(): Color = if (isDarkTheme()) RiskTrackerDesign.profitOnDark else RiskTrackerDesign.profitOnLight

@Composable
@ReadOnlyComposable
fun lossColor(): Color = if (isDarkTheme()) RiskTrackerDesign.lossOnDark else RiskTrackerDesign.lossOnLight

@Composable
@ReadOnlyComposable
fun financialNumberFont(): FontFamily = NumericFont

val RiskTrackerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

private val RiskTrackerCardBorder: BorderStroke
    @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun RiskCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
    border: BorderStroke? = RiskTrackerCardBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.animateContentSize(), shape = shape, colors = colors, elevation = elevation, border = border, content = content)
}

@Composable
fun RiskCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
    border: BorderStroke? = RiskTrackerCardBorder,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(onClick = onClick, modifier = modifier.animateContentSize(), enabled = enabled, shape = shape, colors = colors, elevation = elevation, border = border, interactionSource = interactionSource, content = content)
}
