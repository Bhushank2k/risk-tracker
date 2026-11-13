# Trading analytics methodology

The Performance section is available from **Trade Journal → Performance**. It analyzes saved Binance Spot and USDⓈ-M Futures executions on the device, alongside reconciled daily P&L.

## Execution-level metrics

- Partial fills are grouped by exchange, market, symbol and order ID. Orders without an exchange order ID fall back to execution ID.
- Realized-order win rate, gross profit, gross loss, profit factor, average win and average loss use only orders with finite, non-zero realized P&L.
- Expectancy per realized close order is the arithmetic mean of exchange-reported realized P&L across winning and losing orders, before fees.
- Average win/loss ratio is average win divided by the absolute average loss.
- Streaks and the cumulative realized-P&L curve are calculated in chronological order using the earliest fill timestamp within each grouped order.
- Realized-order drawdown is measured in quote-currency units from the running peak of cumulative realized P&L. It is not account-equity drawdown and is not a percentage.
- Stablecoin fees in USDT/USDC are subtracted from realized P&L in the displayed net-after-known-fees metric. Other fee assets are itemized separately because historical conversion rates are not available. Negative fee values (rebates) are retained.

## Daily P&L and monthly view

Daily results use the existing non-additive reconciliation priority: Binance imported P&L, then manually entered daily P&L, then estimated manual-journal P&L. Overlapping sources are not added. Where manual and Binance rows overlap, the absolute difference is surfaced for review. Monthly summaries aggregate these selected daily values.

The daily balance-ledger drawdown and realized-order drawdown are separate measures and should not be compared as if they shared the same equity basis.

## Data-quality checks

The screen reports last successful sync age, failed symbol requests, malformed fill rows, realized-P&L coverage, and days where Binance and manual daily P&L differ by more than USD 1. A sample under 30 realized close orders is explicitly marked provisional.

These checks are diagnostics, not proof that exchange history is complete. API permissions, import date range, symbol coverage, account subaccounts, funding/commission history and exchange-side corrections can all affect completeness.

## Limitations

- Expectancy is before fees; the separate net-after-known-stable-fees value is not a full net result when commissions were charged in non-stable assets.
- Funding payments, rebates in assets without conversion rates, borrow interest and tax are not fully normalized into one reporting currency by this screen.
- P&L amounts from different quote assets should not be summed as USD unless the underlying records are USD/USDT-denominated. The volume panel explicitly groups quote assets rather than assuming conversion.
- The cumulative realized-P&L curve begins at zero and is not a capital-weighted return curve. No annualized return or Sharpe ratio is claimed.
- Small samples, incomplete history and changing market conditions make historical metrics descriptive rather than predictive.
