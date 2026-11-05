package com.risktracker.app

import kotlin.math.abs
import kotlin.math.min

/**
 * A reconstructed one-way USDⓈ-M Futures position cycle.
 *
 * This is derived only from saved Binance executions. Because the execution model does not retain
 * Binance positionSide, hedge-mode accounts cannot be reconstructed safely and are shown as
 * execution history rather than merged into guessed long/short cycles.
 */
data class BinancePositionCycle(
    val key: String,
    val symbol: String,
    val market: String,
    val direction: String,
    val firstObservedCycle: Boolean,
    val startTime: Long,
    val endTime: Long,
    val openedQuantity: Double,
    val closedQuantity: Double,
    val averageEntryPrice: Double?,
    val averageExitPrice: Double?,
    val realizedPnl: Double?,
    val feeTotals: Map<String, Double>,
    val fillCount: Int,
    val unclosedQuantity: Double,
    val fills: List<BinanceJournalFill>
) {
    val isUnclosed: Boolean get() = unclosedQuantity > 1e-10
}

data class BinanceSpotActivityGroup(
    val symbol: String,
    val fills: List<BinanceJournalFill>,
    val baseQuantityBought: Double,
    val baseQuantitySold: Double,
    val quoteVolume: Double,
    val feeTotals: Map<String, Double>
)

/**
 * Reconstructs position lifecycles from chronological Binance USDⓈ-M fills in one-way mode.
 * Spot is deliberately grouped as activity, not as closed positions: deposits, withdrawals and
 * pre-import inventory are not represented in trade fills, so a fill-only Spot P&L would be false.
 */
object BinancePositionJournal {
    fun futuresCycles(fills: List<BinanceJournalFill>): List<BinancePositionCycle> {
        val result = mutableListOf<BinancePositionCycle>()
        fills.asSequence()
            .filter { it.market == "USDⓈ-M Futures" && it.quantity.isFinite() && it.quantity > 0.0 &&
                it.price.isFinite() && it.price > 0.0 && it.side.uppercase() in setOf("BUY", "SELL") }
            .groupBy { Triple(it.exchange, it.market, it.symbol.uppercase()) }
            .toSortedMap(compareBy<Triple<String, String, String>> { it.third }.thenBy { it.first })
            .forEach { (_, rows) ->
                var builder: CycleBuilder? = null
                var firstCycle = true
                rows.sortedWith(compareBy<BinanceJournalFill> { it.time }.thenBy { it.id }).forEach { fill ->
                    val signed = if (fill.side.equals("BUY", true)) fill.quantity else -fill.quantity
                    val current = builder
                    if (current == null || abs(current.netQuantity) < EPSILON ||
                        current.netQuantity * signed > 0.0) {
                        val active = current ?: CycleBuilder(fill.symbol, fill.market, fill.time, firstCycle)
                            .also { builder = it; firstCycle = false }
                        active.addOpen(fill, abs(signed))
                        active.netQuantity += signed
                    } else {
                        val closingQty = min(abs(current.netQuantity), abs(signed))
                        val closeFraction = closingQty / fill.quantity
                        current.addClose(fill, closingQty, closeFraction)
                        current.netQuantity += if (signed > 0) closingQty else -closingQty
                        if (abs(current.netQuantity) < EPSILON) {
                            result += current.build()
                            builder = null
                        }
                        val remainder = fill.quantity - closingQty
                        if (remainder > EPSILON) {
                            val remainderFraction = remainder / fill.quantity
                            val next = CycleBuilder(fill.symbol, fill.market, fill.time, firstCycle)
                            firstCycle = false
                            next.addOpen(fill, remainder, remainderFraction)
                            next.netQuantity = if (signed > 0) remainder else -remainder
                            builder = next
                        }
                    }
                }
                builder?.takeIf { abs(it.netQuantity) >= EPSILON }?.let { result += it.build() }
            }
        return result.sortedByDescending { it.endTime }
    }

    fun spotActivity(fills: List<BinanceJournalFill>): List<BinanceSpotActivityGroup> =
        fills.asSequence()
            .filter { it.market == "Spot" && it.quantity.isFinite() && it.quantity > 0.0 &&
                it.quoteAmount.isFinite() && it.quoteAmount >= 0.0 }
            .groupBy { it.symbol.uppercase() }
            .map { (symbol, rows) ->
                val ordered = rows.sortedBy { it.time }
                BinanceSpotActivityGroup(
                    symbol = symbol,
                    fills = ordered,
                    baseQuantityBought = ordered.filter { it.side.equals("BUY", true) }.sumOf { it.quantity },
                    baseQuantitySold = ordered.filter { it.side.equals("SELL", true) }.sumOf { it.quantity },
                    quoteVolume = ordered.sumOf { it.quoteAmount },
                    feeTotals = ordered.filter { it.fee.isFinite() && it.fee > 0.0 }
                        .groupBy { it.feeAsset.ifBlank { "Unknown" } }
                        .mapValues { (_, values) -> values.sumOf { it.fee } }
                )
            }
            .sortedByDescending { it.fills.maxOfOrNull(BinanceJournalFill::time) ?: 0L }

    private class CycleBuilder(
        val symbol: String,
        val market: String,
        start: Long,
        private val firstObservedCycle: Boolean
    ) {
        var netQuantity = 0.0
        private var openedQuantity = 0.0
        private var closedQuantity = 0.0
        private var entryNotional = 0.0
        private var exitNotional = 0.0
        private var realizedPnl = 0.0
        private var hasRealizedPnl = false
        private var missingClosePnl = false
        private var feeTotals = linkedMapOf<String, Double>()
        private val rows = mutableListOf<BinanceJournalFill>()
        private var firstTime = start
        private var lastTime = start
        private var sequence = 0

        fun addOpen(fill: BinanceJournalFill, quantity: Double, fraction: Double = 1.0) {
            openedQuantity += quantity
            entryNotional += quantity * fill.price
            addCommon(fill, fraction)
        }

        fun addClose(fill: BinanceJournalFill, quantity: Double, fraction: Double) {
            closedQuantity += quantity
            exitNotional += quantity * fill.price
            val exchangePnl = fill.realizedPnl
            if (exchangePnl != null && exchangePnl.isFinite()) {
                realizedPnl += exchangePnl
                hasRealizedPnl = true
            } else {
                missingClosePnl = true
            }
            addCommon(fill, fraction)
        }

        private fun addCommon(fill: BinanceJournalFill, fraction: Double) {
            if (rows.none { it.id == fill.id }) rows += fill
            firstTime = minOf(firstTime, fill.time)
            lastTime = maxOf(lastTime, fill.time)
            sequence++
            if (fill.fee.isFinite() && fill.fee > 0.0) {
                val asset = fill.feeAsset.ifBlank { "Unknown" }
                feeTotals[asset] = (feeTotals[asset] ?: 0.0) + fill.fee * fraction
            }
        }

        fun build(): BinancePositionCycle {
            val baseDirection = if (rows.firstOrNull()?.side.equals("SELL", true)) "SHORT" else "LONG"
            val direction = if (firstObservedCycle) "$baseDirection (inferred)" else baseDirection
            val safeOpened = openedQuantity.takeIf { it > EPSILON }
            val safeClosed = closedQuantity.takeIf { it > EPSILON }
            val id = "${symbol.uppercase()}|${firstTime}|${sequence}|${direction}"
            return BinancePositionCycle(
                key = id,
                symbol = symbol.uppercase(),
                market = market,
                direction = direction,
                firstObservedCycle = firstObservedCycle,
                startTime = firstTime,
                endTime = lastTime,
                openedQuantity = openedQuantity,
                closedQuantity = closedQuantity,
                averageEntryPrice = if (safeOpened != null) entryNotional / safeOpened else null,
                averageExitPrice = if (safeClosed != null) exitNotional / safeClosed else null,
                realizedPnl = if (hasRealizedPnl && !missingClosePnl) realizedPnl else null,
                feeTotals = feeTotals.toMap(),
                fillCount = rows.size,
                unclosedQuantity = abs(netQuantity),
                fills = rows.sortedBy { it.time }
            )
        }
    }

    private const val EPSILON = 1e-10
}
