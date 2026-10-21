# Wikiscroll - A Dopamine-Driven Knowledge Engine

Wikipedia rebuilt as a vertical feed: one article per screen, a swipe to throw it away, a tap to
read the whole thing. The gesture is the easy part. The interesting part is the engine underneath,
which decides what comes next using nothing but what you just did on this device.

## The Pitch

Wikiscroll is a Kotlin Multiplatform client for Wikipedia wrapped in a full-screen, swipe-driven
interface. It is forked from [terrakok/WikWok](https://github.com/terrakok/WikWok) and rebuilt around
a recommendation engine that learns from behaviour instead of profiles. There is no account, no
server, no analytics SDK and no request that carries your reading history anywhere. The model is a
handful of immutable maps held in memory, and it dies with the process.

The feed is a stream of decisions rather than a ranked list. Each card is scored by affinity (what
you have dwelled on, liked or opened), narrative pathing (how close it sits to the article you just
left), authority (whether Wikipedia itself says people read it) and a small visual tie-breaker. Those
four numbers are multiplied, not summed, so a single strong signal cannot carry a weak candidate
through, and the result is sampled rather than sorted: a feed that always returns the top scorer is a
feed that stops surprising you after twenty cards.

## Key Technical Achievements

- **Clean Architecture with a pure Kotlin domain.** Three layers (presentation, domain, data) with no
  Android or Compose type leaking inward. The engine is a plain class with no coroutines, no clock and
  no I/O, which is why it is testable down to the last branch.
- **Adaptive 70/30 exploration.** Seventy percent of the picks exploit the current interest profile;
  thirty percent explore categories the profile does not know about yet. The ratio is a constant, not a
  heuristic, so the feed cannot collapse into one topic and call it a preference.
- **Anti-stub gate.** An article needs at least 300 characters of extract and at least two categories
  before it is allowed to compete. Wikipedia is full of one-line disambiguation pages, and a feed that
  serves them teaches the user to swipe without reading.
- **Four-signal retention engine.** Dwell, fast swipe, like and read-more are worth 2, -1, 5 and 10
  points. A fast swipe writes a category penalty that decays after five turns, so one bad card does not
  bury a topic forever. Popular articles get an authority multiplier; an article with no lead image and
  no authority takes a small penalty, which is a tie-breaker rather than a veto.
- **Idempotent interaction ledger.** Liking the same card twice scores once. The view model keeps the
  set of ids it has already processed, so a recomposition storm or a double tap cannot inflate the
  profile.
- **CI/CD on GitHub Actions.** Every push runs the unit tests, compiles the shared module for JVM and
  wasmJs, assembles the release APK and uploads it as an artifact. Every `v*` tag publishes a GitHub
  Release with the APK attached.
- **97 unit tests.** Seeded and deterministic: the engine takes its randomness from an injected seed, so
  a probabilistic assertion either always passes or always fails, and flakiness stays a bug instead of
  becoming background noise.
- **Kotlin Multiplatform, four targets.** Android, desktop JVM, web (wasmJs) and iOS (arm64 and
  simulator arm64) from one `commonMain`. Compose Multiplatform for the UI, Ktor for the network,
  kotlinx.serialization for the wire format, Coil for the images.
- **A launcher icon that survives every API level.** Vector adaptive icon on 26 and up, a radial
  gradient on 24 and 25, a flat fallback on 23, a monochrome layer for the Android 13 themed icon, and
  bitmaps rendered from the same geometry for the launchers that still refuse vectors.
- **Category-aware empty states.** An article with no lead image is not left on a flat black card: the
  backdrop is a vertical gradient picked from the article categories by whole-word match, so `Software`
  never inherits the war palette just because it contains the letters `war`.

## How to try it

Download the APK from the latest release:

https://github.com/barraco002/Wikiscroll/releases/tag/v1.3.0

Install it on any device running Android 6.0 (API 23) or newer:

```bash
adb install -r wikiscroll-1.3.0.apk
```

Or copy the file to the device and open it, allowing installation from unknown sources when prompted.
The app needs a network connection and nothing else: no login, no stored credentials, and no
permission beyond `INTERNET`.

## Status

The Android target is the one that ships. The desktop, web and iOS targets compile from the same
source but have not been through a device pass yet, and the release APK is a sideload build rather
than a store submission.

## License

Apache License 2.0, inherited from WikWok. The fork keeps the original package identifiers so that the
upstream history stays readable.