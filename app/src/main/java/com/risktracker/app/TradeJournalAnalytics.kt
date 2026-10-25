package com.risktracker.app

/**
 * Conservative execution-level analytics. Spot P&L is only reported when the imported fills
 * contain enough FIFO inventory to cover a sale; non-quote fees are never silently converted.
 */
data class SpotRealizedResult(
    val symbol: String,
    val sellFillId: String,
    val time: Long,
    val quantity: Double,
    val grossPnlQuote: Double?,
    val netPnlQuote: Double?,
    val basisComplete: Boolean,
    val feeConversionNeeded: Boolean
)

data class ExchangeJournalSummary(
    val spotClosedSales: List<SpotRealizedResult>,
    val futuresGrossRealizedPnl: Double,
    val futuresKnownQuoteFees: Double,
    val futuresNetKnownQuotePnl: Double,
    val futuresRowsWithUnknownFeeConversion: Int
)

object TradeJournalAnalytics {
    private data class Lot(var quantity: Double, val unitCost: Double)

    private val quoteAssets = listOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "USDP", "BTC", "ETH", "BNB", "EUR", "TRY", "GBP", "BRL", "AUD", "BIDR", "RUB", "UAH", "DAI")

    fun quoteAsset(symbol: String): String? =
        quoteAssets.sortedByDescending { it.length }.firstOrNull { symbol.endsWith(it) && symbol.length > it.length }

    fun summarize(fills: List<BinanceJournalFill>): ExchangeJournalSummary {
        val lotsBySymbol = mutableMapOf<String, ArrayDeque<Lot>>()
        val sales = mutableListOf<SpotRealizedResult>()
        val ordered = fills.sortedWith(compareBy<BinanceJournalFill> { it.time }.thenBy { it.id })

        ordered.filter { it.market == "Spot" }.forEach { fill ->
            val quote = quoteAsset(fill.symbol)
            val lots = lotsBySymbol.getOrPut(fill.symbol) { ArrayDeque() }
            val base = quote?.let { fill.symbol.removeSuffix(it) }
            val feeInQuote = quote != null && fill.feeAsset.equals(quote, ignoreCase = true)
            val feeInBase = base != null && fill.feeAsset.equals(base, ignoreCase = true)
            val feeConvertible = fill.fee == 0.0 || feeInQuote || feeInBase

            if (fill.side.equals("BUY", true)) {
                val acquired = (fill.quantity - if (feeInBase) fill.fee else 0.0).coerceAtLeast(0.0)
                val totalCost = fill.quoteAmount + if (feeInQuote) fill.fee else 0.0
                if (acquired > 0.0 && totalCost >= 0.0) lots.addLast(Lot(acquired, totalCost / acquired))
            } else if (fill.side.equals("SELL", true)) {
                val disposed = fill.quantity + if (feeInBase) fill.fee else 0.0
                var remaining = disposed
                var basis = 0.0
                val epsilon = 1e-10
                while (remaining > epsilon && lots.isNotEmpty()) {
                    val lot = lots.first()
                    val used = minOf(remaining, lot.quantity)
                    basis += used * lot.unitCost
                    lot.quantity -= used
                    remaining -= used
                    if (lot.quantity <= epsilon) lots.removeFirst()
                }
                val complete = remaining <= maxOf(epsilon, disposed * 1e-8)
                val grossProceeds = fill.quoteAmount
                val grossPnl = if (complete) grossProceeds - basis else null
                val netPnl = if (complete && feeConvertible) grossProceeds - basis - if (feeInQuote) fill.fee else 0.0 else null
                sales += SpotRealizedResult(
                    symbol = fill.symbol, sellFillId = fill.id, time = fill.time,
                    quantity = fill.quantity, grossPnlQuote = grossPnl, netPnlQuote = netPnl,
                    basisComplete = complete, feeConversionNeeded = !feeConvertible
                )
            }
        }

        val futures = fills.filter { it.market == "USDⓈ-M Futures" && it.realizedPnl != null }
        val knownQuoteFees = futures.filter { it.feeAsset.equals("USDT", true) || it.feeAsset.equals("USDC", true) }
            .sumOf { it.fee }
        val unknownFeeRows = futures.count {
            it.fee != 0.0 && !it.feeAsset.equals("USDT", true) && !it.feeAsset.equals("USDC", true)
        }
        val gross = futures.sumOf { it.realizedPnl ?: 0.0 }
        return ExchangeJournalSummary(
            spotClosedSales = sales,
            futuresGrossRealizedPnl = gross,
            futuresKnownQuoteFees = knownQuoteFees,
            futuresNetKnownQuotePnl = gross - knownQuoteFees,
            futuresRowsWithUnknownFeeConversion = unknownFeeRows
        )
    }
}
