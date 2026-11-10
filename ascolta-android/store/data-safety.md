# Google Play: Data safety answers for LingoPod 1.0

**Does your app collect or share any of the required user data types?** → **Yes** (only the
optional anonymous usage statistics and purchase checks below).

**Is all of the user data collected by your app encrypted in transit?** → **Yes** (HTTPS).

**Do you provide a way for users to request that their data is deleted?** → **Yes**: usage
statistics are anonymous and can be turned off in Profile → Privacy; nothing else is stored off
the phone. Uninstalling removes everything.

## Data types

| Category | Type | Collected | Shared | Optional? | Purpose |
|---|---|---|---|---|---|
| App activity | App interactions | Yes | No | **Optional** (asked at first launch) | Analytics |
| App info and performance | Crash logs / Diagnostics | Yes | No | **Optional** (same switch) | Analytics |
| Financial info | Purchase history | Yes | No | Required for Pro | App functionality (checking the purchase with Google) |
| Device or other IDs | Device or other IDs | Yes | No | Required for Pro | Fraud prevention (Play Integrity, a random app-made ID) |

Processed only on the phone and **not collected** (answer *No* for these): audio / voice recordings
(shadowing stays on the device), photos, files, location, contacts, messages, personal info.

Notes for the form:
- "Ephemeral processing": purchase checks are processed and not stored by the server.
- No data is sold; no advertising; no third-party SDK except Google Play services and the
  optional TelemetryDeck counts (anonymous, salted and hashed).
