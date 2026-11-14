# Reproducible market replay

## Offline dataset format
The offline importer accepts Binance USD-M Futures kline CSV exports in the standard 12-column archive format (header optional). It reads the first eight columns: open time, open, high, low, close, base volume, close time and quote volume. Timestamps are Unix milliseconds. Rows must be valid, chronological and non-overlapping; malformed rows are rejected rather than skipped. Import at least 60 candles from one symbol and one interval.

Keep the original downloaded CSV unchanged alongside the report. Record exchange, market (USD-M perpetual), symbol, interval, archive URL or export date, and download timestamp in the source label. Do not combine symbols or intervals in one replay.

## Walk-forward protocol
`MarketReplayRunner.run` defaults to a chronological 70/30 split, a 12-bar holding horizon, 8 bps fee and 5 bps slippage per side. Training setups are scored only before the split. Out-of-sample indicators may use pre-split candles for warm-up, but only signals at or after the split index count in the holdout. Entry is the next candle open; exit is the close after the configured horizon. Incomplete forward windows are excluded.

The report includes separate training and holdout statistics, exact split index, candle time bounds, cost assumptions and a SHA-256 fingerprint of canonicalized candle rows. Re-running the same dataset and parameters produces the same fingerprint and split. Fix parameters before inspecting holdout results; do not tune on the holdout and then report it as out-of-sample.

## Interpretation
The positive/non-positive return metric is the fraction of setups with positive/non-positive net return at the fixed horizon, not a calibrated probability. This is a single-symbol, single-interval breakout study. It is not a portfolio backtest and does not model funding, liquidation, market impact or overlapping-position capital constraints. It does not establish profitability. Report sample counts and both periods; if a period has no setups, return statistics are unavailable, not zero.

## Data provenance and safety
Use completed candles and preserve the original archive. The importer is offline and makes no API calls. It needs no exchange credentials and cannot place orders. Historical data supplied by a user or downloaded from an exchange is untrusted input and is validated before replay.

The Android Market Activity Lab exposes this importer through the “Import Binance kline CSV” action. Select the intended symbol and interval before importing; ZIP archives must be extracted to CSV first. The UI runs the report on-device and displays both training and holdout counts, holdout net return, excursion range and dataset fingerprint.
