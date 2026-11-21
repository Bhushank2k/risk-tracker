# Repository cleanup plan — balanced consolidation

## Goal

Reduce navigation friction and clarify ownership without deleting a feature, scanner, record or compatibility path before its dependencies and usage are understood.

## Implemented in this pass

- Added Unified SMC Signals to Explore under Market Intelligence beside Coin Explosion Radar.
- Preserved the existing destination IDs and primary bottom navigation.
- Connected the SMC screen's existing callback to Daily Trade Ideas.
- Added a navigation regression assertion that both Market Intelligence destinations remain secondary routes.
- Updated the information architecture and navigation audit to describe the actual current routes and the evidence required before deletion.

## Audited feature boundaries

| Area | Evidence in repository | Decision |
|---|---|---|
| Daily Trade Ideas | Dedicated screen, validation/tracking models and primary route | Keep as primary |
| Unified SMC | One unified scan persists both Core and Advanced result sets; dedicated screen observes both | Keep; expose in Market Intelligence |
| Coin Explosion Radar | Separate data source, engine, screen, replay/configuration and engine tests | Keep; group with SMC, do not merge engines |
| Market Activity Lab | Separate research engine/screen, saved preferences, replay/import behavior and tests | Keep code; review navigation placement later |
| Confirmed Reversal Signals | Separate historical candle scan and screen | Keep code; review navigation placement later |
| Advanced SMC worker | Unified path saves Advanced results; legacy scheduler cancellation exists | Do not delete class in this pass |
| SMC legacy worker/scheduler | Separate worker, unique WorkManager names and persisted SMC result/status keys remain in source | Do not delete without upgrade/WorkManager compatibility audit |
| Binance Sync / Sync Health | Connection workflow vs diagnostics | Keep separate |
| Account Activity / Journal | External cash flows vs executions | Keep separate |
| Tax Report / P&L Calendar | Tax exports vs date-level performance with separate source labels | Keep separate |

## Safe consolidation principles

1. Consolidate navigation first; consolidate screens only when user tasks and data contracts are genuinely the same.
2. Do not combine Manual journal P&L with Binance Futures realized P&L without explicit source and reconciliation semantics.
3. Preserve SharedPreferences keys, JSON schemas, import coverage, backups and restore compatibility.
4. Preserve WorkManager unique-work names and cancellation paths until upgrade behavior is verified.
5. Preserve read-only exchange access, credential handling, risk gates and notification behavior.
6. Treat a screen missing from navigation as hidden/unrouted, not automatically dead code.

## Next phases

### Phase 2 — research workspace review
- Decide whether Market Activity Lab, Confirmed Reversal Signals and Scanner Performance Lab belong in a Research subsection of Market Intelligence.
- Compare overlapping data sources and outputs; keep distinct replay, watchlist and historical-analysis capabilities.

### Phase 3 — worker and startup lifecycle
- Trace every enqueue/cancel call and unique-work name across app versions.
- Add tests for worker independence and compatibility cancellation before removing any legacy worker class.
- Revisit the initial-scan preference so it records a successful/terminal state rather than merely an enqueue attempt.

### Phase 4 — broader architecture cleanup
- Extract app orchestration from MainActivity incrementally.
- Centralize market-data HTTP policy and freshness reporting.
- Plan Room migration for durable trade/financial records only with a backward-compatible import path.

## Verification checklist

- [ ] Unit tests pass, including primary navigation tests.
- [ ] Android debug build and APK verification pass.
- [ ] Explore opens Unified SMC Signals.
- [ ] SMC screen can navigate to Daily Trade Ideas.
- [ ] Coin Explosion Radar remains reachable.
- [ ] Market, Ideas, History and all existing account/report routes remain unchanged.
- [ ] No persisted keys, historical records, exchange permissions or background work behavior changed.
