# Product roadmap

This roadmap extends existing Risk Tracker functionality without replacing Daily P&L, the closed-trade journal, position calculator, insights, target planner, or Binance historical import/recovery workflows.

## Delivery order

1. **Trade Risk Gate** — advisory pre-trade evaluation, saved locally, with risk-budget and trade-count checks.
2. **Behavioral Intelligence** — deterministic insights derived from existing trade outcomes and optional journal metadata; show sample sizes and caveats.
3. **Risk Scenario Lab** — deterministic stress scenarios first, followed by seeded Monte Carlo simulations with clearly stated assumptions.
4. **Trade Review System** — connect a saved plan to an existing closed trade, record review notes, and compare planned versus actual outcomes.
5. **Ledger provenance and reconciliation** — explicit source IDs, import batches, coverage and unmatched-record review before unifying financial totals.
6. **Optional AI summaries** — opt-in summaries over local analytics, with privacy and data-quality disclosures.

## Engineering guardrails

- Keep calculations deterministic and independently unit-tested.
- Do not double-count journal estimates and exchange-imported results.
- Do not treat missing historical coverage as zero.
- Do not add exchange order execution or request trading/withdrawal API permissions.
- Preserve current backup/import formats and test restore compatibility.
- Label estimates and assumptions in the UI.

## Current implementation status

- [x] Trade Risk Gate evaluation and local saved plans (initial implementation).
- [ ] Wire configurable settings to existing app preferences and add plan-to-journal linking.
- [ ] Behavioral insight engine and opt-in review metadata.
- [ ] Risk Scenario Lab and deterministic simulations.
- [ ] Trade review lifecycle.
- [ ] Ledger provenance and reconciliation UX.
- [ ] Optional AI summaries.

Checklist items should only be marked complete after integration and passing tests/builds.
