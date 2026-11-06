# Risk Tracker

Risk Tracker is a native Android companion for **honest, auditable crypto-futures risk tracking and behavioral guardrails**. It helps traders understand recorded exposure, review execution history, and plan risk. It does not place trades.

## Features

- Daily P&L with source-aware reconciliation and a manual balance ledger.
- Separate manual trade journal and Binance-imported execution/position journals.
- Read-only Binance sync, sync health, historical CSV import, audit and recovery.
- Per-trade analytics, daily performance insights and behavioral review.
- Optional, manual AI explanations for individual Binance futures setups (requires a separately deployed backend; disabled by default).
- Position sizing, leverage reference, Trade Risk Gate and loss-streak stress tests.
- Risk alerts, saved trade plans and post-trade reviews.
- Historical crypto transaction review and CSV/PDF reports.
- Local backup/restore and light/dark appearance settings.

## Build and test

Requirements: JDK 17, Android SDK API 36, Gradle 8.13.

```bash
gradle testDebugUnitTest --no-daemon
gradle assembleDebug --no-daemon
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same unit-test and debug-build commands for pull requests.

## Data trust

Source precedence, accounting separation, historical coverage and estimate limitations are documented in [Binance sync and coverage](docs/binance-sync-and-coverage.md).

## Security

Use a Binance API key with read-only permissions only. The app does not request trading or withdrawal access. Never put API secrets in source code, logs, exports, screenshots, or build artifacts.

## Documentation

- [Binance sync, coverage and data-trust caveats](docs/binance-sync-and-coverage.md)
- [Product roadmap and usability/reliability plan](docs/roadmap.md)
- [Design decisions](docs/design-notes.md)
- [Optional AI trade explanations and backend setup](docs/optional-ai-trade-analysis.md)
- [Google Sheets sync (experimental / unsupported)](docs/sheets-sync.md)

Older backup bundles remain importable. When an older bundle does not contain trade plans or review notes, importing it leaves the existing plans and reviews unchanged. Import validation, audit, and pre-import recovery behavior are retained.
