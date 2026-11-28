# Confluence V4 plan snapshot capture

Confluence V4 plans are normalized into `TradeIdea` values before the scanner cache is persisted. This integration captures those plan records at the cache persistence boundary, covering the primary V2TradePlan-to-TradeIdea pipeline without changing scoring, ranking, eligibility, notifications, or execution.

Each immutable snapshot records symbol, side, horizon, setup, capture timestamp, score/status, entry/stop/targets, entry-triggered state, warning/reasons, and the available relative-volume, funding, spread, book-imbalance, depth, and open-interest values. The local SharedPreferences history is bounded to 5,000 rows and deduplicates repeated writes for the same symbol/side/horizon/setup/capture timestamp.

## Timestamp and context limitations

The current `TradeIdea` model does not expose the exact source candle close timestamp or raw ADX, ATR%, and BTC benchmark regime from the V2TradePlan evaluation. The snapshot therefore uses the scan-cache `updatedAt` as its capture/evaluation timestamp and leaves unavailable fields absent. It does not substitute 24-hour volatility for ATR, local 4H bias for BTC bias, or parse display text to fabricate indicator values. A later scanner-model change should carry source candle timestamps and these raw context values through the plan boundary.

Snapshots are local research records only. They do not submit, modify, or cancel orders. They are not yet joined to historical candles and are not automatically replayed.
