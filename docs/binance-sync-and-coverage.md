# Binance sync, historical coverage and data trust

## Read-only security

The Binance integration is read-only. It must not call order placement, cancellation, transfer or withdrawal endpoints. API credentials are stored locally using the existing credential store; do not log, export, hard-code or include secrets in builds.

## Automatic execution sync

The automatic execution journal records last-attempt and last-success timestamps, fetched and inserted counts, attempted and failed symbol counts, and the rolling-window bounds. Partial and failed syncs remain distinguishable and can be retried. The Overview links to the dedicated Binance Sync Health screen.

The automatic collector uses a rolling seven-day window and a bounded set of discovered/known symbols. A successful seven-day sync is not proof of complete account history. Missing dates can be legitimate periods with no activity, or missing source coverage; do not interpret missing records as zero P&L.

The five-year USDⓈ-M Futures trade-history backfill uses Binance's asynchronous trade archive endpoints. It divides the rolling five-year period into five contiguous requests of no more than one year each, persists each returned download ID, and allows the user to resume status checks and downloads after app restarts. Completed ZIP/CSV archives are parsed and merged into the shared execution journal using Binance trade IDs. A period is marked imported only after parsing and merging succeeds. Binance limits these archive requests to five per month; previously used quota, account retention, archive processing time and download-link expiry remain exchange-controlled. Failed or still-processing requests remain visible and can be checked again without requesting a duplicate archive.

This archive backfill covers USDⓈ-M Futures trades. Spot `/api/v3/myTrades` remains symbol-scoped; the app's automatic Spot discovery is based on currently held assets and cannot guarantee discovery of every pair whose position was fully closed years ago. Do not describe Spot coverage as complete account-wide history.

Execution fills and daily P&L are merged into on-device history rather than replaced by each recent refresh. Older saved rows remain available across app restarts and later syncs. The explicit historical API preview and Binance trade CSV import remain available as recovery paths. Repeated imports use Binance execution IDs where available and a one-to-one fallback match where needed, while preserving separate partial fills.

The Settings & data screen provides a confirmed manual clear action for saved Binance executions, CSV-imported trade history and synced daily P&L. It does not clear manual Daily P&L, manual journal trades, account statements or API credentials.

## Historical coverage limitations

The standard USDⓈ-M Futures income endpoint has limited historical availability. API pagination does not recover records outside Binance retention. Older executions and income records require supported Binance exports or user-provided CSV history. Export request quotas and download-link expiry are controlled by Binance; request only needed years and save exports promptly.

Spot FIFO discovery is limited to a bounded set of quote markets and assets currently discoverable from account holdings. It can omit fully closed Spot assets and is not an account-wide Spot P&L calculation. It is disabled in the UI by default and must be explicitly enabled as an incomplete estimate.

## Import validation and recovery

Historical CSV import validates required date/type/amount fields, row widths, timestamps, finite numeric amounts and selected-year boundaries. Invalid rows reject the import with a row-specific error. Exact duplicate rows are counted and skipped; otherwise-different transactions remain separate. The import audit reports included income rows by type, ignored rows, duplicate count, net imported amount and observed activity dates.

Before replacing a year's Futures values, the app stores a local pre-import snapshot. Restore remains available and restores the matching prior import audit. Spot results and other years are preserved. Do not weaken this validation, audit or snapshot/restore sequence during refactors.

## Accounting rules that must remain stable

- Internal transfers are not Futures trading income and are never merged into it.
- Futures income types (realized P&L, commission, funding) remain separately traceable and use their signed source amounts.
- For overlapping daily P&L sources, precedence is imported Binance P&L, then manual daily P&L, then estimated manual-journal P&L. The selected source is shown; overlapping values are not added.
- Balance charts continue to use the separate manual balance ledger.
- Fees and income remain in their original assets unless an explicit historical conversion is available. Never sum BTC, BNB, USDT, INR or other coins as if they were one currency.
- Missing realized P&L is unavailable, not zero. Spot FIFO is incomplete and must not be described as account-wide P&L.

## Review and reconciliation

Compare export periods, year, currency and income categories before treating totals as comparable. A matching year total is a total-level check, not proof of row-level completeness. Keep manual estimates visibly separate from exchange-reported values.
