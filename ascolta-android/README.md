# Ascolta: learn languages with podcasts (Android)

Ascolta is a podcast player that generates a **transcript on your phone** for any
episode and shows a **translation under every sentence**. It's built for learning
Italian with English as the native language, and also supports Spanish, French,
German, Portuguese, Japanese and Chinese.

There are no AI services, no API keys and no servers. Speech recognition
([whisper.cpp](https://github.com/ggml-org/whisper.cpp) or NVIDIA Parakeet through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)) and translation
([ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation))
all run locally. The models are downloaded once; audio and transcripts never leave
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
  (Wi-Fi only by default). Transcription is chosen per episode with **Transcribe** (or switched
  on for every download in Settings); without a transcript the player shows the episode cover.
- If a podcast publishes its own transcript (`<podcast:transcript>`, VTT/SRT/JSON),
  that is used instead of running speech recognition.
- **Transcription follows what you listen to**, like a video buffer: jump from minute 1 to
  minute 20 and it drops the current block within a second and starts at minute 20 (a short
  first block so text appears quickly). Finished parts are kept; go back to minute 1 and it
  continues there. It only ever transcribes a **buffer zone of 20% of the episode's duration** ahead of
  where you are in the timeline (playing or paused; e.g. 12 minutes of a 60-minute episode), then
  waits and frees the model's memory; it continues as you listen past one block of it. Nothing
  behind you or beyond the zone is transcribed. Listening with the phone locked or the
  app in the background pauses it; opening the app restarts it right where you are.
- Blocks end in a pause (never mid-word) and repeated speech is kept once. An interrupted job continues where it stopped.
- **Network mode**: *Wi-Fi only* (episodes, models, feeds and translation models download on
  Wi-Fi; streaming on mobile data asks first) or *Wi-Fi and mobile data*.

**Fast, controllable transcription**
- Choose the engine in Settings: Whisper (Tiny/Base/Small/Large v3 Turbo) or
  **NVIDIA Parakeet TDT 0.6B v3** (fast and very accurate for 25 European languages; greyed out
  for other languages, and podcasts in other languages use Whisper).
- **Skips music and silence** with a voice-activity model, so only speech is processed.
- whisper.cpp is built for several CPU generations; the app loads the fastest one the
  phone supports (e.g. dot-product and int8 matrix instructions on recent Snapdragons).
- Choose how many CPU cores to use (1–8) and toggle Whisper's flash attention.
- **Work split across the chip**: the next block of audio is decoded and searched for speech on
  the efficiency cores while the current one is recognised on the fast cores (thread affinity).
- **Speed test** in Settings: the same bundled 30 s recording every time; one warm-up run, then
  three timed runs (median and spread), plus word accuracy.
- **Stop** a running transcription (what's done is kept; *Continue* resumes), or remove
  waiting items from the queue.

**Languages**
- On first launch, choose the languages you're learning (57 available, each with its flag).
  Each language has its **own library** of podcasts, words and flashcards; switch with the
  **flag bar** on the Library, Discover and Words screens. Podcasts go into their own
  language's library automatically and can be moved (podcast menu → *Move to another language*).

**Finding podcasts by theme and level**
- Browse by **theme** (news, history, culture, science, comedy, kids, language courses, …) from
  Apple's charts in the learning language's country, with English shows filtered out, or search.
- Each result shows an **estimated CEFR level** (from an explicit level in the title, words such
  as *facile / lento / beginner*, the Language Learning genre, or native content = C1–C2) and
  can be filtered by A1–A2 / B1–B2 / C1–C2. The first launch asks your level and themes.

**Favourites, feeds and storage**
- ★ **Favourite** podcasts are listed first and send a **notification** when a new episode is out.
- Per-podcast automatic download; feed check every 1/3/6/12/24 hours or only by hand.
- Downloaded episodes can be **deleted automatically** after 1–30 days (transcripts are kept).
- Theme: like the phone, light or dark; colours from the wallpaper or Ascolta's own.

**Flashcards**
- Saved words keep their **sentence and its translation** (the word highlighted in it) and a
  **clip of the original audio** of that sentence (downloaded episodes), played in the Words list
  and when a card is turned during review.
- Save a word or the whole sentence from the player, or **add your own cards** (word or
  sentence; translated automatically if you leave the meaning empty).
- **Review** in the app with spaced repetition (Again / Hard / Good / Easy, as in Anki).

**Learning**
- Dual subtitles: the Italian sentence with the English translation under it, synced
  to the audio, with the current sentence and the current **word** highlighted and auto-scrolled
  (exact seeking in variable-bit-rate MP3s keeps the highlight in sync).
- Tap a word to translate it and save it to **Words**, or **save the whole sentence** for context.
- Word-by-word highlighting can be turned off (player menu → *Highlight each word*). Export to Anki as tab-separated text.
- **Sleep timer** (5–60 min or end of episode) that stops at the **end of the sentence** being
  spoken, with a short fade, instead of mid-word.
- **Progress**: hours listened per language with **listening milestones** (1, 5, 10, 25, 50, 100,
  250, 500, 1000 h), daily streak, and badges for streaks, saved words, reviews, transcripts and
  languages; a celebration when one is earned (a notification if the app is in the background).
- Previous / next / repeat sentence, **pause after each sentence**, 0.5×–1.5× speed.
- Hide either language to test yourself; adjustable text size. The highlighted sentence and
  its translation always stay fully on screen.
- **Transcript only**: switch off *Translate sentences* (Settings or the player menu).
- Show the **episode picture** instead of the transcript at any time (top-bar button); the
  transcript is kept and comes back with the same button. Under the picture, one line shows
  what is being said, following the current word.
- **57 languages** to learn and translate (all supported by both Whisper and ML Kit); each speech
  model lists its languages in Settings, and Parakeet's 25 European languages are marked.
- Background playback with lock-screen and notification controls.
- App interface in English, Italian, French, Spanish, German or Portuguese
  (Settings → App language, or Android's per-app language setting).

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

The first build downloads whisper.cpp v1.7.6 and the sherpa-onnx 1.12.40 Android
library (checksum-verified, into `app/libs/`) from GitHub, and compiles whisper.cpp for
several CPU generations, which takes a few minutes. To build offline, clone it once into `third_party/whisper.cpp`:

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

| Model | Download | Speed | Accuracy | Languages |
|---|---|---|---|---|
| Whisper Tiny | 32 MB | fastest | OK for slow, clear speech | all |
| **Whisper Base** (default) | 60 MB | fast | decent | all |
| Whisper Small | 190 MB | ~3× slower than Base | noticeably better | all |
| Whisper Large v3 Turbo | 574 MB | slow | best Whisper; needs ~8 GB RAM | all |
| **NVIDIA Parakeet v3** | 670 MB | fast | very good, with punctuation | 25 European (not ja/zh) |

In a test on Italian speech, Parakeet transcribed perfectly where Whisper Base made
mistakes, and was about 3× faster. Run the speed test in Settings to compare models,
core counts and flash attention on your own phone. Use *Re-transcribe* on an episode
after switching models.

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
| `whisper/` | Speech engines (whisper.cpp JNI, Parakeet via sherpa-onnx), voice detection, audio decoding, models, speed test |
| `net/` | RSS/OPML parsing, published transcript parsing, podcast search, link resolution |
| `data/` | Room database, settings, transcript storage, repository |
| `work/` | WorkManager jobs: feed refresh, downloads, transcription |
| `translate/` | ML Kit on-device translation |
| `player/` | Media3 playback service and controller |
| `ui/` | Jetpack Compose screens |

## Legal

- [Privacy policy](PRIVACY.md) and [terms of use](TERMS.md), also in the app (Settings → About).
- Open-source licenses and model/data credits are in the app (Settings → About →
  *Open-source licenses and credits*), including the CC BY 4.0 attribution for NVIDIA Parakeet
  and for the speed-test recording. No Qualcomm or other proprietary runtime is bundled.

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
