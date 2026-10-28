# Risk Tracker — Android

A native Android app built with Kotlin and Jetpack Compose for crypto-futures risk tracking.

## Features

- Daily P&L tracking, balance chart, and CSV export
- Native trade journal for closed long/short trades
- Trade analytics: net P&L after fees, win rate, profit factor, average wins/losses, and maximum drawdown
- Local on-device trade storage and CSV sharing
- Position sizing and risk calculator
- Leverage/liquidation reference table
- Optional Google Sheets sync for Daily P&L
- Premium dashboard with hybrid bottom navigation and a full destination menu
- Binance Sync Health screen with last-attempt/last-success timestamps, fetched/inserted counts, failed-symbol count, and explicit rolling-window coverage caveats
- Target planner for balance goals, daily net-profit pace, progress and estimated completion time
- Trade Risk Gate for advisory pre-trade risk checks and locally saved trade plans
- Configurable in-app risk alerts for daily loss limits, consecutive-loss streaks, recorded drawdown thresholds, and stale Binance execution-history sync
- Behavioral intelligence by strategy and mistake tags, with sample-size and data-quality caveats
- Risk Scenario Lab for consecutive-loss drawdown stress tests and seeded historical-outcome simulations
- Post-trade reviews for plan adherence, setup, pre-trade state and lessons learned
- Performance insights: net daily P&L, win rate, best/worst day, average daily P&L, and peak-to-trough drawdown
- Custom light and dark palettes with System / Light / Dark appearance selection
- Light/dark theme support

## Build and test

Requirements: JDK 17, Android SDK (API 36), and Gradle 8.13 or compatible.

```bash
gradle testDebugUnitTest --no-daemon
gradle assembleDebug --no-daemon
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the unit tests and builds the APK for pushes and pull requests.

The Binance statements screen now summarizes recent completed deposits and withdrawals separately by asset, breaks down saved Futures income by income type and asset, filters the stored record list by category, and reports which statement categories failed during a partial sync instead of silently implying every category succeeded. Amounts are deliberately not combined across different coins or presented as USD without historical conversion prices. This recent statement window is useful for review, but is not sufficient to reconstruct lifetime starting capital; use complete historical exports and account reconciliation for that calculation. The Capital reconciliation card calculates completed deposits minus completed withdrawals independently for each asset, shows the date span and stored-record count, and explicitly refuses to label this partial archive as lifetime starting capital. Internal transfers and Futures income remain separate to avoid double-counting; a verified historical export is still required for a lifetime figure.

## Binance sync and historical coverage

The app includes an optional read-only Binance API integration. Spot realized P&L is estimated using FIFO matching for the symbols discovered by the current account scan. USDⓈ-M Futures P&L imports recent `REALIZED_PNL`, `COMMISSION`, and `FUNDING_FEE` income records. The standard Futures income endpoint has a limited history window, so API sync alone cannot recover every record back to 2018. The automatic execution journal now records sync attempts, the last fully successful sync, fetched/inserted counts, failed symbol requests, and the rolling seven-day window. Partial or failed background syncs request a WorkManager retry, and the Overview links to a dedicated Sync Health screen. A successful seven-day sync is not proof of complete account history; use historical exports and reconciliation for older periods.

The Binance sync screen supports year-wise viewing of saved results, requesting Binance's official asynchronous Futures transaction-history export for the selected year, checking the saved export request ID for a download link, and importing a year-scoped income-history CSV. Before saving an import, the CSV parser validates required date/type/amount fields, row column counts, timestamps, finite numeric amounts, and selected-year boundaries; invalid rows reject the entire import with a row-specific error. Exact duplicate rows (all trimmed fields identical) are skipped and counted, while otherwise-identical transactions with differing fields remain separate. After import, an audit summary reports included income rows by type, ignored other income rows, exact duplicate rows skipped, net imported amount, and the first/last activity dates found in the file. The latest audit report is saved locally per year and reappears when that year is selected again; it describes the most recent import for that year, not necessarily the current contents of a file outside the app. The year-wise reconciliation section can compare the imported Futures total with a separately obtained Binance source total, stores that source total locally for each year, and flags a mismatch greater than 0.01 USDT. Use totals from the same year, currency and income categories; a matching total is only a total-level check, not proof of row-level correctness or export completeness. These dates are not proof of complete coverage: transaction history may have legitimate gaps on days with no income, so compare the export period and available Binance statements rather than inferring completeness from daily row counts. Each export request can cover at most one year and Binance limits requests to five per month shared with the website, so request only the years you need; the request ID is saved locally to avoid duplicate requests. The app opens Binance's temporary download link in the browser; save and extract the export, then import its CSV. Use a complete export for the selected year: the app first shows a preview of daily totals, net total, row counts and observed dates, plus a comparison against saved Futures values showing the net change and new, changed, or removed dates (up to 10 date-level differences). Confirming the import saves a local pre-import snapshot of that year's Futures values, then replaces that year's saved Futures totals while preserving Spot results and other years. A restore control asks for confirmation before reinstating the saved pre-import Futures snapshot and its matching prior import audit (or clearing the audit if none existed); the recovery snapshot remains available until a later confirmed import for that year replaces it. Years without recoverable export/API data must be treated as incomplete, not as zero P&L. The Daily P&L screen now shows a source-labelled combined daily view: Binance-only dates appear alongside manual dates, and manual entries take precedence when both sources have the same date to avoid double-counting. Balance charts and balance-based metrics continue to use the manual balance ledger. The local imported ledger remains separately stored from manually entered Daily P&L records.

Trade-journal P&L is estimated from entered entry/exit prices, quantity, side, and fees. It does not include funding, slippage, or exchange-specific contract multipliers. Journal entries are stored locally and are not synchronized with Google Sheets. The app does not execute trades.

Use a read-only Binance API key with no trading or withdrawal permissions. Never put exchange API secrets in source code, logs, exports, or client-side builds.

## Product roadmap

See [docs/product-roadmap.md](docs/product-roadmap.md) for the staged feature roadmap and engineering guardrails. See [docs/usability-and-reliability-plan.md](docs/usability-and-reliability-plan.md) for usability, data-trust, backup, risk-alert, and release-quality priorities. The new features are under review in [pull request #45](https://github.com/Bhushank2k/risk-tracker/pull/45). Trade plans and review notes use separate local storage and are included in newly exported backup bundles. Older backup files remain importable; because they predate these fields, importing an older bundle leaves existing trade plans and reviews unchanged.


Risk alerts can optionally post Android notifications in the background. Enable **Background notifications** on the Risk alerts screen and grant notification permission when Android asks. WorkManager checks about every 15 minutes (Android may delay background work); a notification is sent when an alert first becomes active, not repeatedly while it stays active. Notifications are advisory and are not real-time, broker-enforced, or guaranteed to run on schedule. Daily P&L, drawdown, and loss-streak checks use locally saved data; stale-sync alerts require saved Binance credentials.
