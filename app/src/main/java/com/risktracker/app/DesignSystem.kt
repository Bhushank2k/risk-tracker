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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize

/**
 * Shared visual language for Risk Tracker.
 *
 * Keep screen-specific content and business logic in their screens; use these tokens
 * and primitives so surfaces, spacing and motion stay consistent everywhere.
 */
object RiskTrackerDesign {
    val pagePadding = 18.dp
    val sectionGap = 18.dp
    val cardGap = 12.dp
    val cardPadding = 16.dp
    val compactCardPadding = 12.dp
    val pageTitleSize = 22.dp
    val navigationIconSize = 24.dp
    val cardRadius = 18.dp

    val canvasDark = Color(0xFF111418)
    val surfaceDark = Color(0xFF1B2026)
    val raisedDark = Color(0xFF242B33)
    val borderDark = Color(0xFF303841)
    val accent = Color(0xFF35B77A)
    val success = Color(0xFF35B77A)
    val warning = Color(0xFFE5AC52)
    val danger = Color(0xFFE16B70)
    val info = Color(0xFF929DAA)

    // Profit and loss: one palette for every screen, chosen by theme for contrast on its surfaces.
    val profitOnLight = Color(0xFF16865D)
    val lossOnLight = Color(0xFFD34B55)
    val profitOnDark = Color(0xFF35B77A)
    val lossOnDark = Color(0xFFE16B70)
}

/** Whether the active theme is dark, judged from its background. */
@Composable
@ReadOnlyComposable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Color for profit (positive P&L, long side). Theme-aware. */
@Composable
@ReadOnlyComposable
fun profitColor(): Color = if (isDarkTheme()) RiskTrackerDesign.profitOnDark else RiskTrackerDesign.profitOnLight

/** Color for loss (negative P&L, short side). Theme-aware. */
@Composable
@ReadOnlyComposable
fun lossColor(): Color = if (isDarkTheme()) RiskTrackerDesign.lossOnDark else RiskTrackerDesign.lossOnLight

val RiskTrackerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

val RiskTrackerTypography = Typography()

private val RiskTrackerCardBorder: BorderStroke
    @Composable get() = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
    )

/**
 * Standard card surface. The subtle outline and low elevation provide a consistent
 * hierarchy without turning every panel into a heavy floating tile.
 */
@Composable
fun RiskCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation: CardElevation = CardDefaults.cardElevation(
        defaultElevation = 0.dp,
        pressedElevation = 1.dp
    ),
    border: BorderStroke? = RiskTrackerCardBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.animateContentSize(),
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content
    )
}

/** Clickable variant with the same surface, outline and motion as RiskCard. */
@Composable
fun RiskCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation: CardElevation = CardDefaults.cardElevation(
        defaultElevation = 1.dp,
        pressedElevation = 3.dp
    ),
    border: BorderStroke? = RiskTrackerCardBorder,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.animateContentSize(),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        interactionSource = interactionSource,
        content = content
    )
}
