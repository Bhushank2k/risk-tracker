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
- Dedicated **Developer · About** tab with project information, design notes, repository link, and next steps
- Responsive desktop sidebar and mobile bottom navigation
- Dark/light theme toggle, accessible status announcements, and reduced-motion support
- No JavaScript framework or build step required

## Important data note

The dashboard currently uses **illustrative demo values only**. It does not connect to an exchange, provide live prices, calculate real liquidation levels, or execute trades. Connect a trusted backend and validate risk calculations before relying on this UI for financial decisions. Never expose exchange API secrets in client-side code.

## Design direction

Material 3 Expressive-inspired: generous rounded surfaces, clear typography, semantic risk colors, responsive navigation, and a risk-first information hierarchy.

## Repository

https://github.com/Bhushank2k/risk-tracker
