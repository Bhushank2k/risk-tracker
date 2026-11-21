# Repository cleanup audit — balanced consolidation

**Status:** Phase 1 audit complete; Phase 2 navigation consolidation implemented in PR #396  
**Base reviewed:** `main` at `df60d5bfa74465c5a70874a22bae2a4a69457771`  
**Policy:** No feature, worker, store, model, scanner, historical record or calculation is deleted in this change.

## Goals

Reduce duplicated navigation and repeated workflows without weakening financial-data provenance, risk gates, scan reliability or upgrade compatibility. Prefer a single owner screen with focused subviews over parallel screens that maintain separate state.

## Verified app-shell findings

The current `MainActivity.kt` routes four bottom-navigation destinations:

| Bottom destination | Existing route ID |
|---|---:|
| Market Overview | 0 |
| Daily Trade Ideas | 21 |
| Trade History | 1 |
| Explore | 29 |

Explore currently groups:
- Trading & Tools: Live positions & orders (16), Position & leverage calculator (2)
- Market Intelligence: Unified SMC Signals (33), Coin Explosion Radar (32)
- Performance & Reports: P&L Calendar (23), Crypto Tax Report (9)
- Exchange & Account: Binance Sync & imports (6), Sync health (18), Account activity & statements (17)
- App: Settings (7), About (5)

These route IDs are consumed by the app-shell router. Preserve them during UI grouping work.

## Consolidation decisions

| Area | Balanced decision | Evidence / safety boundary |
|---|---|---|
| Core SMC + Advanced SMC | Consolidate user-facing discovery into one SMC workspace with Core/Advanced filters and shared scan status | `UnifiedSMCSignalsScreen.kt` consumes both core and advanced result stores and merges candidates. Keep both engines, result stores and strategy explanations. |
| Trade Ideas + SMC | Group under a Market Intelligence concept; preserve distinct setup types and tracking | Trade Ideas and unified SMC have coordinated scan state. Do not collapse their signal semantics or risk gates. |
| Binance Sync + import controls | Keep one Data & Sync owner; expose concise health summaries elsewhere | MainActivity routes Binance Sync (6) and Sync Health (18) separately today. A later UI pass may place health inside Sync, but retain a stable way to reach diagnostics. |
| P&L summaries | Establish one shared accounting/portfolio data contract before removing any summary | Manual journal, Binance Futures fills, imported P&L and external cash flows have different provenance. Do not sum them without explicit reconciliation rules. |
| Scan health | Present one market-data health summary with per-pipeline detail | Trade Ideas and SMC can fail independently; preserve independent status, freshness and retry behavior. |
| Risk calculations | Reuse shared risk calculations; keep contextual entry points | Removing a screen must not remove a hard risk gate or calculation used by scanner/cloud modules. |
| Alert history | One notifications/alerts entry point if the UI currently duplicates it | Cloud relay history is process-local; do not imply durable history or exactly-once delivery. |

## Usage/dependency audit: retirement candidates

| Candidate | What is known | Decision |
|---|---|---|
| Coin Explosion Radar | Dedicated Explore route (32); has separate data source and engine files | **Keep for now.** Similarity to Trade Ideas is not enough to prove functional duplication. Compare signal inputs, output utility and usage before folding it in. |
| Market Activity Lab | Screen and research/engine files exist in the repository; no dedicated destination is present in the MainActivity route switch reviewed | **Do not delete.** Audit all call sites, tests, exports and intended product access before deciding whether it is hidden, embedded or obsolete. |
| Legacy Advanced SMC screen | Separate composable source exists; the unified SMC screen already combines Core and Advanced result stores | **Candidate for later UI retirement only.** Confirm all call sites and deep links; retain engines and data stores. |
| Legacy Advanced SMC worker/scheduler | App startup invokes `AdvancedSMCScanScheduler.cancelLegacyWork(context)` and schedules `UnifiedBackgroundScanScheduler` | **Keep compatibility class and cancellation path.** Confirm historical WorkManager unique-work names, upgrade behavior and all references before deleting. |
| Older SMC background worker | Worker source exists in the repository | **No deletion.** Trace enqueue/cancel paths, unique-work names and installed-version upgrade behavior first. |
| Manual journal records | Legacy/manual records still participate in app data and P&L presentation | **Preserve and migrate.** Never remove user records as part of UI simplification. |
| Repeated P&L refreshes | Multiple sync and refresh paths exist for distinct data sources | **Do not consolidate blindly.** Map trigger, data source, freshness and side effects before changing cadence. |

## Required checks before any deletion

1. Search the entire repository for class names, composable names, route IDs, worker class names, unique-work names, preference keys and serialized model names.
2. Inspect every enqueue, cancel, observe and periodic-work call site, including app startup and upgrade paths.
3. Check Android manifest entries, notification intents, exported components, deep links and backup/restore behavior.
4. Check app tests, scanner-core tests, cloud-scanner imports and shared strategy dependencies.
5. Identify persisted keys/files and define a forward/backward-compatible migration or retention policy.
6. Add tests for the replacement behavior and migration from a prior installed version.
7. Remove only in a dedicated follow-up PR with a concise dependency audit attached.

## Recommended implementation sequence

### Phase 1 — navigation and ownership (completed)
- Correct the information-architecture documentation to match the current four-destination app shell.
- Record screen ownership and consolidation boundaries.
- Establish an explicit no-delete retirement policy.

### Phase 2 — safe UI consolidation
- Add a coherent Market Intelligence entry point for Trade Ideas and unified SMC without duplicating scan jobs.
- Present Core/Advanced as filters or tabs over the unified SMC results.
- Move Sync Health diagnostics into the Binance Sync experience only after preserving direct access and existing status semantics.
- Keep route IDs stable or provide explicit route migration.

### Phase 3 — dependency-proven retirement
- Remove only verified dead UI wrappers and obsolete navigation entries.
- Retain worker compatibility/cancellation until upgrade-safe retirement is demonstrated.
- Retain all historical financial records and shared scanner/risk calculations.

### Phase 4 — validation
- Run the full Android and shared-module test/build workflows.
- Add regression coverage for scan independence, cached-signal retention, sync/import provenance, journal restore and navigation.
- Review the PR diff and CI before any merge decision.

## Explicit non-goals

- No order execution or exchange-write behavior.
- No changes to accounting definitions, P&L source precedence, tax logic or risk gates.
- No deletion of historical records, preferences, backup data or import audit data.
- No removal of scanner engines simply because their standalone screen is consolidated.
- No merge as part of this work.
