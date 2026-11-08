package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/**
 * Evidence dashboard for the existing scanner. It deliberately distinguishes
 * manually recorded paper outcomes from historical candle replay and does not
 * infer profitability from missing or small samples.
 */
@Composable
fun ScannerPerformanceLabScreen() {
    val context = LocalContext.current
    val paperStats = TradeIdeaPaperJournal.stats(context)
    val totalSamples = paperStats.sumOf { it.closedTrades }
    val totalWins = paperStats.sumOf { it.wins }
    val weightedAverageR = paperStats.sumOf { it.averageR * it.closedTrades }
    val overallWinRate = if (totalSamples > 0) totalWins.toDouble() / totalSamples * 100.0 else null
    val overallAverageR = if (totalSamples > 0) weightedAverageR / totalSamples else null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Scanner Strategy Performance Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Measure observed outcomes before changing live scanner rules. This screen reports recorded paper trades; it does not claim that untested rules are profitable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recorded paper-trade results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (totalSamples == 0) "INSUFFICIENT DATA · No completed paper trades have been recorded yet."
                        else if (totalSamples < 30) "INSUFFICIENT DATA · $totalSamples completed samples; at least 30 are needed for a preliminary win-rate estimate."
                        else if (totalSamples < 100) "PRELIMINARY · $totalSamples completed samples; collect at least 100 before treating results as meaningful."
                        else "OBSERVED SAMPLE · $totalSamples completed paper trades. Validate on unseen periods before changing rules.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    HorizontalDivider()
                    MetricLine("Completed samples", totalSamples.toString())
                    MetricLine("Observed win rate", overallWinRate?.let { "%.1f%%".format(it) } ?: "—")
                    MetricLine("Average result", overallAverageR?.let { "%+.2fR".format(it) } ?: "—")
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Strategy breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Paper-journal outcomes are grouped by the setup name recorded at entry. A strategy with no recorded outcomes is not tested, not a failure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (paperStats.isEmpty()) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("No completed paper-trade history yet", fontWeight = FontWeight.SemiBold)
                        Text("Record and close paper setups from Daily Trade Ideas to build an evidence sample. Do not interpret an empty history as a successful or failed strategy.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            items(paperStats, key = { it.setup }) { stat ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stat.setup, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        val status = when {
                            stat.closedTrades < 30 -> "INSUFFICIENT DATA"
                            stat.closedTrades < 100 -> "PRELIMINARY"
                            stat.averageR > 0.0 -> "POSITIVE OBSERVED AVERAGE · NOT VALIDATED"
                            else -> "NON-POSITIVE OBSERVED AVERAGE · REVIEW"
                        }
                        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        MetricLine("Completed trades", stat.closedTrades.toString())
                        MetricLine("Observed win rate", "%.1f%%".format(stat.winRatePercent))
                        MetricLine("Average result", "%+.2fR".format(stat.averageR))
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Historical replay coverage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    MetricLine("Breakout + retest", "Included in causal replay; aggregate validation pending")
                    MetricLine("Trend pullback", "Included in causal replay; aggregate validation pending")
                    MetricLine("Range reversal", "Included in causal replay; aggregate validation pending")
                    MetricLine("Liquidity sweep reclaim", "Not implemented as a separate replay setup")
                    MetricLine("Scalp / Intraday / Swing", "Horizon-specific candle intervals are replayed; comparative validation pending")
                    Text(
                        "The causal replay covers breakout-retest, trend-pullback and range-reversal setups using each idea's configured intervals. Per-idea replay statistics are supplementary samples, not a validated portfolio backtest or proof of profitability.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Rule experiments · awaiting controlled tests", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "Trend alignment · compare with and without the filter",
                        "Relative volume · test each strategy threshold independently",
                        "RSI guardrails · measure missed winners and avoided losses",
                        "BTC influence · blanket filter versus per-coin correlation",
                        "Spread and funding · quantify execution-cost and tail-risk impact",
                        "Anti-chasing · compare entry quality and missed opportunities"
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "No rule is labelled PASS or FAIL until the same entry/exit model is tested on matched historical samples, including fees, slippage and funding, then checked on an unseen period.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Text(
                "Limitations: recorded paper results depend on correct entry/exit recording and currently exclude a full exchange-cost model. Win rate alone is not profitability; future validation must include profit factor, expectancy, drawdown, sample size and out-of-sample stability. This lab never changes live scanner rules automatically.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
