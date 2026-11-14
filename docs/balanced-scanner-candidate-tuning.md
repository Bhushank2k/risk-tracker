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

The live scan fetches separate closed-candle series and routes them by role:

- Scalp: 1H context → 15M trend → 5M setup → 1M execution.
- Intraday: 4H context → 1H trend → 15M setup → 5M execution.
- Swing: 1D context → 4H trend → 1H setup → 15M execution.

The shared scanner now accepts all three modes, uses horizon-specific freshness and sequential setup/entry expiry windows, reports the actual setup/execution intervals, and calculates fee/funding cost assumptions using the setup interval. The exchange fetch layer already requests these intervals; this change connects those existing series to the scanner rather than relabeling H4/H1/M15/M5.

Historical replay remains supplementary and should be evaluated independently for each mode. Do not treat enabling the modes as proof of profitability or a guarantee of 2–5 signals each day; frequency depends on market conditions and all liquidity, funding, stop, volatility, freshness, and net reward/risk gates remain active.

## Validation

Run gradle testDebugUnitTest --no-daemon and gradle assembleDebug --no-daemon. Candidate frequency is market-dependent; 2–5 per day is a target to evaluate, not a guaranteed output or reason to bypass risk gates.