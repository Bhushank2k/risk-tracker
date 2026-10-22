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

## Binance sync and historical coverage

The app includes an optional read-only Binance API integration. Spot realized P&L is estimated using FIFO matching for the symbols discovered by the current account scan. USDⓈ-M Futures P&L imports recent `REALIZED_PNL`, `COMMISSION`, and `FUNDING_FEE` income records. The standard Futures income endpoint has a limited history window, so API sync alone cannot recover every record back to 2018.

The Binance sync screen supports year-wise viewing of saved results and importing a year-scoped Binance USDⓈ-M Futures income-history CSV export. Use a complete export for the selected year: importing it replaces that year's saved Futures totals while preserving Spot results and other years. Years without recoverable export/API data must be treated as incomplete, not as zero P&L. The local daily summary remains separate from manually entered Daily P&L records.

Trade-journal P&L is estimated from entered entry/exit prices, quantity, side, and fees. It does not include funding, slippage, or exchange-specific contract multipliers. Journal entries are stored locally and are not synchronized with Google Sheets. The app does not execute trades.

Use a read-only Binance API key with no trading or withdrawal permissions. Never put exchange API secrets in source code, logs, exports, or client-side builds.
