# Usability and reliability plan

This plan prioritizes trustworthy numbers and a short daily workflow over adding more top-level screens.

## Delivered in this branch

- Configurable in-app risk alerts for daily loss-limit breaches, consecutive-loss streaks, recorded peak drawdown, and stale Binance sync, with locally saved opt-in rules and threshold validation.
- Unit tests for alert threshold crossings, unknown P&L, disconnected exchange state, and stale-sync conditions.
- Dedicated Binance Sync Health screen, reachable from Tools and the Overview dashboard.
- Persisted last-attempt and last-success timestamps, rolling-window bounds, fetched and inserted execution counts, and failed-symbol request counts.
- Distinct status for a complete rolling-window sync, partial sync, and failed sync.
- Background worker retries after partial or failed attempts.
- Unit tests for sync-state classification.
- Clear UI and README language explaining that a successful seven-day sync does not prove complete historical coverage.

## Next implementation priorities

### 1. Ledger trust

- Migrate SharedPreferences-backed records to Room with a versioned, tested migration path; retain stable exchange-scoped IDs and indexed timestamps.
- Track per-market/per-symbol coverage ranges and import batches.
- Reconcile realized P&L, commissions, funding, fee-asset conversions, Spot cost basis, and manual estimates without double-counting.
- Show stale data, unknown fee conversions, incomplete cost basis, and unreconciled source totals as explicit warnings.
- Never interpret missing history as zero profit.

### 2. Workflow usability

- Keep Overview, Journal, Insights, and Tools as the four primary destinations.
- Group advanced features by task and add contextual shortcuts from relevant screens.
- Add journal search and filters by date, symbol, side, exchange, setup, and result.
- Keep the Overview focused on today's P&L, account snapshot timestamp, open risk, sync health, and the next action.

### 3. Risk management

- Extend the new in-app alerts with optional Android notifications and consecutive-loss checks, with clear notification-permission controls.
- Distinguish realized P&L, unrealized P&L, equity, notional exposure, and margin.
- Label liquidation calculations as estimates unless sourced from the exchange.

### 4. Backup and recovery

- Validate backup files before applying changes.
- Show import preview, counts, source ranges, duplicates, and changed records.
- Preserve a recoverable snapshot before destructive imports.
- Document local-only data and offer encrypted export/backup options.

### 5. Quality gates

- Run unit tests and Android debug builds on every pull request.
- Test offline mode, rate limits, partial API failures, duplicate imports, large ledgers, and app restarts.
- Check TalkBack, contrast, touch target size, light/dark themes, and small-screen layouts.
- Verify that no exchange credentials are logged or included in exported files.

## Scope and limitations

The current automatic collector still uses a rolling seven-day window and a bounded set of discovered/known symbols. It cannot guarantee that every historical execution is imported. Historical exports/imports and reconciliation remain necessary for complete long-term records. Multi-exchange support should only be considered complete when each adapter has working history sync, retry/progress reporting, tests, and reconciliation behavior.
