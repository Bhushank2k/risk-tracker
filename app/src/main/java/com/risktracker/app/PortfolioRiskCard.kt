package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PortfolioRiskCard(
    positions: List<BinanceLiveTrade>,
    futuresBalance: BinanceFuturesAccountBalance?,
    snapshotAge: String?,
    hasCredentials: Boolean,
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PORTFOLIO RISK", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (!hasCredentials) {
                Text("Connect read-only Binance credentials to calculate live exposure and account availability.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Open notional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(exposure.usd(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Open positions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(positions.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Futures equity", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(futuresBalance?.totalEquity?.usd() ?: "Unavailable", fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Available balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(futuresBalance?.availableBalance?.usd() ?: "Unavailable", fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Unrealized P&L", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pnl = positions.sumOf { it.unrealizedPnl }
                    Text((if (pnl > 0.0) "+" else "") + pnl.usd(), fontWeight = FontWeight.SemiBold,
                        color = if (pnl >= 0.0) DashboardGreen else DashboardRed)
                }
                HorizontalDivider()
                Text("Liquidation buffer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (nearestLiquidation == null) {
                    Text(if (positions.isEmpty()) "No open positions." else "Liquidation price is not available for these positions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val (position, distance) = nearestLiquidation
                    Text(position.symbol + " · " + distance.pct(2) + " from mark to liquidation",
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
                if (bySymbol.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Exposure by symbol", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    bySymbol.take(5).forEach { row ->
                        val share = if (exposure > 0.0) row.value / exposure * 100.0 else 0.0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(row.key, style = MaterialTheme.typography.bodySmall)
                            Text(row.value.usd() + " · " + share.pct(1), style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Text("Open notional is quantity × mark price (gross exposure, not margin or maximum loss). Liquidation distance is an estimate from Binance's reported liquidation price. Account balance uses the saved snapshot" +
                    (snapshotAge?.let { " · $it" } ?: "") + ".",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (positionsRefreshFailed) {
                    Text("Position refresh failed; exposure may be stale.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
