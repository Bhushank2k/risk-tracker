# Risk Tracker

Crypto futures trading tracker — with a Material 3 Expressive-inspired responsive UI prototype.

## Preview

Open [index.html](./index.html) directly in a browser, or serve this folder locally:

```bash
python -m http.server 8000
```

Then visit http://localhost:8000.

## UI prototype features

- Risk overview with portfolio summary cards and illustrative equity visualization
- Positions table with risk labels and CSV export
- Risk analytics and an interactive risk checklist
- Trade journal with long/short realized P&L estimates, fees, searchable history, CSV export, equity curve, win rate, profit factor, and maximum drawdown
- Browser journal entries persist locally in that browser; they are not synced to Android or Google Sheets
- Dedicated **Developer · About** tab with project information, project details, design notes, and next steps
- Responsive desktop sidebar and mobile bottom navigation
- Dark/light theme toggle, accessible status announcements, and reduced-motion support
- No JavaScript framework or build step required

## Android apps

- `app/` is the native Jetpack Compose app (daily PNL, trade journal and analytics, position calculator, leverage table, Google Sheet sync, About). It follows the system light/dark theme and uses dynamic color on Android 12+.
- `android/` wraps this web prototype in a WebView; CI copies `index.html` and `logo.svg` into it.

Both use the adaptive launcher icon in `res/` (with a monochrome layer for themed icons), generated from `logo.svg`.

Run the calculator unit tests with `gradle testDebugUnitTest`.

## Important data note

The dashboard's portfolio/position overview currently uses **illustrative demo values only**. The trade journal is user-entered and stores browser data locally; Android journal entries are stored locally on-device and are not synchronized with the browser journal. It does not connect to an exchange, provide live prices, calculate real liquidation levels, or execute trades. Connect a trusted backend and validate risk calculations before relying on this UI for financial decisions. Never expose exchange API secrets in client-side code.

## Design direction

Material 3 Expressive-inspired: generous rounded surfaces, clear typography, semantic risk colors, responsive navigation, and a risk-first information hierarchy.

## Repository

https://github.com/Bhushank2k/risk-tracker
