# Ascolta: learn languages with podcasts (Android)

Ascolta is a podcast player that generates a **transcript on your phone** for any
episode and shows a **translation under every sentence**. It's built for learning
Italian with English as the native language, and also supports Spanish, French,
German, Portuguese, Japanese and Chinese.

There are no AI services, no API keys and no servers. Speech recognition
([whisper.cpp](https://github.com/ggml-org/whisper.cpp)) and translation
([ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation))
both run locally. The models are downloaded once; audio and transcripts never leave
the phone.

## Features

**Getting podcasts**
- **Search** millions of shows (open Apple Podcasts directory, no account needed) and follow them.
- **Paste or share a link**: RSS feed, Apple Podcasts, or **Spotify**. Spotify doesn't
  give apps its audio, so for a Spotify link Ascolta reads the show's name and finds
  the same show's public RSS feed. In the Spotify app: *Share → Ascolta*.
- **Import audio files** (MP3, M4A, …) from your phone, or *Open with → Ascolta*.
- **Import OPML** subscriptions exported from Pocket Casts, AntennaPod, Overcast, etc.

**Automatic, like Spotify**
- Feeds are checked every 6 hours. New episodes of followed shows are downloaded
  (Wi-Fi only by default) and then transcribed in the background.
- If a podcast publishes its own transcript (`<podcast:transcript>`, VTT/SRT/JSON),
  that is used instead of running speech recognition.
- Transcription works in 5-minute chunks and saves after each one. You can read the
  first sentences while the rest is still processing, and an interrupted job
  continues where it stopped.

**Learning**
- Dual subtitles: the Italian sentence with the English translation under it, synced
  to the audio, with the current sentence highlighted and auto-scrolled.
- Tap a word to translate it and save it to **Words**. Export to Anki as tab-separated text.
- Previous / next / repeat sentence, **pause after each sentence**, 0.5×–1.5× speed.
- Hide either language to test yourself; adjustable text size.
- Background playback with lock-screen and notification controls.

## Building

Requirements: Android Studio (Ladybug or newer) **or** JDK 17+ and the Android SDK
with **NDK 27.2.12479018** and **CMake 3.22.1** (Android Studio installs them on
first build if missing).

```bash
cd ascolta-android
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected phone (USB debugging on)
./gradlew testDebugUnitTest      # unit tests (parsers, resampler, …)
```

The first build downloads whisper.cpp v1.7.6 from GitHub and compiles it, which takes
a few minutes. To build offline, clone it once into `third_party/whisper.cpp`:

```bash
git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
```

Run on a **real phone**. The emulator works but whisper is very slow there.

## First run

1. Open **Settings** and check the languages (default: Italian → English) and the
   speech model. The model downloads automatically the first time it's needed;
   tap **Get** to download it in advance on Wi-Fi.
2. **Discover** → search (e.g. "Italiano semplice") → **Follow**. The newest episode
   starts downloading and transcribing.
3. Open the episode. Transcribed sentences appear as they're ready.

### Choosing a speech model

| Model | Download | Speed | Accuracy |
|---|---|---|---|
| Tiny | 32 MB | fastest | OK for slow, clear speech |
| **Base** (default) | 60 MB | fast | decent |
| Small | 190 MB | ~3× slower than Base | noticeably better (recommended if your phone is recent) |
| Large v3 Turbo | 574 MB | slow | best; needs ~8 GB RAM |

On a recent phone, Base usually processes an hour of audio in well under an hour.
Small takes longer but makes fewer mistakes with fast native speech. Use
*Re-transcribe* on an episode after switching models.

## How it works

```
RSS feed ──► Room DB ──► DownloadWorker ──► TranscribeWorker ──────────────► transcript JSON
                                             │ published transcript? use it    │
                                             │ else MediaCodec → 16 kHz PCM    │
                                             │      → whisper.cpp (JNI)        │
                                             └ ML Kit translation per sentence ┘
PlayerScreen ◄── Media3 (PlaybackService) + transcript ──► synced dual subtitles
```

| Path | What |
|---|---|
| `app/src/main/cpp/` | CMake build of whisper.cpp + JNI bridge |
| `whisper/` | Kotlin bindings, audio decoding/resampling, model downloads |
| `net/` | RSS/OPML parsing, published transcript parsing, podcast search, link resolution |
| `data/` | Room database, settings, transcript storage, repository |
| `work/` | WorkManager jobs: feed refresh, downloads, transcription |
| `translate/` | ML Kit on-device translation |
| `player/` | Media3 playback service and controller |
| `ui/` | Jetpack Compose screens |

## Known limitations

- **Spotify-exclusive** and paywalled shows can't be played; there is no public feed.
  Private feeds (Patreon, Supercast) work: paste their RSS link.
- Whisper occasionally splits a sentence at a 5-minute chunk boundary, and can mishear
  names. Larger models help.
- Word tap selects whole runs of characters, so it works well for languages with spaces
  (Italian, Spanish…) but not yet for Japanese/Chinese word segmentation.
- The arm64 build targets ARMv8.2 (practically every phone since ~2018).
- Long transcriptions run as a foreground job with a notification. If Android won't
  allow that (e.g. started while the app is in the background), the job runs in
  shorter stretches and resumes automatically.
