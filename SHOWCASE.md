# Wikiscroll

## A Dopamine-Driven Knowledge Engine

Wikipedia rebuilt as a vertical feed: one article per screen, a swipe to throw it away, a tap to read
the whole thing. The gesture is the easy part. The interesting part is the engine underneath, which
decides what comes next using nothing but what you just did on this device.

## The Pitch

Every feed product on your phone runs the same machine: an infinite supply, a one-gesture cost per
item, and a reward schedule you cannot predict. It works. It is also aimed at content that leaves
nothing behind.

Wikiscroll points that machine at an encyclopedia. Same variable ratio, same swipe, same pull toward
one more card, with a corpus that pays the reader back. The reward table is published in the README
instead of buried in a model, the weights are four numbers you can audit, and the whole policy runs
on the device with no account and no telemetry.

This is not a Wikipedia wrapper. A wrapper lists articles. This project decides, card by card, which
article deserves the next thirty seconds of attention, and it earns that decision with a scoring
model, two quality gates and a deliberate 30 percent of exploration that keeps the feed from
collapsing into whatever you liked last week.

## Key Technical Achievements

- **Kotlin Multiplatform from one shared source set.** Android, desktop JVM, wasmJs and iOS declared
  from a single `commonMain`. The domain layer is plain Kotlin: no Android type, no Compose type, no
  I/O, which is why the policy is testable down to the last branch.
- **A retention engine with published mechanics.** Dwell, fast swipe, like and read-more are worth
  +2, -1, +5 and +10. Signals are multiplied rather than summed (`affinity * pathing * authority`), so
  one strong signal cannot carry a weak candidate, and the 70/30 exploit-explore split is a constant
  instead of a heuristic.
- **Gates before weights.** An article needs 300 characters of extract and at least two categories
  before it competes, and a fast swipe buries its categories for five cards. Wikipedia is full of
  one-line disambiguation pages; a feed that serves them teaches the reader to swipe blind.
- **Deterministic by construction.** The engine takes its randomness from an injected seed, so a
  probabilistic assertion either always passes or always fails. 104 unit tests across 13 suites, and
  flakiness stays a bug instead of becoming background noise.
- **Network discipline.** A swipe costs three requests, never an N+1 fan-out. Server errors retry twice
  with exponential backoff capped at four seconds; timeouts are deliberately not retried, so a dead
  connection fails in seconds instead of tripling the spinner.
- **Bounded memory in an unbounded feed.** The article and neighbourhood caches are LRU maps capped at
  160 and 64 entries. The order is maintained by hand because the `accessOrder` constructor exists on
  the JVM only and this module also compiles for wasmJs and iOS.
- **Android correctness that survives the platform.** Back closes the article before it closes the app,
  predictive back is enabled for API 33 and up, `launchMode` is `singleTop` rather than the
  deeplink-hostile `singleInstance`, and `configChanges` covers density and smallest screen size.
- **Accessibility treated as behaviour.** The like control announces whether it is already liked, and
  its touch target is 48dp without changing the 44dp disc.
- **CI/CD that builds the product, not a sample.** Every push runs the unit tests, compiles the shared
  module for JVM and wasmJs, assembles the release APK and uploads it. Every `v*` tag publishes a
  GitHub Release with the APK attached.

## Why Anthropic?

Anthropic builds systems that are powerful and legible at the same time, and refuses the trade where
capability is bought with opacity. That is the constraint I tried to hold here at a much smaller
scale. The engagement algorithm is the part most products hide, so in this one it is written down:
the event weights, the payout table, the exploration ratio and the gates live in the README, and the
code is tested against that specification rather than against its own behaviour.

The privacy stance follows from the same choice. There is no analytics SDK in the dependency graph,
no account, and no server that could receive a reading history. The interest model lives in memory
and dies with the process. A recommendation engine that cannot be inspected is a claim; one that can
be inspected is an engineering artifact.

That is the work I want to do around Claude for developers: build tools whose behaviour is
observable, whose failure modes are stated before they happen, and where the person using the system
can see what it decided and why.

## Try it

Download the APK from the latest release:

https://github.com/barraco002/Wikiscroll/releases/tag/v1.4.0

```bash
adb install -r wikiscroll-1.4.0.apk
```

Or copy the file to the device and open it, allowing installation from unknown sources when prompted.
The app needs a network connection and nothing else: no login, no stored credentials, and no
permission beyond `INTERNET`.

## Status

The Android target is the one that ships, and the only one CI builds end to end. The desktop and web
targets are compiled on every push; the iOS targets are declared in the Gradle build but have not been
compiled or run yet. The release APK is a sideload build rather than a store submission, and it has not
been exercised on a physical device by anyone other than its author.

## License

Apache License 2.0, inherited from WikWok. The fork keeps the original package identifiers so that the
upstream history stays readable.
