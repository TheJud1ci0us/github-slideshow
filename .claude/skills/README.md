# Agent skills for LingoPod

Third-party skills, reviewed and copied unchanged (Claude loads a skill only when its task comes up).

| Skill | Source | License |
|---|---|---|
| android-emulator-skill | new-silvermoon/awesome-android-agent-skills @ 82900ea | Apache-2.0 |
| footgun-scan, state-and-background-footguns, media-playback-footguns, data-layer-footguns, compose-screens-footguns, compose-visuals-footguns | maxrave-dev/kotlin-footguns @ 2c391bc | GPL-3.0 (see LICENSE-kotlin-footguns-GPL-3.0) |
| optimizing-lazy-layouts | skydoves/compose-performance-skills @ 1b32f81 | Apache-2.0 |

The footgun skills are guidance for development only: don't copy their code examples into the
app if it is distributed commercially (GPL-3.0). footgun-scan opens trap files in the other
*-footguns skills, which is why those are included. android-emulator-skill needs adb and an
emulator, i.e. a session on a computer with Android Studio (not the cloud sandbox).
