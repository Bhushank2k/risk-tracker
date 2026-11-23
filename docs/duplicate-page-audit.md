# Duplicate-page audit — safe consolidation plan

**Reviewed:** `main` at `b7e77f6b76d3656697502bb6d2d14795469ed998`  
**Scope:** Android user-facing screens, Explore destinations, and the screen/data ownership documented in `INFORMATION_ARCHITECTURE.md`.  
**Outcome:** No screen is deleted in this audit. Similar labels or overlapping summaries are not sufficient evidence that two screens are duplicates.

## Findings

| Pair / area | Evidence from current implementation | Decision |
|---|---|---|
| Daily Trade Ideas (route 21) + Best Opportunities (route 34) | `BestOpportunitiesScreen` consumes both `TradeIdeaScanManager.state` and `UnifiedSMCScanManager.state`, merges Core and Advanced SMC candidates, and applies cross-scanner status, side and horizon filters. It also requires fresh/aligned SMC confirmation and execution checks for CONFIRMED status. | **Keep both for now.** Best Opportunities is a cross-scanner decision view, not a duplicate list. A future UI option is a tab/secondary view within Market Intelligence, provided both workflows remain distinct and no second scan lifecycle is introduced. |
| Binance Sync (route 6) + Sync Health (route 18) | Sync owns credentials, sync actions and imports. Sync Health is separately routed for operational freshness/diagnostics. The existing IA explicitly treats health as diagnostics rather than a second connection flow. | **Consolidate presentation, not behavior.** Preferred future direction: a Sync Health section inside Binance Sync, retaining route 18 as a compatibility redirect/entry point until deep links and callers are audited. No route removal in this change. |
| Trading Analytics + P&L Calendar (route 23) | Analytics and calendar present different projections of financial activity: trends/breakdowns versus date-level realized P&L. Journal and imported sources have different provenance. | **Keep both.** Reuse one accounting/data contract and avoid duplicate headline totals; do not merge daily calendar detail into trend charts or blend sources without reconciliation. |
| Unified SMC Signals (route 33) + legacy Advanced SMC UI | Unified SMC is the current combined Core/Advanced result workspace. The repository has legacy Advanced SMC components and compatibility worker/scheduler paths. | **Candidate for UI retirement only.** Before removing a composable, verify every call site, deep link, notification intent, WorkManager unique-work name, upgrade cancellation path and persisted result consumer. Keep engines and stores. |
| Coin Explosion Radar (route 32) + Trade Ideas | Both surface market opportunities, but Radar has its own discovery engine/data source while Trade Ideas has entry/stop/target, tracking and validation semantics. | **Keep separate.** No proven functional duplication. |
| Account Activity + Trade History | Account Activity covers external cash flows; Trade History covers executions and position cycles. | **Keep separate.** Never combine deposits/transfers with trading P&L by presentation-only cleanup. |
| Tax Report + Ledger/Import Audit | Tax exports and source reconciliation have different completeness and provenance requirements. | **Keep separate.** Preserve warnings, source lineage and export semantics. |
| Market Activity Lab | Research/replay capability is distinct from live account sync and trade journaling; no dedicated Explore route was identified in the reviewed navigation. | **Do not delete.** Complete a call-site, embedding, test and product-entry audit before deciding whether it is hidden or obsolete. |

## Safe changes in this PR

- Document the verified screen ownership and overlap boundaries.
- Record Best Opportunities as a distinct cross-scanner view under Market Intelligence.
- Clarify that Sync Health may move into Binance Sync as a section, but route 18 remains stable until compatibility is proven.
- Keep the four primary navigation destinations and all existing secondary route IDs unchanged.
- Add no data migration and make no changes to journal, position, import, tax, backup, alert, scanner or risk stores.

## Required evidence before a later deletion PR

1. Search all Kotlin sources, tests, manifest entries and navigation callbacks for composable names and route IDs.
2. Trace deep links, notification intents, WorkManager enqueue/observe/cancel calls, unique-work names and upgrade paths.
3. Check reflection, serialization, backup/restore, preference keys and stored result consumers.
4. Confirm replacement behavior and add regression tests for route compatibility and data ownership.
5. Run unit tests, debug APK build and APK verification.
6. Submit deletions separately from behavioral changes; preserve route redirects where installed versions may still reference old destinations.

## Explicit non-goals

- No deletion of screens, scanner engines, workers, stores, records or preferences.
- No changes to accounting definitions, P&L source precedence, tax calculations or risk gates.
- No route-ID renumbering and no change to order execution or exchange-write behavior.
- No merge without explicit approval.
