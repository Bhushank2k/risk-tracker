# Market Activity Lab methodology

## Data source and trust
The first implementation uses Binance USD-M Futures public endpoints only: `/fapi/v1/klines` for OHLCV and `/fapi/v1/ticker/24hr` for current ticker context. No API key, account scope, trade permission or withdrawal permission is requested. Candle values are filtered to completed candles. The candle cache is in-memory for 60 seconds; failures are surfaced rather than filled with synthetic values. Ticker context is also cached for 60 seconds. This is a fixed starter universe (BTC, ETH, BNB, SOL, XRP, DOGE, ADA and LINK perpetual contracts), not an all-symbol market-wide scanner.

## Indicators
EMA uses the standard recursive smoothing factor. RSI uses Wilder smoothing over 14 changes. MACD uses 12/26 EMAs and a 9-period EMA signal. Bollinger Bands use a 20-candle simple mean and two population standard deviations. ATR uses a 14-candle true-range simple average. VWAP is the volume-weighted typical price over the latest 20 candles. Relative volume divides the latest completed candle's base volume by the preceding 20-candle average. Historical volatility is the standard deviation of the latest 20 log returns, expressed as a per-bar percentage (not annualized).

## Activity score
The 0–100 score sums seven capped rule components: relative volume (20), ATR expansion (15), range compression (15), Bollinger behavior (15), EMA alignment (15), MACD momentum (10), and RSI confirmation (10). It is a transparent research heuristic, not a probability, confidence interval or expected return. The score may be incomplete when history is insufficient; unavailable indicators are displayed as unavailable and do not receive points.

WATCH requires a narrow preceding 20-candle range plus relative-volume expansion, Bollinger contraction or low ATR relative to its recent baseline. CONFIRMED HISTORICAL BREAKOUT requires the latest completed candle to close beyond the preceding 20-candle high/low with the configured breakout relative-volume threshold (default 1.2x). Direction is based on the breakout candle. No forward candle is used to classify the latest event. Range-compression, WATCH-volume and breakout-volume thresholds can be adjusted in the screen.

## Historical replay caveats
Replay scans completed historical prefixes and enters at the next candle open, then exits at the close of a fixed 12-candle horizon. The current implementation deducts fixed fee and slippage assumptions of 8 and 5 basis points per side. The displayed false-positive rate means the fraction of replayed setups with non-positive net return at this horizon; it is not a statistical false discovery rate. Maximum adverse/favorable excursion is the worst/best intraperiod path relative to entry. The current screen uses the most recent returned candle window, does not yet provide chronological train/test partitioning, per-timeframe comparison, funding-cost modelling or a user-configurable cost model. Treat results as exploratory until those controls and larger samples are added.

## Persistence and lifecycle
Scanner symbols (up to 40), range-compression threshold, WATCH/breakout relative-volume thresholds and minimum display score are saved locally with SharedPreferences. Setup transitions are persisted per symbol/timeframe, and a bounded 100-event in-app history records WATCH, confirmed-breakout and invalidation transitions. A WATCH setup is invalidated when a later completed candle closes beyond its original opposite range boundary; a confirmed breakout is invalidated when price closes back inside the original breakout range. This is local in-app history only: there are no background scans or push notifications, and events are generated when the user refreshes or opens the scanner.

## Remaining limitations
Historical consolidation catalogue including failed-breakout cohorts, chronological train/test separation, and full market-condition stratification remain follow-up work. Existing Risk Tracker journals, account data and risk controls are not modified.
