# Anonymous usage statistics

Ascolta can send anonymous usage statistics to [TelemetryDeck](https://telemetrydeck.com)
(privacy-first analytics, EU). It is **opt-in**: new users choose on the last onboarding page
(switch off by default); existing users are asked once; anyone can change it in
Settings → Privacy. Builds without an App ID contain the code but never collect or send anything.

## Turning it on

1. Create a free account at telemetrydeck.com and add an app ("Ascolta").
2. Copy its **App ID** (a UUID) into `ascolta-android/gradle.properties`:
   `telemetryDeckAppId=…` (the salt is already set; never change it, or user counts restart).
3. Build. Debug builds send signals marked as *test mode*: switch the dashboard's
   "Test Mode" toggle to see them; release builds appear in normal mode.

## What is sent

Every signal carries: app version, Android version, phone brand/model, app locale, the
language being learned, the native language, and how many languages are learned. Users are a
random ID created on the phone, SHA-256-hashed with the salt. Never sent: podcast/episode
names, feed URLs, search text, transcripts, translations, saved words, anything typed.

| Signal | Parameters (floatValue) |
|---|---|
| `TelemetryDeck.Session.started` | app launch |
| `TelemetryDeck.Navigation.pathChanged` | screen route template (library, discover, player/{episodeId}, …) |
| `TelemetryDeck.Activation.onboardingCompleted` | languages, number of levels/interests set |
| `TelemetryDeck.Error.occurred` | crash type and code location (sent at next launch) |
| `Language.switched` | to |
| `Discover.searched` / `Discover.themeBrowsed` / `Discover.levelFilter` | language / genre id / band |
| `Podcast.followed` / `Podcast.favourited` / `Podcast.movedLanguage` | podcast language, library |
| `Episode.played` | downloaded or streamed, imported file |
| `Listening.time` | language (minutes) |
| `Transcription.requested` / `started` / `completed` / `failed` | model, language, gpu, threads, error type (episode minutes) |
| `Transcription.speed` | model, language, threads, gpu (× real time per block) |
| `SpeedTest.completed` | model, threads, gpu, accuracy (× real time) |
| `Word.saved` | word or sentence, audio clip, from episode, language |
| `Flashcard.reviewed` | grade, word/sentence, language |
| `SleepTimer.set` | minutes |
| `Badge.earned` | badge id |
| `Settings.changed` | setting key and new value (model per language, furigana/romaji, translation, network mode, GPU, …) |

## Useful insights to create in the dashboard

- Daily/weekly active users, new users, retention (built in).
- Languages learned: breakdown of `Ascolta.learning`.
- Speech models: `Transcription.started` by `model`; average `Transcription.speed` floatValue by
  `model` and `TelemetryDeck.Device.modelName` (which phones run which model well).
- Failures: `Transcription.failed` by `model` and `error`; crashes by `TelemetryDeck.Error.id`.
- Engagement: sum of `Listening.time` floatValue per day; `Flashcard.reviewed` count; `Word.saved`
  by `kind`.
- Features: `Settings.changed` by `setting`/`value` (e.g. how many choose romaji vs furigana),
  `SleepTimer.set`, `Discover.themeBrowsed` by genre.
