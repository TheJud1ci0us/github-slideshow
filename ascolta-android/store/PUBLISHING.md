# Publishing LingoPod on Google Play — checklist

Files in this folder: `listing/` (texts, 6 languages), `icon-512.png`, `feature-graphic-1024x500.png`,
`data-safety.md`, `foreground-services.md`, `privacy.html`, `server-setup.md`.
Kept outside the repository (sent separately): `lingopod-upload.jks` + its passwords,
`pro-pass-private-pkcs8.pem`. Keep two copies of each, somewhere safe.

> **Before uploading a build to Play: turn the paywall on.** It is off while testing (everything
> unlocked). Add `lingopodPaywall=true` to `ascolta-android/gradle.properties`, then rebuild
> (`./gradlew bundleRelease`). With it off, Pro would be free for everyone.

1. **Developer account** — play.google.com/console → *Personal*, pay $25, verify your identity
   (ID document) and your phone (Play Console app). A few days.
2. **Payments profile** — Console → Settings → Payments profile: bank account and tax details.
3. **Purchase-check server** — follow `server-setup.md` (≈30 min). Then put the URL and the
   project number in `gradle.properties` (`lingopodServerUrl`, `lingopodCloudProjectNumber`) and
   rebuild the bundle (`./gradlew bundleRelease`; ask me to).
4. **Create the app** — *Create app*: name **LingoPod**, language English, **App**, **Free**.
5. **Privacy policy** — publish `privacy.html` (replace CONTACT_EMAIL) e.g. with GitHub Pages,
   paste its URL in App content → Privacy policy.
6. **App content** (Dashboard → "Set up your app"):
   - App access: *All functionality is available without special access*
   - Ads: **No**
   - Content rating: questionnaire → category *Reference, News, or Educational*; answer No to violence etc.
   - Target audience: **13+** (not designed for children)
   - Data safety: copy `data-safety.md`
   - News app: No · Government app: No · Financial features: No · Health: No
   - Foreground services: copy `foreground-services.md` and upload the three short videos
7. **Main store listing** — `listing/en.txt` (+ translations under *Manage translations*), the icon,
   the feature graphic and 4–8 phone screenshots (Home, Discover, player with transcript, word
   sheet, Words, Statistics). Category: **Education**. Contact e-mail.
8. **Products** — Monetize with Play → Products:
   - Subscriptions: `pro_monthly` (base plan monthly, €4.99), `pro_yearly` (base plan yearly,
     €29.99) — no free trial. Activate.
   - In-app products: `pro_lifetime` (€59.99). Activate.
   - Settings → License testing: add your Gmail address (your test purchases are free).
9. **Internal testing** — Testing → Internal testing → Create release → upload
   `lingopod-1.0.0.aab` → testers: your e-mail → install from the opt-in link. Try: setup tour,
   transcription (30 free minutes), buying Pro (license tester), Restore purchases.
10. **Closed testing** (required for personal accounts) — Testing → Closed testing → create a
    track, upload the same bundle, add **12+ testers** (a Google Group or e-mail list), share the
    opt-in link. They must stay opted in for **14 days in a row**.
11. **Production** — Dashboard → *Apply for production* (questions about the test), then
    Production → Create release → the bundle → staged rollout 20 % → after a few days 100 %.

Updates: raise `versionCode`/`versionName` in `app/build.gradle.kts`, `./gradlew bundleRelease`,
upload to the same tracks.
