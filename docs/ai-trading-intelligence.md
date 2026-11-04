# AI Trading Intelligence

This module adds four user-triggered OpenAI analyses to the Android app:

1. Trade journal summaries
2. Behavioral pattern explanations
3. Weekly performance reviews
4. Personalized risk-discipline observations

## Privacy and consent

- Analysis is disabled until a user explicitly configures an OpenAI API key.
- Every report requires an unchecked-by-default consent checkbox and a separate Generate action.
- No scheduled or background AI requests are created.
- The request payload is minimized to journal fields, self-authored review fields, daily ledger entries and source-attributed Binance execution fields.
- Exchange API credentials, exchange account identifiers, trade IDs and screenshot URIs are not sent.
- The report is stored locally in app preferences and can be deleted from the AI screen.
- The OpenAI key is encrypted at rest using an AES-GCM key held by Android Keystore. The key is not part of backup/export payloads.
- The user can remove the key from the AI screen.

## OpenAI configuration

The app calls the OpenAI Chat Completions API using `gpt-4o-mini`. The user supplies their own API key in the AI Trading Intelligence screen. Requests are made only after explicit per-report consent.

**Deployment security note:** A client-side mobile app cannot fully protect a user-supplied API key on a compromised or rooted device. Keystore encryption protects the key at rest but does not make a distributed mobile client a trusted secret boundary. For a public multi-user release, replace direct client-to-OpenAI requests with an authenticated server-side proxy, enforce per-user quotas, and keep the OpenAI key exclusively in server secrets. Do not ship a shared API key in the APK.

## Data semantics

- Manually journaled P&L is an estimate derived from entry, exit, quantity, direction and fees.
- Binance fill rows are executions, not closed positions. The prompt and payload preserve this distinction.
- Exchange-reported realized P&L is included only when present on the source fill.
- Missing realized P&L is represented as unavailable, never as zero.
- Weekly reports include current local calendar week trades and executions from Monday onward.
- The request includes data-quality counts and a note describing P&L provenance.
- The AI is instructed not to invent metrics, infer causation, make predictions, or issue buy/sell/leverage recommendations.

## User flow

1. Open the overflow menu and select **AI Trading Intelligence**.
2. Add an OpenAI API key. It is encrypted using Android Keystore.
3. Select one of the four report types.
4. Review the privacy disclosure and explicitly check consent.
5. Tap Generate. The request runs on a background dispatcher.
6. Read the result or delete the locally saved report.

## Validation

Run:

```bash
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
```

Tests cover minimized payload content, exclusion of private trade identifiers and screenshot URIs, review adherence fields, and weekly date filtering.
