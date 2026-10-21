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
- Light/dark theme support

## Build and test

Requirements: JDK 17, Android SDK (API 36), and Gradle 8.13 or compatible.

```bash
gradle testDebugUnitTest --no-daemon
gradle assembleDebug --no-daemon
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the unit tests and builds the APK for pushes and pull requests.

## Trade journal calculation notes

P&L is estimated from entered entry/exit prices, quantity, side, and fees. It does not include funding, slippage, or exchange-specific contract multipliers. Journal entries are stored locally on the Android device and are not synchronized with Google Sheets. No exchange API is connected and the app does not execute trades.

Never put exchange API secrets in source code or client-side builds.
