# Binance rate-limit and live market data

- Live USDⓈ-M Futures prices for tracked trade ideas and open positions use Binance's public mark-price WebSocket stream (`@markPrice@1s`); they do not poll `/ticker/price`.
- Trade-idea scans use a bounded REST snapshot: the 25 highest-volume eligible USDT perpetual pairs, with microstructure REST data limited to the first 10. Scan concurrency is limited to two symbols.
- All app Binance REST paths share a process-wide request pacer (minimum 250 ms between requests) and a circuit breaker. HTTP 418 honors Binance's `banned until` timestamp; HTTP 429 honors `Retry-After` and otherwise backs off for one minute. Requests fail fast while the circuit is open rather than repeatedly hitting the banned IP.
- Historical candles, account balances, order/position snapshots, and account history remain REST operations because they are snapshot/history data, not continuous live quotes.
- A WebSocket does not remove an existing Binance IP ban for REST. Wait until Binance's supplied expiry timestamp; WebSocket market streams may continue independently.
- The guard is process-local. A process restart resets its in-memory circuit state, so do not repeatedly relaunch the app during an active ban.
