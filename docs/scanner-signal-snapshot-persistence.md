# Scanner Signal Snapshot Persistence

## What is captured

The Advanced SMC signal save path now appends a versioned, immutable snapshot whenever the scanner publishes its current result set. Each record preserves:

- Stable snapshot ID (signal ID + source candle timestamp), signal ID and symbol
- Side, strategy/pattern, strategy version and timeframe
- Source signal timestamp and local capture timestamp
- Score, status, entry-zone low/high, stop, TP1, TP2 and reward/risk
- Scanner explanation and confirmation guidance
- Optional BTC bias, ADX, ATR percentage, relative volume and funding rate fields

Unavailable context remains null. It is not backfilled from current market data, because that would rewrite what was known when the signal was emitted.

## Storage behavior

- Local app-private SharedPreferences JSON store; no network upload and no exchange permissions.
- Append/deduplicate by stable snapshot ID, so repeated scans of the same signal candle do not create duplicate records.
- Bounded to the newest 5,000 snapshots (chronological order).
- Malformed or invalid records are skipped when reading.
- Schema version is stored on each snapshot for future migrations.
- Existing latest-signal storage and UI behavior remain unchanged.

## Replay handoff

ScannerSignalSnapshot.toReplaySignal() converts the saved entry zone to its midpoint, and marks entry as not yet triggered. Replay therefore has to observe a later candle touching the midpoint before treating it as an entry. The original entry-zone boundaries remain available in the snapshot for future zone-aware replay.

## Scope and limitations

This first integration captures signals written through AdvancedSMCSignalStore.save. It does not yet capture every Confluence V4/core scanner emission, persist historical candle data, or automatically run replay. Indicator/context fields are present in the schema but remain null until the emitting scanner can provide their exact point-in-time values. The local preference store is appropriate for this bounded first phase; a Room database should replace it before substantially increasing retention or query volume.

The snapshots are research records only. They do not place, edit or cancel orders and are not proof of signal profitability.
