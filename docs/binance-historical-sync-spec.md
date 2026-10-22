# Binance USDⓈ-M Futures Historical Sync Specification

Status: implementation plan  
Target range: 2018–2026 (UTC calendar years)  
Market: Binance USDⓈ-M Futures  
Include trading fees: yes  
Include funding: yes

## 1. Repository baseline and scope

The current README states that no exchange API is connected and trade-journal P&L is estimated from manually entered prices, quantity, side, and fees. This is therefore a new integration, not just a change to an existing Binance sync. Preserve manual journal and daily P&L behavior. This document describes the safe implementation sequence and acceptance criteria; it does not claim that API access to every historical record is guaranteed.

The feature is read-only: no order placement, cancel, transfer, or withdrawal endpoints.

## 2. Historical availability is a product constraint

Do not promise that REST pagination can recover every record back to 2018.

- Recent fills: `GET /fapi/v1/userTrades` supports a maximum `limit` of 1,000 and bounded time windows (maximum seven days per request); use supported ID/time pagination for recent history.
- Older fills: use Binance's documented asynchronous USDⓈ-M trade-history export workflow (`GET /fapi/v1/trade/asyn` and the corresponding download-link endpoint) where the account/API has access. Export requests are limited (documented as five per month) and a request can cover at most one year. Verify current Binance docs and permissions before coding.
- Income history: `GET /fapi/v1/income` is paginated (maximum `limit` 1,000), but the endpoint's historical availability is limited (documented as the most recent three months). Pagination does not bypass retention.
- Actual account funding and commissions must come from account income records or account exports. Public `/fapi/v1/fundingRate` is market reference data and must never be used to fabricate an account's actual funding payments.
- Provide Binance CSV/export import as the fallback for unavailable old income records. Record source and coverage per year and per data type. Never silently label missing history as complete.

Verify endpoint names, parameters, signing, retention, export quotas, and permissions against the current official Binance USDⓈ-M Futures API documentation during implementation.

## 3. Range and sync jobs

Create independent jobs for each UTC calendar year 2018 through 2026, with half-open ranges:
- start: `YYYY-01-01T00:00:00Z`
- end: next year's `YYYY-01-01T00:00:00Z` (exclusive in internal range handling)

The 2026 job must only mark dates/data actually queried and available; future dates are not complete merely because the selected year is 2026. Track fills, realized P&L, commissions, and funding coverage independently.

Recommended state values: `PENDING`, `FETCHING`, `PERSISTING`, `PAUSED`, `RETRY_WAIT`, `NEEDS_IMPORT`, `PARTIAL`, `COMPLETE`, `FAILED`.

## 4. Pagination, retries, and checkpoints

1. Follow each endpoint's documented cursor contract; do not assume generic page-number pagination.
2. Use endpoint-specific time windows and page-size limits. For `userTrades`, use supported `fromId` or bounded time requests as documented; these cannot necessarily be combined. For income, use the currently documented pagination parameters and retention window.
3. Persist each page before advancing its checkpoint. Commit page rows and checkpoint in one Room transaction.
4. Use bounded overlapping windows to avoid boundary gaps; unique keys make overlap safe.
5. Retry transient network/server failures and rate limits with exponential backoff and jitter. Honor `Retry-After` when supplied. Do not retry invalid credentials/signatures indefinitely.
6. Persist async export request IDs and status; poll with backoff, download promptly because links expire, and save the file before parsing.
7. Resume from the last committed cursor after app restart, process death, pause, or network loss. Avoid repeatedly consuming limited monthly export quota.
8. An empty page is not proof of complete historical coverage when the endpoint's retention does not include the requested interval.

## 5. Local data model (Room)

Use one Room database with a shared append-oriented ledger and year indexes, not a separate database file per year. This supports cross-year queries, consistent deduplication, migrations, and aggregate recalculation.

Suggested entities:
- `exchange_accounts`: account ID, exchange, market type, display name (never store API secrets here).
- `exchange_trades`: account ID, symbol, trade ID, order ID, timestamp ms, side, position side, quantity, price, realized P&L if supplied, commission if supplied, commission asset, UTC year, source.
- `exchange_income`: account ID, transaction ID, income type, timestamp ms, amount, asset, symbol/trade ID where supplied, UTC year, source.
- `sync_jobs`: account ID, year, data type, state, cursor, async export request ID, last successful timestamp, counts, error, updated timestamp.
- `daily_pnl`: account ID, UTC date, realized P&L, signed commissions, signed funding, net P&L, source coverage, calculation version.
- `yearly_summaries`: year, aggregates, coverage status, calculation timestamp; preferably rebuildable from the ledger.

Use database indices for account/time and account/year. Use decimal-safe money representation (e.g. decimal strings converted with BigDecimal), never Float/Double for financial calculations. Preserve each original asset and signed amount; currency conversion must be explicit and auditable.

## 6. Deduplication and provenance

- Fill key: `(accountId, marketType, symbol, tradeId)`.
- Income key: `(accountId, marketType, incomeType, tranId)` when the endpoint guarantees uniqueness within that type. Do not assume transaction IDs are globally unique across all income types.
- CSV rows: prefer Binance-provided IDs. If unavailable, use a documented source-file plus normalized-row fingerprint and surface ambiguous collisions for review.
- Use idempotent upserts; reimporting a page or file must not change totals.
- Preserve source metadata and original exchange fields needed to audit calculations.

## 7. Daily P&L reconciliation

Use a single signed-amount convention:

`netPnl = realizedPnl + signedCommission + signedFunding`

Fees are generally negative and funding can be positive or negative; add the signed values without subtracting expenses twice.

- Keep manual daily entries and journal-derived estimates distinct from Binance-imported totals.
- Make Binance-derived daily totals deterministic recalculations from unique ledger rows, not additive increments.
- Choose one authoritative realized-P&L source when the same value appears in both fills and income history; use the other for reconciliation, not addition. Document the fallback if income history is missing.
- Import commission and funding from actual account income records/export data. Do not infer them from public funding rates.
- Recalculate only affected UTC dates after imports/updates, then rebuild monthly/yearly summaries.
- Mark daily/yearly results partial when fees or funding coverage is missing. Do not treat unavailable data as zero.
- Keep manual P&L functional when Binance is disconnected. Offer explicit reconciliation/override controls rather than silently replacing manual values.

## 8. UI and background work

Add a Binance Historical Sync screen:
- start year 2018, end year 2026;
- USDⓈ-M Futures market label;
- separate toggles for fills, commissions, and funding;
- per-year and per-data-type status, record counts, last checkpoint, and coverage warnings;
- Start, Pause, Resume, Retry Missing Data, and Import Export actions;
- clear messages for rate limits, permissions, unavailable history, expired links, and incomplete years.

Use WorkManager for persistent background work with network constraints, progress, cancellation, and bounded work units. Add an incremental recent-history sync after the initial import. Keep UI state derived from persisted sync jobs.

## 9. Credential and privacy requirements

- Require a read-only API key with no trading or withdrawal permissions.
- Never hardcode credentials or include them in logs, crash reports, exports, or source control.
- Use platform-appropriate encrypted credential storage and clear credentials on disconnect.
- Keep data local unless the user explicitly opts into another sync destination.
- Do not call order, transfer, or withdrawal endpoints.

## 10. Implementation order

1. Inspect existing Room entities, migrations, daily P&L calculations, and manual/journal provenance.
2. Add schema/migrations and repository tests for idempotent upserts and decimal-safe calculations.
3. Implement authenticated read-only API client and recent fills pagination.
4. Implement async historical fill exports, quotas, checkpointing, and CSV import fallback.
5. Implement income history imports and per-year fee/funding coverage reporting.
6. Add WorkManager orchestration, pause/resume/retry, and year selection UI.
7. Reconcile daily P&L and add annual summaries/export.
8. Add migration, boundary, retry, duplicate-import, missing-history, and P&L regression tests.

## 11. Acceptance criteria

- A 2018–2026 selection creates nine independently resumable annual jobs.
- Every endpoint follows its own supported time range, page size, cursor, and rate-limit behavior.
- Repeated page/file imports are idempotent.
- Process death and network loss resume without skipping committed ranges.
- API retention gaps and missing income exports remain visible as partial coverage.
- Commission and funding signs and original assets are preserved.
- Realized P&L cannot be double-counted across fill and income sources.
- Daily, monthly, and yearly totals reconcile from the same ledger.
- Existing manual P&L and trade-journal behavior survives migration.
- No exchange secret is committed or logged; the integration performs no trading actions.

## References

- [Binance USDⓈ-M Futures API documentation](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Account-Trade-List)
- [Binance Futures account income history](https://developers.binance.com/docs/derivatives/usds-margined-futures/account/rest-api/Get-Income-History)
- [Binance Futures connector account endpoints](https://github.com/binance/binance-futures-connector-python/blob/main/binance/um_futures/account.py)
