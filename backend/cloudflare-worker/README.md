# Optional AI explanation proxy

This Worker is an optional backend for Risk Tracker's on-demand trade-idea explanations. It keeps the OpenAI API key server-side. The Android app sends only a compact copy of the scanner's already-calculated setup to this Worker.

## Deploy

1. Install Node.js and Wrangler (Cloudflare's CLI).
2. From this directory, authenticate with Wrangler: wrangler login.
3. Set a long random app-to-backend token: wrangler secret put APP_ACCESS_TOKEN.
4. Set your OpenAI project API key: wrangler secret put OPENAI_API_KEY.
5. Deploy: wrangler deploy.

Wrangler prints the deployed URL, for example https://risk-tracker-ai.your-subdomain.workers.dev. Add /analyze to it in the Android app.

## Configure the Android app

On Daily Trade Ideas:
1. Enable Optional AI explanations.
2. Paste the Worker URL ending in /analyze.
3. Paste the same APP_ACCESS_TOKEN value you set as a Worker secret.
4. Tap Save AI connection.
5. Tap Explain with AI on a trade idea when you want an explanation.

AI calls are manual and per-idea; no requests are made while scanning or when the feature is off. The OpenAI key is never entered in the app.

## Production safeguards

- Configure a Cloudflare rate-limiting rule for /analyze and monitor Worker analytics.
- Set OpenAI project usage alerts and a hard spend limit appropriate for your budget.
- Rotate APP_ACCESS_TOKEN and OPENAI_API_KEY if exposed.
- Treat the app token as a shared access credential, not strong user identity. A determined user can extract a token from a client app; rate limiting and spend controls are important.
- Use HTTPS only. Never commit either secret to Git.
- The Worker sends no Binance credentials and does not place orders.
