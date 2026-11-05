# Risk Tracker — Information Architecture

Use one authoritative screen for each kind of information. Other screens may show a small summary, but should link to the owner screen for full detail.

## Screen ownership

| Screen | Owns | May summarize | Avoid repeating |
|---|---|---|---|
| Overview | Current account equity, today's P&L, daily loss budget, open exposure | Net recorded P&L, win rate, recent activity | Full trade history, detailed analytics, import diagnostics |
| Journal | Individual Binance executions and grouped position cycles | Realized result per trade, entry/exit and fees | Portfolio totals and aggregate performance charts |
| Analytics | Period-based performance, win/loss, profit factor, drawdown, volume and fees | Net P&L and execution count | Connection setup and sync controls |
| Ideas | Market setups, entry/stop/target, validation and tracking | Current scan freshness and tracked ideas | Portfolio balance and journal history |
| Binance Sync | Credentials, connection tests, sync controls and import workflows | Current sync state | Full statement ledger and tax summaries |
| Account Activity | Deposits, withdrawals, transfers and statement records | Net external cash flow by asset | Trade journal and tax calculations |
| Tax Report | Financial-year trade dataset, tax review and exports | Imported transaction counts and data quality | General portfolio analytics |
| Ledger Audit | Historical import coverage, duplicate counts and source reconciliation | Imported totals | Daily P&L and journal estimates |
| Risk tools | User inputs, limits, scenarios and calculated risk | Relevant account equity where available | Duplicated portfolio dashboards |

## Duplication rules

1. Overview is the compact portfolio snapshot; Analytics is the detailed performance workspace.
2. Journal is the record-level source for executions. Analytics aggregates those records; it does not create a second trade ledger.
3. Binance Sync owns connection and sync diagnostics. Other pages show only a small freshness/status indicator when it changes the user's decision.
4. Account Activity owns external cash flows. Those values must not be silently combined with trading P&L.
5. Tax Report and Ledger Audit retain source and completeness caveats because those affect interpretation; keep them concise and adjacent to the relevant figures.
6. Use links or Explore destinations to navigate to full details instead of copying full tables or diagnostic paragraphs across pages.
7. Never remove source distinctions, reconciliation warnings, risk warnings or legal/tax qualifications merely to reduce text.

## Layout rules

- The app bar supplies the page title; screen content should not repeat the same large title.
- Use 16dp horizontal page gutters, 8–10dp vertical section gaps and 14dp card padding.
- Prefer one hero panel plus compact metric groups on overview.
- Prefer dense rows with dividers for histories and breakdowns.
- Place filters directly above the data they control.
- Use expandable sections for advanced diagnostics, methodology and historical detail.
- Keep labels concise; show explanations only where the user needs them to interpret a value or safely act.
