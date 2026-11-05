# Navigation and dead-code audit

## Applied in this change

- Kept the four high-frequency destinations in bottom navigation: Overview, Journal, Analytics and Ideas.
- Reorganized secondary destinations into task-based Explore groups.
- Removed redundant contextual quick-action text buttons from the top app bar; each duplicated an already available destination and made the bar crowded.
- Changed the Explore affordance to a menu icon.
- Removed an unused local navigation shape variable.
- Preserved every existing destination ID and screen-router branch.

## Consolidation without feature loss

The menu now groups related screens but does not collapse distinct financial workflows:
- Binance Sync and Sync Health remain separate destinations because connection actions and diagnostic history are different tasks.
- Account Activity remains separate from trading Journal because deposits, withdrawals and transfers are not trading P&L.
- Tax Report and Ledger Audit remain separate because tax exports and import reconciliation have different source and completeness requirements.
- Analytics and P&L Calendar remain separate views of aggregated performance and date-level results.
- Risk Calculator, Trade Risk Gate, Scenario Lab, Alerts and Target Planner remain individually accessible.

## Dead-code policy

No screen, persistence model, worker, API client, migration, reconciliation function or notification handler was deleted in this pass. A declaration is not considered dead merely because it has no direct call site: Compose navigation, WorkManager, Android manifest registration, callbacks and serialization can invoke code indirectly.

Follow-up candidates must be confirmed with repository-wide reference searches and Android build/test results before deletion. In particular, preserve:
- Store keys, backup/restore formats and migration compatibility.
- Binance credential handling, read-only API calls, sync scheduling and import recovery.
- Trade fill/position aggregation, P&L reconciliation and tax/ledger provenance.
- Notification and background-worker entry points.

## Verification

Run the existing CI workflow (unit tests, debug APK build and APK verification) after this navigation-only change. Manually verify every Explore destination and all four bottom navigation routes. No stored data or API behavior is intentionally changed.
