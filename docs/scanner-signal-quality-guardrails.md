# Scanner signal-quality guardrails

This PR adds conservative confidence caps to the existing transparent Confluence V4 score. It does not change mandatory liquidity, funding, stop, freshness, volatility, entry-confirmation, or reward/risk gates, and it does not create or submit orders.

## Score caps

- Missing BTC regime/impulse context: maximum 82.
- Missing ADX/DI context: maximum 78.
- BTC direction is a headwind for the signal: maximum 75 (context warning, not an independent hard veto).
- ATR expansion above 1.5x baseline: maximum 78.
- ATR expansion above 1.8x: maximum 60 (existing volatility gate already blocks readiness).
- Missing/invalid ATR comparison: maximum 70.

The tightest applicable cap wins. The scanner exposes a reason in its signal rationale whenever a cap actually lowers the raw score. Caps cannot raise scores and cannot bypass hard gates. The thresholds are initial conservative guardrails, not statistically calibrated probabilities; evaluate them through out-of-sample replay before treating scores as predictive.
