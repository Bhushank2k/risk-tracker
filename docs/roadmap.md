# Roadmap

This document combines the former product roadmap and usability/reliability plan. Items are proposals unless explicitly marked delivered; checklist status must not be inferred from the existence of a screen alone.

## Product roadmap

1. Trade Risk Gate and locally saved plans.
2. Behavioral insights with sample-size and source caveats.
3. Deterministic Risk Scenario Lab and consecutive-loss stress testing.
4. Post-trade reviews and plan-adherence tracking.
5. Ledger provenance, reconciliation and historical coverage.
6. Optional AI summaries only after privacy, source quality and completeness checks are established. The `feat/ai-trading-intelligence` implementation adds opt-in, on-demand OpenAI journal summaries, behavioral explanations, weekly reviews and risk-discipline observations; see [AI Trading Intelligence](ai-trading-intelligence.md).

Engineering guardrails: deterministic calculations with unit tests; no double-counting of imported and estimated results; missing coverage is not zero; no order execution or withdrawal permissions; preserve backup compatibility; label estimates and assumptions.

## Usability and reliability plan

### Delivered capabilities to preserve

- Configurable alerts for daily loss limits, consecutive-loss streaks, recorded drawdown and stale Binance sync.
- Dedicated Binance Sync Health with last-attempt/last-success timestamps, rolling-window bounds, fetched/inserted counts, failed-symbol counts, partial/failure states and retry behavior.
- Source-aware daily P&L reconciliation and historical import audit/recovery.
- Separate local storage for trade plans and post-trade reviews.

### Ledger trust

- Keep stable exchange-scoped IDs and source provenance.
- Reconcile realized P&L, commissions, funding, fee-asset conversions, Spot cost basis and manual estimates without double-counting.
- Show stale data, unknown fee conversions, incomplete cost basis and unreconciled source totals as warnings.
- Never interpret missing history as zero profit.

### Workflow usability

- Keep four primary destinations in bottom navigation; expose all specialist destinations in one overflow menu.
- Keep Overview focused on equity, daily loss budget, current exposure and sync health.
- Make journal search/filtering and source distinctions clear.
- Consolidate duplicate analytics destinations and compute metrics in a shared domain layer.

### Risk management

- Distinguish realized P&L, unrealized P&L, equity, notional exposure and margin.
- Label liquidation calculations as estimates unless sourced from the exchange.
- Keep warnings and behavioral guardrails visible without making them blocking unless explicitly designed as hard safety gates.

### Backup and recovery

- Validate bundles before applying changes.
- Preserve older backup import compatibility and leave newer-only trade plans/reviews untouched when importing an older bundle.
- Preserve import audit, pre-import snapshot and restore behavior.
- Keep credentials out of backups and exports.

### Quality gates

- Run `gradle testDebugUnitTest --no-daemon` and `gradle assembleDebug --no-daemon` on every pull request.
- Test offline mode, rate limits, partial API failures, duplicate imports, large ledgers and app restarts.
- Check TalkBack, contrast, touch targets, light/dark themes and small-screen layouts.
- Verify no exchange credentials appear in logs, exports or build artifacts.
