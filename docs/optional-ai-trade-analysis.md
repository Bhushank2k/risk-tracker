# Optional AI trade explanations

Daily Trade Ideas includes a disabled-by-default, on-demand AI explanation layer.

## Behaviour

- The Binance scanner and TradeIdeasV2 remain the source of all signal, entry, stop-loss, target and risk calculations.
- AI is called only when the user enables the feature, configures a backend URL/token, and taps Explain with AI on a specific idea.
- Disabling the feature hides the AI action. A missing or unavailable backend does not interrupt scanning.
- AI output is informational and may be inaccurate; it cannot alter scanner data, paper trades, notifications, tracking or orders.
- The Android client sends only the selected idea's scanner fields to the configured backend. It does not send Binance API credentials.

## Backend setup

See the Cloudflare Worker deployment instructions in backend/cloudflare-worker/README.md. The Worker stores OPENAI_API_KEY and APP_ACCESS_TOKEN as server-side secrets. Never put an OpenAI key in the APK, app preferences, source code or GitHub.

## Costs and safety

AI requests may incur OpenAI API charges. Calls are manual rather than automatic to limit unexpected usage. Configure Cloudflare rate limiting and OpenAI project spend limits before sharing the app. AI analysis is not financial advice and does not predict outcomes.
