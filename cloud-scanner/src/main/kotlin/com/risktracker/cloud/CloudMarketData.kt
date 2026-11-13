package com.risktracker.cloud

import com.risktracker.app.V2Microstructure

/** Mirrors BinanceFuturesStream's five-level notional and imbalance calculation. */
fun buildMarketMicrostructure(
    bestBid: Double?, bestAsk: Double?,
    bids: List<Pair<Double, Double>>?, asks: List<Pair<Double, Double>>?,
    fundingRate: Double?
): V2Microstructure {
    val bid = bestBid?.takeIf { it.isFinite() && it > 0.0 }
    val ask = bestAsk?.takeIf { it.isFinite() && it > 0.0 }
    val spread = if (bid != null && ask != null && ask > bid) {
        ((ask - bid) / ((ask + bid) / 2.0) * 10_000.0).takeIf { it.isFinite() }
    } else null
    fun total(rows: List<Pair<Double, Double>>?): Double? {
        if (rows == null || rows.size < 5) return null
        if (rows.take(5).any { (price, qty) -> !price.isFinite() || !qty.isFinite() || price <= 0.0 || qty <= 0.0 }) return null
        return rows.take(5).sumOf { it.first * it.second }.takeIf { it.isFinite() && it > 0.0 }
    }
    val bidDepth = total(bids)
    val askDepth = total(asks)
    val imbalance = if (bidDepth != null && askDepth != null && bidDepth + askDepth > 0.0) {
        ((bidDepth - askDepth) / (bidDepth + askDepth)).takeIf { it.isFinite() }
    } else null
    return V2Microstructure(spread, imbalance, fundingRate?.takeIf { it.isFinite() }, null, bidDepth, askDepth)
}
