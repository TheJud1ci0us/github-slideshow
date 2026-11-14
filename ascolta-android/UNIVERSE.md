# Lingua Universe

Version 1.9.15 replaces the Aquarium entry in Profile with an optional 3D idle island. The existing podcast player, transcripts and saved vocabulary remain independent of the game.

## Playing

Drag to orbit the island; pinch to zoom. Tap a tile to unlock or upgrade it. Land also provides a screen-reader-friendly building list. Production runs automatically: forests produce wood, quarries stone, and the village, farms and sanctuaries produce coins. Expand across 61 neighboring hex tiles, upgrade buildings (a production doubling at each ten-level milestone), recruit workers and research production multipliers.

Foreign-language playback adds energy for a threefold production boost (up to 30 minutes stored) and one Insight per five minutes actually listened. Successful vocabulary reviews award one Insight per card per day, capped at 40 cards. Finishing an episode awards two Insight after actually listening for at least ten minutes or half of a shorter episode. Insight optionally halves research material costs; all research can instead be purchased with ordinary resources. Existing manual transcript correction rewards pay 30 ordinary coins, with their daily and duplicate limits retained.

After exploring 12 tiles and producing 10,000 coins, the portal offers a confirmed voyage to France, Japan, Italy or Brazil. A voyage resets buildings, ordinary resources, workers and research, adds permanent production stars, and preserves learning rewards and records. There is one active world at a time. The country themes alter procedural architecture and plants; this first version is an idle builder, with animated workers and an orbit camera, rather than a character-controlled action game.

## Persistence and battery

The immutable state is saved locally. Production is calculated from elapsed time when the game is visible, playback rewards arrive or an action occurs, capped at eight hours away. There is no background game job, wake lock or physics loop. While visible, the native OpenGL ES 2 view draws at most 30 frames per second; pausing animations uses on-demand redraws. Rendering pauses with the screen lifecycle. Geometry is rebuilt only when buildings, theme or worker count change, rather than on every resource tick.

The previous Aquarium save is retained intact. The first world imports its coins (up to ten million) and records its collected species/tier count as legacy relics. Loading the game again never repeats that import. An unreadable world save is preserved in a recovery preference before fallback initialization. Do not uninstall to upgrade: install the APK over the earlier app to retain its local data.

## Verification

`UniverseGameTest` exercises offline settlement, boost accounting, production milestones, cost/adjacency rules, caps, research, review/episode reward deduplication, migration inputs, prestige, mesh integrity and camera picking. `UniverseStoreTest` checks migration exactly once, retained legacy data, actual playback rewards, persistence, daily deduplication and recovery.

For a reproducible real graphics check:

1. Run the release JVM tests for `UniverseGameTest` and `UniverseStoreTest` (the latter exports production mesh fixtures).
2. Run `python tools/universe/render_preview.py` with Mesa EGL and Pillow available.
3. Run `UniverseUiTest` for interaction checks and phone, landscape, large-font, research and portal screenshots.

UI interaction tests also run on a clean checkout without preview files; their 3D slot stays empty until the graphics pipeline supplies frames.

The EGL check compiles the exact packaged GLSL shaders and renders the exact Kotlin-generated meshes with depth testing. It caught and fixed a shared-uniform precision mismatch between shader stages. The Compose screenshots embed those rendered frames because Robolectric does not provide a physical Android GPU surface. They are not screenshots from a physical phone. Device checks for GPU compatibility, touch feel and sustained battery use remain necessary before a production rollout. If graphics setup fails, an accessible Land list still allows the economy to be played.
