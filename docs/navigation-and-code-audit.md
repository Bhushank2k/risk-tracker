# Navigation and dead-code audit

## Current implementation

The bottom navigation in `MainActivity.kt` is Market, Ideas, History and Explore. Explore currently groups secondary destinations by task. Route IDs are intentionally preserved.

This cleanup adds **Unified SMC Signals** to the **Market Intelligence** Explore group alongside **Coin Explosion Radar**. The SMC screen receives its existing callback to open Daily Trade Ideas. No existing route is renumbered or removed.

## Consolidation without feature loss

- Trade Ideas remains a primary destination because it has its own setup validation and tracking workflow.
- Unified SMC Signals exposes Core and Advanced SMC results from the unified scan and is grouped with the Radar as market-intelligence tooling.
- Coin Explosion Radar remains separate: it has its own market-wide expansion scan, configuration, result stages and replay logic.
- Binance Sync and Sync Health remain separate because connection actions and diagnostic history are different tasks.
- Account Activity remains separate from trading Journal because deposits, withdrawals and transfers are not trading P&L.
- Tax Report and statement/import reconciliation remain separate because tax exports and source completeness have different requirements.
- P&L Calendar keeps Manual and Binance Futures values separately labeled; consolidation must not silently double-count records.

## Dependency and usage audit

The repository contains separate screen and engine files for Coin Explosion Radar, Market Activity Lab, Confirmed Reversal Signals, Unified SMC and Advanced SMC. Tests also exist for Radar, Market Activity Lab and Advanced SMC. These are not safe deletion candidates based on filename overlap alone.

The unified background worker persists both Core and Advanced SMC result stores. The startup path calls `UnifiedBackgroundScanScheduler.schedule` and `AdvancedSMCScanScheduler.cancelLegacyWork`. The older SMC and Advanced SMC worker classes and scheduler/state stores still exist in source; WorkManager unique-work names can survive app upgrades, so do not delete these classes or their compatibility cancellation paths without auditing enqueue history, persisted state and migration behavior.

The Android manifest registers the activities, while WorkManager workers may be instantiated by class name and unique-work metadata. A lack of direct Compose navigation is not enough to establish dead code.

## Deletion policy

No screen, engine, worker, persistence model, API client, migration, reconciliation function, notification handler or stored key is deleted in this pass.

Before any later removal:
1. Search all source, tests, manifest declarations, WorkManager unique names, callbacks, serialization keys and backup/restore paths.
2. Confirm no active or upgrade-persisted WorkManager jobs depend on the class or unique name.
3. Confirm the replacement provides equivalent outputs, freshness semantics, settings and replay/history behavior.
4. Add migration/compatibility handling where persisted data or user preferences are involved.
5. Run unit tests and the full Android build/CI workflow.

## Follow-up candidates — audit required

- Market Activity Lab: consider linking it from Market Intelligence or folding only its discovery entry into a workspace; preserve its watchlist, custom symbols, replay import/export and preferences.
- Confirmed Reversal Signals and Scanner Performance Lab: decide whether to expose under a research sub-group or retire only after checking all dependencies.
- Legacy SMC worker classes: remove only after proving all old unique work is cancelled and no upgrade path requires compatibility.
- Duplicate P&L refresh triggers: consolidate only after comparing freshness, retry and source reconciliation behavior.

## Verification

Run the existing CI workflow (unit tests, debug APK build and APK verification). Manually verify all four bottom destinations, the Explore route to Unified SMC Signals, its callback to Ideas, Radar navigation, and the existing account/report routes. No stored data or API behavior is intentionally changed.
