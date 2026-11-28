# Historical market candle retention

Completed Binance USD-M futures candles are retained locally for symbols that actually emit trade ideas or Advanced SMC signals, plus BTCUSDT benchmark context. Supported intervals are 1m, 5m, 15m, 1h, 4h and 1d.

- Candles are immutable OHLCV records keyed by symbol, interval and open time.
- Incoming rows deduplicate by open time and can correct a previously stored row.
- Invalid geometry, unsupported intervals and non-finite values are rejected.
- Retention is bounded to 1,000 candles per series and 300 series total.
- The store uses app-private SharedPreferences; it makes no network calls and has no order/execution side effects.

The scanner only has the latest fetched window in memory. This change persists that window for symbols associated with emitted signals; it does not backfill older history, guarantee continuous candle coverage while the app is closed, or claim a complete market-wide archive. Replay integration must verify candle coverage around each signal before computing outcomes.
