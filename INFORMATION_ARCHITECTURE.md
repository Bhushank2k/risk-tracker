# Risk Tracker — Information Architecture

Use one authoritative screen for each kind of information. Summary cards may appear elsewhere, but full records and diagnostics belong to one owner.

## Current navigation

The four persistent destinations are:

- **Market** — portfolio snapshot and current account context.
- **Ideas** — daily Trade Ideas, validation and tracking.
- **History** — Binance execution journal and grouped position cycles.
- **Explore** — secondary tools and account services.

Explore groups the current destinations by task:

| Group | Destinations |
|---|---|
| Trading & Tools | Live positions & orders; Position & leverage calculator |
| Market Intelligence | Unified SMC Signals; Coin Explosion Radar |
| Performance & Reports | P&L Calendar; Crypto Tax Report |
| Exchange & Account | Binance Sync & imports; Sync health; Account activity & statements |
| App | Settings; About |

Unified SMC Signals combines Core and Advanced SMC result sets from one scan. It is now grouped beside Coin Explosion Radar in Explore; Daily Trade Ideas remains a primary destination because it has its own setup lifecycle, validation and tracking workflow.

## Screen ownership and non-duplication

| Screen / domain | Owns | Avoid duplicating |
|---|---|---|
| Market Overview | Current account snapshot, today's reconciled P&L, daily loss budget and exposure summary | Full trade history, tax details, import diagnostics |
| Trade Ideas | Setups, entry/stop/target, validation and idea tracking | Portfolio balance and execution ledger |
| Unified SMC Signals | Core + Advanced SMC candidates, scan freshness and SMC status | Trade Idea tracking records |
| Coin Explosion Radar | Market-wide volatility/expansion scan and its own replay/configuration | SMC candidate calculations or Trade Idea records |
| History / Journal | Binance executions and grouped position cycles | Portfolio-wide performance totals |
| P&L Calendar | Date-level realized P&L, with Manual and Binance sources displayed separately | A merged or inferred single ledger |
| Binance Sync | Credentials, connection tests, sync controls and import workflows | Statement history and tax calculations |
| Sync Health | Sync diagnostics and freshness | Connection setup controls |
| Account Activity & Statements | Deposits, withdrawals, transfers and statement records | Trading P&L |
| Crypto Tax Report | Financial-year dataset, tax review and exports | General portfolio analytics |
| Position & leverage calculator | User inputs, sizing and liquidation scenarios | Portfolio dashboards |
| Cloud / risk / research tools | Their own alert, research and risk workflows where surfaced | Duplicate account or execution records |

Market Activity Lab, Confirmed Reversal Signals, Scanner Performance Lab and other specialist screens remain in source. Their absence from the current Explore list is not proof that their engines, stored preferences, replay formats or supporting tests are dead. Revisit their placement only after product-owner review and call-site/worker checks.

## Consolidation rules

1. Overview is the compact portfolio snapshot; Journal owns execution history; analytics views aggregate records without creating a second ledger.
2. Keep external cash flows separate from trading P&L.
3. Keep Binance Sync separate from Sync Health: connection actions and diagnostic history are different tasks.
4. Keep Tax Report separate from import/ledger reconciliation: tax exports and source completeness have different purposes.
5. Keep Trade Ideas, SMC and Coin Explosion Radar distinct as products until evidence demonstrates their signals, controls and data contracts are interchangeable. Group related tools in navigation before attempting engine deletion.
6. Preserve source labels, reconciliation warnings, risk gates and legal/tax qualifications.
7. Preserve persisted preference keys, backup/restore formats and historical records during UI cleanup.

## Layout rules

- The app bar supplies the page title; screen content should not repeat the same large title.
- Use compact summary cards and dense rows for histories.
- Place filters beside the data they control.
- Put advanced diagnostics, methodology and historical detail behind expandable sections.
- Keep labels concise and explanations adjacent to values that require interpretation.
