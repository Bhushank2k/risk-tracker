# Risk Tracker cloud Intraday scanner

This is the scheduled cloud execution of the **same Kotlin Confluence V4 engine used by the Android app**. The strategy and risk logic live in `scanner-core/`; both `:app` and `:cloud-scanner` depend on that module. The cloud worker does not contain a second Python strategy implementation.

## Scan contract

- Public Binance USD-M Futures data only; no API key or exchange account is used.
- Universe: active USDT perpetual contracts with 24-hour quote volume at least USD 2,000,000.
- BTC context: the same H4/H1 EMA20/50/200 regime classifier as the Android scanner.
- Per symbol: completed H4, H1, M15 and M5 candles, best bid/ask, five-level order-book depth and current premium-index funding rate.
- Candle rows whose close time has not passed are excluded. The shared engine also enforces timeframe-specific freshness.
- Five-level depth notional, spread and directional imbalance use the same calculations as `BinanceFuturesStream`.
- Only a READY Intraday plan with a supported timeframe, confirmed entry, score >=75, valid stop/targets, all hard gates true and verified funding can be sent to the relay.
- Scalp and Swing are not cloud-alert horizons. Open interest is omitted because it is supplementary context, not a hard gate.
- No exchange credentials, order placement, position management or trade execution.

The cloud job scans every market above the quote-volume floor; it does not silently cap the universe to a top-N shortlist. REST requests are paced and bounded by a six-worker pool. Binance 418/rate-limit bans stop the job rather than weakening gates or reusing stale data.

## Local build and tests

From the repository root, with Java 17 and Gradle 8.13:

    gradle :scanner-core:test :cloud-scanner:test :cloud-scanner:installDist --no-daemon

The scanner requires these environment variables to actually send alerts:

- `ALERT_RELAY_URL`: HTTPS Cloud Run URL of the existing `cloud-alerts` service (the worker appends `/alerts` if needed).
- `ALERT_API_TOKEN`: same high-entropy application bearer token configured on the relay.

Run locally with:

    ALERT_RELAY_URL='https://YOUR-RELAY-URL' ALERT_API_TOKEN='YOUR-SECRET' cloud-scanner/build/install/cloud-scanner/bin/cloud-scanner

## Deploy as a Cloud Run Job

Prerequisites: enable Cloud Run, Cloud Build, Artifact Registry, Cloud Scheduler and Secret Manager APIs. Choose a project, region and Artifact Registry Docker repository. Create a Secret Manager secret named `ALERT_API_TOKEN` containing the same token configured on the alert relay. Grant the Cloud Run Job service account `roles/secretmanager.secretAccessor` on that secret.

Build the container from the repository root (the Docker build context must include `scanner-core/`):

    export PROJECT_ID="YOUR_PROJECT_ID"
    export REGION="YOUR_REGION"
    export IMAGE="$REGION-docker.pkg.dev/$PROJECT_ID/containers/risk-tracker-scanner:latest"
    gcloud builds submit --config cloud-scanner/cloudbuild.yaml --substitutions=_IMAGE="$IMAGE" .

Create the job. Replace the relay URL and service account with your deployed values:

    gcloud run jobs create risk-tracker-cloud-scanner \\
      --image "$IMAGE" --region "$REGION" --tasks 1 --parallelism 1 \\
      --max-retries 0 --task-timeout 15m \\
      --service-account "risk-tracker-scanner@$PROJECT_ID.iam.gserviceaccount.com" \\
      --set-env-vars "ALERT_RELAY_URL=https://YOUR-RELAY-URL" \\
      --set-secrets "ALERT_API_TOKEN=ALERT_API_TOKEN:latest"

Grant that service account Secret Manager access, then test a one-off execution:

    gcloud run jobs execute risk-tracker-cloud-scanner --region "$REGION" --wait

### Run every 15 minutes

Create a dedicated Scheduler service account and grant it `roles/run.invoker` on the project/job. Then create the authenticated Scheduler trigger:

    gcloud scheduler jobs create http risk-tracker-cloud-scanner-15m \\
      --location "$REGION" --schedule "*/15 * * * *" --time-zone "UTC" \\
      --uri "https://run.googleapis.com/v2/projects/$PROJECT_ID/locations/$REGION/jobs/risk-tracker-cloud-scanner:run" \\
      --http-method POST --oauth-service-account "risk-tracker-scheduler@$PROJECT_ID.iam.gserviceaccount.com" \\
      --message-body '{}'

Check Cloud Run Job execution logs after the first run. The worker prints market counts, data errors and relayed READY alerts. A scan with zero READY setups is a normal outcome.

## No-database guarantees and limits

- No Firebase, Firestore, SQL, Redis or other database is used.
- The cloud scanner job is stateless and stores no market snapshots or alert history.
- The alert relay's deduplication and recent-alert list are process-local. They reset when the relay instance restarts or scales down. There is no durable exactly-once guarantee; a relay restart can allow an old alert ID to be delivered again.
- The mobile app polls the relay while the Cloud Alerts screen is open; it does not receive push notifications while closed.
- If a market-data request fails, that symbol produces no alert. If the relay rejects a post, the job fails visibly and the next scheduled run can retry. No failed gate is converted to true.

This is informational signal delivery only. Verify the current order book, funding and price before making any trading decision.
