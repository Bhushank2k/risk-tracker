# Smart Money Confluence — Phase 1 (Structure & Liquidity)

This document describes the Phase 1 implementation in `scanner-core`.

## Scope

Phase 1 provides causal OHLCV primitives for confirmed swing pivots, market-structure bias, BOS/CHoCH events, equal-high/equal-low liquidity pools, and wick-and-reclaim liquidity sweeps. It does not claim to identify actual institutional orders.

## Candle and confirmation rules

- Inputs are `V2Candle` rows with finite OHLCV values and valid high/low/open/close geometry.
- Swing defaults are two bars left and two bars right. A pivot is returned only after all right-side confirmation candles are present.
- BOS/CHoCH requires the latest completed candle to close beyond the most recently confirmed relevant swing.
- Prior structure bias is based on the last two confirmed swing highs and lows: HH+HL is bullish, LH+LL bearish, otherwise neutral.
- A break against a non-neutral prior bias is CHoCH; a break with or without neutral prior bias is BOS.
- Optional minimum break distance is ATR-scaled (default 0.05 ATR).

## Liquidity rules

- Equal-high/equal-low pools require at least two confirmed pivots within a configurable ATR tolerance (default 0.10 ATR).
- A latest-bar sweep is measured against the prior lookback range, excluding the sweep candle itself.
- A sweep requires a wick beyond the prior level and a close back through it; wick-only continuation is not a sweep.
- The engine returns SELL_SIDE for a low sweep (potential long-side liquidity event) and BUY_SIDE for a high sweep (potential short-side event).

## Integration and safety

The existing Daily Trade Ideas SMC score remains an advisory bonus. Phase 1 does not relax or bypass existing spread/depth, funding, entry confirmation, stop geometry, reward/risk, freshness, volume, or Trade Risk Gate checks. No exchange orders are submitted.

## Files

- `scanner-core/src/main/kotlin/com/risktracker/app/SmcStructureEngine.kt`
- `scanner-core/src/test/kotlin/com/risktracker/app/SmcStructureEngineTest.kt`

Run tests with `./gradlew :scanner-core:test`.
