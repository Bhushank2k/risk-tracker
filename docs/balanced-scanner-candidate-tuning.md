# Balanced scanner candidate tuning

This branch reduces candidate-generation friction without removing the existing hard risk checks.

## Changes in this branch

- Pullback participation accepts a reclaim candle at 0.90x of the rolling volume baseline, provided the preceding candle was not already unusually high-volume and reclaim participation does not collapse versus the previous candle.
- Breakout/retest discovery lowers its historical breakout participation threshold from 1.50x to 1.25x.
- The execution-volume floor is 1.20x for breakout/retest and 0.90x for other setup families.
- READY score floor is 70/100 instead of 75/100.
- Sequential readiness now requires a fresh, correctly ordered M15 setup and M5 entry. H4 bias and H1 structure remain independently calculated and contribute to context/scoring; they no longer need to appear as a perfectly ordered four-event lifecycle.
- Replay volume assumptions are aligned with the updated production thresholds.

## Safety gates intentionally retained

Liquidity/spread/depth, funding acceptability, stop geometry, volatility bounds, non-extended entry, completed-candle freshness, and minimum 2R net target remain hard requirements. These are not relaxed to manufacture a daily signal quota.

## Timeframe scope

The current live trade-idea data contract still supplies H4/H1/M15/M5 candles. The existing Scalp and Swing labels do not have dedicated candle-series mappings and isTradeIdeaHorizonSupported intentionally accepts only Intraday. This branch does not claim to implement true all-timeframe scanning. That requires separate candle fetch mappings and per-horizon execution/holding assumptions (for example 1D/4H/1H/15M for swing and 1H/15M/5M/1M for scalp), plus dedicated replay validation. Keep unsupported horizons blocked until that work is complete.

## Validation

Run gradle testDebugUnitTest --no-daemon and gradle assembleDebug --no-daemon. Candidate frequency is market-dependent; 2–5 per day is a target to evaluate, not a guaranteed output or reason to bypass risk gates.