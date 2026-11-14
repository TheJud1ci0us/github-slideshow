# Discover loading changes in 1.9.14

The old first-page path waited for a global recommendation pass: multiple directory searches/charts, RSS language checks for the entire candidate pool, and an optional second scan for official transcripts. The official-transcript filter could inspect still more feeds before returning enough matches.

The new path removes that filter and its stored preference from active code. Official transcripts published by a podcast are still read normally when available.

Discover now reads a previously language-checked first page from a bounded, six-hour local cache keyed by learning language, native language, levels and interests. A fresh page is checked in the background. Refresh preserves the cover order if the reader already started scrolling. The cache stores six shows for at most eight preference combinations and tolerates missing/corrupt files.

Cold recommendations rank a small directory metadata pool using the existing level/theme rules, then validate candidates in small batches to fill a six-show first page. Further pages load on scroll. Typed searches show directory matches before optional language tags; only visible search rows request those tags. Repeated directory requests share one in-flight fetch and a bounded memory cache. JSON parsing runs away from the main thread.

Feed probes have real socket cancellation and a three-second call deadline. Temporary failures are not permanently cached or marked as dead feeds. Canceling a page restores its pending shows so navigating away does not silently skip them.

A separate 32 MB HTTP disk cache holds metadata, not audio/model downloads. A bounded parsed-feed cache coalesces repeated opens. Explicit refreshes revalidate using server-provided ETag/Last-Modified through OkHttp, and a 304 can reuse the already parsed feed. Full episode lists and published transcript URLs remain intact.

Cover grids keep three columns, remove their gutters and rounded cutouts, and request artwork sized to the actual tile width. Long press and accessibility text still identify shows.

## Comparison and limits

The supplied Podcast Addict APK's application-level cache helper uses feed validators and handles 304 responses; the earlier analysis is in the task's bandwidth-and-battery review artifact. Its public [SmartStream Cache description](https://podcastaddict.com/changelog/2024_5) separately documents caching buffered audio to speed later playback. That supports caching as a useful pattern but does not disclose its private Discover recommendation implementation.

These changes address this app's observed Discover request path. No phone battery or end-to-end OnePlus Nord 4 timing claim is made. A cold start still depends on directory servers, the selected language's available shows, and the network.

Validation: 56 targeted tests and full release lint passed, including HTTP coalescing, metadata disk cache reuse, ETag/304 behavior, changed feed refresh, cancellation during a response body, temporary failures, bounded/expired recommendation caches, pagination cancellation and six-show first pages. Layout tests cover equal adjacent clickable squares and rendered 360dp/412dp/landscape layouts. Existing Player, Anki and transcription scheduling regressions also pass.
