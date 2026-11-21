# Risk Tracker — Information Architecture

Use one authoritative screen for each kind of information. Other screens may show a compact summary, but should link to the owner screen for full detail.

## Primary navigation

The bottom navigation has four destinations. Keep these stable unless a separately reviewed navigation migration is approved:

- **Market Overview** — portfolio snapshot and current account context.
- **Daily Trade Ideas** — generated market setups and their tracking/validation workflow.
- **Trade History** — execution and position-cycle history.
- **Explore** — specialist tools, exchange/account services, reports and app settings.

Explore is a destination in the bottom navigation. Its current groups are:

| Group | Destinations |
|---|---|
| Trading & Tools | Live positions & orders; Position & leverage calculator |
| Market Intelligence | Unified SMC Signals; Coin Explosion Radar |
| Performance & Reports | P&L Calendar; Crypto Tax Report |
| Exchange & Account | Binance Sync & imports; Sync health; Account activity & statements |
| App | Settings; About Risk Tracker |

Keep existing destination IDs stable while consolidating presentation. A navigation label or grouping change must not silently change a destination's meaning.

Unified SMC Signals is a secondary Explore destination (route 33) beside Coin Explosion Radar (route 32). It presents Core and Advanced SMC results from the unified scan workflow and can return users to Daily Trade Ideas (route 21). The four primary bottom-navigation destinations remain unchanged.\n\n## Screen and data ownership

| Screen / capability | Owns | May summarize | Avoid repeating |
|---|---|---|---|
| Market Overview | Current account snapshot, daily P&L context and headline risk | Recent activity and recorded performance | Full trade history, import diagnostics |
| Daily Trade Ideas | Generated setups, entry/stop/target context, validation and tracking | Scan freshness and tracked-idea status | Portfolio accounting and journal history |
| Unified SMC workspace | Core and Advanced SMC candidate presentation from the unified scan result | Candidate counts and scan freshness | A second independent SMC scan lifecycle |
| Trade History / Journal | Binance executions and grouped position cycles; retained manual records where applicable | Realized result per trade, entries/exits and fees | Portfolio totals and aggregate performance dashboards |
| P&L Calendar | Daily realized-P&L presentation with source distinctions | Manual and Binance daily totals | A second trade ledger or blended source assumptions |
| Binance Sync | Credentials, connection tests, sync controls and import workflows | Current sync state | Full journal or tax calculations |
| Sync Health | Sync freshness and operational diagnostics | Last attempt/result | A second connection setup flow |
| Account Activity & Statements | Deposits, withdrawals, transfers and statement records | External cash flow by asset | Trading P&L |
| Crypto Tax Report | Financial-year tax dataset, review and exports | Imported transaction counts and data quality | General portfolio analytics |
| Coin Explosion Radar | Its own discovery signals and filters | Market observations | Portfolio or account records |
| Market Activity Lab | Public futures candle research, indicator scoring and historical replay | Market activity observations and source freshness | Account sync and trade journal records |
| Risk tools | User inputs, limits, scenarios and calculated risk | Relevant account equity where available | Duplicated portfolio dashboards |

## Consolidation rules

1. Overview is the compact portfolio snapshot; the Journal owns record-level trade history.
2. Journal records remain the source for execution-level views. Calendar and analytics aggregate records; they do not create another ledger.
3. Binance Sync owns connection and import controls. Other pages show only concise freshness/status indicators when useful.
4. Account Activity owns external cash flows. Do not silently combine transfers/deposits with trading P&L.
5. Tax Report and Ledger Audit retain source and completeness caveats adjacent to affected figures.
6. Core and Advanced SMC should be presented as views/filters over the unified SMC result where the shared data contract supports it. Do not run duplicate scanners merely to preserve separate screens.
7. Keep Trade Ideas and SMC under a coherent Market Intelligence experience, while retaining distinct strategy filters and explanations.
8. Market Activity Lab and Coin Explosion Radar are separate research/discovery capabilities until measured overlap and usage justify folding or retiring either one.
9. Keep destination IDs and compatibility/migration paths stable during navigation cleanup.
10. Never remove source distinctions, reconciliation warnings, risk gates, historical records or legal/tax qualifications to reduce UI complexity.

## Retirement policy

A screen, worker, store, model or scanner may be removed only after a repository-wide dependency and usage audit confirms:

- no navigation, deep-link, notification, WorkManager, reflection or serialized-name entry point remains;
- no active worker or scheduler enqueues it, and upgrade/migration paths cancel or migrate prior work safely;
- no other screen, test, cloud module or shared scanner depends on its types or calculations;
- stored data has a tested migration/retention plan;
- replacement behavior is covered by tests and the replacement has shipped through a reviewed migration.

An absent direct route in `MainActivity` is not by itself proof that a composable or worker is unused.

## Layout rules

- The app bar supplies the page title; screen content should not repeat the same large title.
- Use consistent page gutters and compact vertical spacing.
- Prefer one clear hero panel plus compact metric groups on overview.
- Prefer dense rows with dividers for histories and breakdowns.
- Place filters directly above the data they control.
- Use expandable sections for advanced diagnostics, methodology and historical detail.
- Keep labels concise; explain values where needed to interpret them or act safely.
