# Risk Tracker — Telegram cloud alert service

This is the notification transport foundation, not the market scanner. It runs as a small stateless Cloud Run container and accepts alerts from a scanner only after that scanner has evaluated the existing Risk Tracker strategy.

## Safety contract

- Accepts only READY + Intraday alerts.
- Requires a non-empty risk_gates object where every supplied hard gate is true.
- Rejects alerts older than ALERT_MAX_AGE_SECONDS (default 300 seconds).
- Sends no orders and never connects to an exchange account.
- Stores no alert history or credentials in a database.
- Does not weaken, duplicate, or replace the app's confluence/risk calculations.

## Not yet implemented

Binance market scanning and parity with the Android scanner are not included in this first stage. The current scanner is embedded in Android app code and depends on Android-specific APIs/models. It must be extracted into a shared JVM module or ported with fixture-based parity tests before a cloud scheduler can safely trigger it. Do not treat this relay as a running scanner.

## Local test and run

Python 3.12; no third-party packages or database.

    cd cloud-alerts
    python -m unittest -v
    ALERT_API_TOKEN='long-random-secret' TELEGRAM_BOT_TOKEN='bot-token' TELEGRAM_CHAT_ID='chat-id' python app.py

Health: GET /healthz
Alert endpoint: POST /alerts

Example payload (created_at must be the current Unix timestamp):

    {
      "alert_id":"BTCUSDT-LONG-intraday-unique",
      "symbol":"BTCUSDT", "side":"LONG", "setup":"Trend pullback",
      "horizon":"Intraday", "status":"READY", "created_at": 0,
      "entry":100, "stop":98, "target1":104,
      "risk_gates":{"liquidity":true,"funding":true,"net_rr":true}
    }

Call with Authorization: Bearer <ALERT_API_TOKEN> and Content-Type: application/json.

## Cloud Run deployment

Create a Telegram bot with BotFather, send it a message from the target chat, and obtain the chat ID. Create three Secret Manager secrets named ALERT_API_TOKEN, TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID. Never commit their values.

    gcloud run deploy risk-tracker-alerts --source cloud-alerts --region REGION --no-allow-unauthenticated --set-env-vars ALERT_MAX_AGE_SECONDS=300 --set-secrets ALERT_API_TOKEN=ALERT_API_TOKEN:latest,TELEGRAM_BOT_TOKEN=TELEGRAM_BOT_TOKEN:latest,TELEGRAM_CHAT_ID=TELEGRAM_CHAT_ID:latest

Grant the Cloud Run service identity access to those secrets. Restrict invokers with Cloud Run IAM; the bearer token is an additional application guard, not a substitute for IAM. Use a separate caller identity for the future scanner.

## Deduplication limitation

Duplicate suppression is process-local for 24 hours by default. Cloud Run can run multiple instances or restart containers, so this is best-effort only. With no database, durable exactly-once delivery is not possible. The future scanner must also deduplicate within each scan cycle and use stable alert IDs.

## Environment

- ALERT_API_TOKEN (required)
- TELEGRAM_BOT_TOKEN (required)
- TELEGRAM_CHAT_ID (required)
- PORT (default 8080)
- ALERT_MAX_AGE_SECONDS (default 300)
- DEDUP_TTL_SECONDS (default 86400)
- MAX_DEDUP_ENTRIES (default 2000)

No Firebase, Firestore, SQL, or other database is used.
