package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale


@Composable
fun PortfolioRiskCard(
    positions: List<BinanceLiveTrade>,
    futuresBalance: BinanceFuturesAccountBalance?,
    positionsRefreshFailed: Boolean
) {
    val exposure = positions.sumOf { kotlin.math.abs(it.quantity * it.markPrice) }
    val nearestLiquidation = positions.mapNotNull { position ->
        if (position.liquidationPrice <= 0.0 || position.markPrice <= 0.0) null
        else {
            val distance = if (position.side == "LONG")
                (position.markPrice - position.liquidationPrice) / position.markPrice * 100.0
            else (position.liquidationPrice - position.markPrice) / position.markPrice * 100.0
            position to distance.coerceAtLeast(0.0)
        }
    }.minByOrNull { it.second }
    val bySymbol = positions.groupBy { it.symbol }.mapValues { (_, rows) ->
        rows.sumOf { kotlin.math.abs(it.quantity * it.markPrice) }
    }.entries.sortedByDescending { it.value }
    RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PORTFOLIO RISK", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Open notional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(exposure.let { usdAmount(it) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
                Text("Liquidation buffer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (nearestLiquidation != null) {
                    val (position, distance) = nearestLiquidation
                    Text(position.symbol + " · " + String.format(Locale.US, "%.2f%%", distance) + " from mark to liquidation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (distance < 5.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (distance < 5.0) {
                        Text("Very small liquidation buffer. Review position risk immediately.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else if (distance < 10.0) {
                        Text("Liquidation buffer is relatively narrow.", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (bySymbol.size > 1) {
                    HorizontalDivider()
                    Text("Exposure by symbol", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    bySymbol.take(5).forEach { row ->
                        val share = if (exposure > 0.0) row.value / exposure * 100.0 else 0.0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(row.key, style = MaterialTheme.typography.bodySmall)
                            Text(row.value.let { usdAmount(it) } + " · " + String.format(Locale.US, "%.1f%%", share), style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            if (positionsRefreshFailed) {
                Text("Position data may be stale", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
