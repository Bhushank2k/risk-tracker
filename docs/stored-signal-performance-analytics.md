# Stored signal performance analytics

The Stored Signal Replay screen reports cohort metrics for completed, entered replay outcomes:

- Setup family (the captured scanner setup name)
- Signal horizon (the captured Intraday/Swing-style horizon)
- Signal score bands (10-point buckets)

Each cohort reports sample count, win rate, net expectancy in R, profit factor, and chronological peak-to-trough drawdown in R. Metrics are derived from the same deterministic replay results shown in the outcome list; they do not use live or future data outside the saved signal timestamp and retained post-signal candles.

Only entered outcomes with finite, non-zero net R are included in cohort performance. Waiting, invalid, unentered, flat, and non-finite outcomes are excluded. Cohort drawdown is calculated after sorting outcomes by replay exit time. Profit factor is gross positive net R divided by absolute gross negative net R; it is shown as infinity when there are gains and no losses, and as unavailable when there are neither.

Cohorts below 30 samples are explicitly marked as small-sample and should not be treated as reliable estimates. Thirty samples is a minimum display threshold, not a guarantee of statistical significance. These are research diagnostics, not profitability guarantees or trading instructions. No exchange API, order execution, scanner score, or risk gate is changed.
