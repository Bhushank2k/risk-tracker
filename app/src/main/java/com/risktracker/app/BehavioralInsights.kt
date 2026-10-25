package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class StrategyInsight(val strategy: String, val sampleSize: Int, val netPnl: Double, val winRate: Double, val averagePnl: Double)
data class BehavioralInsights(val tradeCount: Int, val taggedCount: Int, val strategies: List<StrategyInsight>, val mistakeCounts: Map<String, Int>, val largestLoss: TradeEntry?, val warnings: List<String>)

fun analyzeTradeBehavior(trades: List<TradeEntry>, minimumSampleSize: Int = 3): BehavioralInsights {
    val groups = trades.groupBy { it.strategy.trim().ifBlank { "Untagged" } }
    val strategyStats = groups.map { (name, rows) ->
        val decisive = rows.count { it.pnl != 0.0 }
        StrategyInsight(name, rows.size, rows.sumOf { it.pnl },
            if (decisive == 0) 0.0 else rows.count { it.pnl > 0.0 } * 100.0 / decisive,
            if (rows.isEmpty()) 0.0 else rows.sumOf { it.pnl } / rows.size)
    }.sortedByDescending { it.netPnl }
    val mistakes = trades.map { it.mistake.trim() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.toMap()
    val warnings = buildList {
        if (trades.size < minimumSampleSize) add("Only " + trades.size + " trades are available; wait for more data before comparing patterns.")
        if (trades.any { it.riskUsd <= 0.0 }) add("Some trades have no recorded planned risk, so risk-normalized comparisons are incomplete.")
        if (trades.any { it.strategy.isBlank() }) add("Some trades are untagged; strategy comparisons may be incomplete.")
    }
    return BehavioralInsights(trades.size, trades.count { it.strategy.isNotBlank() }, strategyStats, mistakes, trades.filter { it.pnl < 0.0 }.minByOrNull { it.pnl }, warnings)
}

@Composable
fun BehavioralInsightsScreen(trades: List<TradeEntry>) {
    val report = analyzeTradeBehavior(trades)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Behavioral intelligence", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Explainable patterns from your saved journal. These are descriptive statistics, not predictions or proof of causation.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Journal coverage", style = MaterialTheme.typography.titleMedium)
                Text("Trades analyzed: " + report.tradeCount)
                Text("Strategy-tagged: " + report.taggedCount)
                report.warnings.forEach { Text("• " + it, color = MaterialTheme.colorScheme.tertiary) }
            }
        }
        Text("Results by strategy", style = MaterialTheme.typography.titleMedium)
        if (report.strategies.isEmpty()) Text("Add closed trades to start seeing patterns.")
        report.strategies.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.strategy, fontWeight = FontWeight.Bold)
                    Text("Sample: " + s.sampleSize + " trades")
                    Text("Net P&L: " + s.netPnl.usd() + " · Average: " + s.averagePnl.usd())
                    Text("Win rate: " + s.winRate.pct(1))
                    if (s.sampleSize < 3) Text("Small sample: comparison is not highlighted as a reliable pattern.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Most common mistake tags", style = MaterialTheme.typography.titleMedium)
        if (report.mistakeCounts.isEmpty()) Text("No mistake tags recorded yet.")
        report.mistakeCounts.entries.take(5).forEach { (tag, count) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tag); Text(count.toString() + " trades", fontWeight = FontWeight.SemiBold) }
        }
        report.largestLoss?.let { loss ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Largest recorded loss", fontWeight = FontWeight.Bold)
                    Text(loss.symbol + " · " + loss.side + " · " + loss.date)
                    Text(loss.pnl.usd(), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
