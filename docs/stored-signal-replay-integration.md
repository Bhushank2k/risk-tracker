# Stored signal replay integration

The Stored Signal Replay screen connects the local Confluence V4 plan snapshot history and bounded historical candle store to the deterministic `HistoricalSignalReplay` engine.

## How it works

- Loads up to 5,000 saved plan snapshots from app-private storage.
- Lets the user select a retained candle interval (1m, 5m, 15m, 1h, 4h or 1d).
- Joins each snapshot to the same-symbol candle series for the selected interval.
- Excludes candles whose open timestamp is at or before the snapshot capture timestamp. No pre-signal candle can influence the replay.
- Runs the existing conservative replay rules and aggregates win rate, net-R expectancy, profit factor, total net R, Long/Short and score-band statistics.
- Displays per-signal outcomes and timestamps in IST.

## Interpretation and limitations

A snapshot is only replayable after later candles have been collected. The screen reports signals still awaiting later candle history instead of inventing outcomes. It does not backfill data, fetch exchange history, place orders, or alter scanner scores.

The replay engine's existing default assumptions remain in effect: 50% TP1 realization, original stop for the remainder, stop-first same-candle ambiguity, 8 bps round-trip fees, 4 bps slippage and estimated 1 bps funding per 8 hours. These are research defaults, not a claim about an individual account's actual fees or fills. Metrics are not statistically reliable without sufficient signal count and date coverage.
