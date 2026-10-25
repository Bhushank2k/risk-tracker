package com.risktracker.app

/**
 * Conservative execution-level analytics. Spot P&L is only reported when the imported fills
 * contain enough FIFO inventory to cover a sale; non-quote fees are never silently converted.
 */
data class SpotRealizedResult(
    val symbol: String,
    val sellFillId: String,
    val orderId: String,
    val time: Long,
    val quantity: Double,
    val grossPnlQuote: Double?,
    val netPnlQuote: Double?,
    val basisComplete: Boolean,
    val feeConversionNeeded: Boolean
)

data class ExchangeJournalSummary(
    val spotClosedSales: List<SpotRealizedResult>,
    /** Values are grouped by quote asset; do not add unlike currencies together. */
    val futuresPnlByQuoteAsset: Map<String, Pair<Double, Double>>,
    val futuresRowsWithUnknownFeeConversion: Int
)

object TradeJournalAnalytics {
    private data class Lot(var quantity: Double, val unitCost: Double, val feeConversionNeeded: Boolean)


    private val quoteAssets = listOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "USDP", "BTC", "ETH", "BNB", "EUR", "TRY", "GBP", "BRL", "AUD", "BIDR", "RUB", "UAH", "DAI")

    fun quoteAsset(symbol: String): String? =
        quoteAssets.sortedByDescending { it.length }.firstOrNull { symbol.endsWith(it) && symbol.length > it.length }

    fun summarize(fills: List<BinanceJournalFill>): ExchangeJournalSummary {
        val lotsBySymbol = mutableMapOf<String, ArrayDeque<Lot>>()
        val sales = mutableListOf<SpotRealizedResult>()
        val invalidBasisSymbols = mutableSetOf<String>()
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
                if (acquired > 0.0 && totalCost >= 0.0) lots.addLast(Lot(acquired, totalCost / acquired, feeConversionNeeded = !feeConvertible))
            } else if (fill.side.equals("SELL", true)) {
                val disposed = fill.quantity + if (feeInBase) fill.fee else 0.0
                var remaining = disposed
                var basis = 0.0
                var consumedLotHasUnknownFee = false
                val epsilon = 1e-10
                val alreadyInvalid = fill.symbol in invalidBasisSymbols
                if (!alreadyInvalid) {
                    while (remaining > epsilon && lots.isNotEmpty()) {
                        val lot = lots.first()
                        val used = minOf(remaining, lot.quantity)
                        basis += used * lot.unitCost
                        consumedLotHasUnknownFee = consumedLotHasUnknownFee || lot.feeConversionNeeded
                        lot.quantity -= used
                        remaining -= used
                        if (lot.quantity <= epsilon) lots.removeFirst()
                    }
                }
                val covered = remaining <= maxOf(epsilon, disposed * 1e-8)
                val complete = covered && !alreadyInvalid
                if (!complete) invalidBasisSymbols += fill.symbol
                val grossProceeds = fill.quoteAmount
                val grossPnl = if (complete) grossProceeds - basis else null
                val needsFeeConversion = !feeConvertible || consumedLotHasUnknownFee
                val netPnl = if (complete && !needsFeeConversion) grossProceeds - basis - if (feeInQuote) fill.fee else 0.0 else null
                sales += SpotRealizedResult(
                    symbol = fill.symbol, sellFillId = fill.id, orderId = fill.orderId, time = fill.time,
                    quantity = fill.quantity, grossPnlQuote = grossPnl, netPnlQuote = netPnl,
                    basisComplete = complete, feeConversionNeeded = needsFeeConversion
                )
            }
        }

        val futures = fills.filter { it.market == "USDⓈ-M Futures" && it.realizedPnl != null }
        val unknownFeeRows = futures.count { fill ->
            val quote = quoteAsset(fill.symbol)
            fill.fee != 0.0 && (quote == null || !fill.feeAsset.equals(quote, true))
        }
        val pnlByQuote = futures.groupBy { quoteAsset(it.symbol) ?: "Unknown quote" }.mapValues { (_, rows) ->
            val gross = rows.sumOf { it.realizedPnl ?: 0.0 }
            val knownFees = rows.filter { row ->
                val quote = quoteAsset(row.symbol)
                quote != null && row.feeAsset.equals(quote, true)
            }.sumOf { it.fee }
            gross to knownFees
        }
        // Collapse partial executions from the same Spot order into one journal-level sale.
        val groupedSales = sales.groupBy { sale ->
            if (sale.orderId.isNotBlank()) sale.symbol + ":" + sale.orderId else sale.sellFillId
        }.values.map { parts ->
            val first = parts.first()
            val basisComplete = parts.all { it.basisComplete }
            val feeConversionNeeded = parts.any { it.feeConversionNeeded }
            SpotRealizedResult(
                symbol = first.symbol,
                sellFillId = first.sellFillId,
                orderId = first.orderId,
                time = parts.maxOf { it.time },
                quantity = parts.sumOf { it.quantity },
                grossPnlQuote = if (basisComplete) parts.sumOf { it.grossPnlQuote ?: 0.0 } else null,
                netPnlQuote = if (basisComplete && !feeConversionNeeded) parts.sumOf { it.netPnlQuote ?: 0.0 } else null,
                basisComplete = basisComplete,
                feeConversionNeeded = feeConversionNeeded
            )
        }.sortedByDescending { it.time }
        return ExchangeJournalSummary(
            spotClosedSales = groupedSales,
            futuresPnlByQuoteAsset = pnlByQuote,
            futuresRowsWithUnknownFeeConversion = unknownFeeRows
        )
    }
}
