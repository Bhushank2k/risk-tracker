# Historical Signal Replay Engine

This module replays immutable scanner signal snapshots against chronological candles without exchange access or order execution.

## Current assumptions

- Signal is evaluated only against candles with timestamps strictly later than the signal timestamp.
- For signals not already marked entry-triggered, entry occurs when the candle range touches the entry price.
- If entry and stop/target levels are all touched within one candle, resolution is conservative: stop first.
- TP1 realizes a configurable fraction (default 50%). The remainder keeps the original stop; this engine does not assume an automatic breakeven move.
- When the remaining position reaches TP2, weighted realized R is calculated from both target exits.
- If the replay window expires, remaining quantity is marked to the last candle close (not an exchange fill).
- Fees, slippage and estimated funding are deducted using the shared net-R helper.
- Replay is advisory analysis only. It does not place, modify or cancel trades.

## Output

Per signal: entry status, outcome, entry/exit timestamps, bars held, gross R, net R and resolution notes.

Aggregate: win/loss/breakeven counts, win rate, net-R expectancy, profit factor, total net R, average win/loss, Long/Short summaries, and score-band summaries (0–59, 60–69, 70–79, 80–89, 90–100).

## Limitations / next steps

This is a pure replay core, not yet wired to scanner history persistence or a dashboard. Before using performance figures to tune scanner scores, connect durable signal snapshots and candle history, ensure timeframe/market data alignment, then validate with walk-forward splits and realistic exchange fee tiers, slippage, funding, partial-fill and gap assumptions. Sample size and date range should accompany every metric.
