# Binance rate-limit and live market data

- Live USDⓈ-M Futures prices for tracked trade ideas and open positions use Binance's public mark-price WebSocket stream (`@markPrice@1s`); they do not poll `/ticker/price`.
- Trade-idea scans use a bounded REST snapshot: the 25 highest-volume eligible USDT perpetual pairs, with microstructure REST data limited to the first 10. Scan concurrency is limited to two symbols.
- All app Binance REST paths share a process-wide request pacer (minimum 250 ms between requests) and a circuit breaker. HTTP 418 honors Binance's `banned until` timestamp; HTTP 429 honors `Retry-After` and otherwise backs off for one minute. Requests fail fast while the circuit is open rather than repeatedly hitting the banned IP.
- Historical candles, account balances, order/position snapshots, and account history remain REST operations because they are snapshot/history data, not continuous live quotes.
- A WebSocket does not remove an existing Binance IP ban for REST. Wait until Binance's supplied expiry timestamp; WebSocket market streams may continue independently.
- The circuit-breaker expiry is persisted in app preferences and restored by the activity and every background sync runner, so force-closing or restarting the app does not immediately resume REST requests.
- The Premium Overview refreshes its positions and account-balance REST snapshots at most once every five minutes, and once on screen open. Live mark prices continue over the WebSocket between snapshots.

- Account-history sync uses bounded concurrency (four workers) for independent Spot symbols and statement categories. The shared REST pacer remains authoritative, so parallel work never bypasses the 250 ms request gap or circuit breaker.
- Automatic trade-journal sync stores a successful timestamp per market/symbol. Repeat syncs request only the interval since that symbol's last successful checkpoint, with a one-minute overlap; first-time symbols retain the rolling seven-day recovery window. Failed symbols do not advance their checkpoint.
- Spot FIFO P&L is still calculated after all fetched fills are combined and sorted chronologically; network concurrency does not change lot matching order.
- Account snapshot refresh fetches Spot and Futures balances concurrently and writes a new snapshot only after both requests succeed.
