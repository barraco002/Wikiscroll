# Wikiscroll

An infinite Wikipedia feed, ranked on-device by what you actually read.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.0-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20Wasm-3DDC84)](#project-layout)

## Overview

Wikiscroll is a Wikipedia client with no search box. Every swipe loads one article from the neighbourhood of the previous one: its internal links, its categories, and the categories the reader keeps returning to. Ranking runs on the device from reading behaviour, not from profile data.

Most feed products optimise for time spent on content that leaves nothing behind. Wikiscroll applies the same mechanics to a corpus that does: full-screen cards, one gesture per item, and a reward the reader cannot predict. The loop is documented rather than hidden. The event weights, the exploration split and the payout table below are the specification the engine is tested against.

This repository is an Apache-2.0 fork of [WikWok](https://github.com/terrakok/WikWok) by Konstantin Tskhovrebov.

## Core Features

- **Dopamine-driven recommendation algorithm.** A variable-ratio schedule drives the feed: exploitation of high-interest categories most of the time, unpredictable exploration the rest. The reward is the article, and the interval is never announced.
- **Dwell-aware interest model.** Reading time, not clicks, is the primary signal. Articles that hold attention raise the weight of their categories; articles swiped past in under 1.5 seconds lower it.
- **Daily streaks.** Consecutive-day reading is tracked as `(lastVisitEpochDay, streak)`, so time zones and daylight-saving changes cannot break it.
- **Variable ratio rewards.** Every tenth article pays out from a weighted table. The interval is fixed and the payout is not - the classic variable-ratio shape, applied to knowledge instead of slot reels.
- **Random walk navigation.** The next card is sampled from the current article's graph neighbourhood, so a session reads as a train of thought instead of a slot machine.
- **Multiplatform by construction.** One Compose Multiplatform UI, one shared domain layer, four targets: Android, iOS, desktop (JVM) and web (Wasm).
- **No account, no telemetry.** Scores live in local storage. There is no analytics SDK in the dependency graph and no server to send them to.

## Screenshots

<p align="center">
  <img src="docs/screenshots/feed.svg" width="230" alt="Wikiscroll feed" />
  <img src="docs/screenshots/like-confetti.svg" width="230" alt="Like action with confetti" />
  <img src="docs/screenshots/streak.svg" width="230" alt="Daily streak and reward" />
</p>

<p align="center"><em>Placeholders. Real device captures land with the Phase 3 UI work.</em></p>

## Algorithm Architecture

Four interaction events feed a persisted score per category.

| Event | Condition | Weight | Side effect |
|---|---|---|---|
| `DwellTime` | article visible for >= 8.0 s | **+2** | counts as a dwell |
| `FastSwipe` | advanced in < 1.5 s | **-1** | buries the categories for 5 cards |
| `LikeAction` | explicit tap | **+5**, confetti burst | counts as a dwell |
| `ReadMore` | reader opens the full article | **+10** | counts as a dwell |
| none | 1.5 s to 8.0 s | 0 - deliberate dead zone | - |

A like costs a tap. Opening the article costs attention, and it is the only signal emitted after the reader has decided the summary was not enough, so it is worth twice a like. Both are idempotent per article: a second tap cannot inflate the profile, and an unknown article is never recorded.

The timer starts when the pager settles on a page and stops on scroll-away, so prefetching cannot inflate it.

```text
UserInterestScore(c) = max(0, sum of weights of every event on an article in category c)
P(c)                 = UserInterestScore(c) / sum(UserInterestScore(.))
```

The floor at zero matters: a category can be pushed down to nothing, but never blacklisted. One good article brings it back.

### The 70/30 split

```text
draw one uniform number per article

  0.00 - 0.70  EXPLOIT  sample a category weighted by P(c)
  0.70 - 1.00  EXPLORE  sample a category the reader has never seen
```

Exploitation alone converges on the first three categories that ever scored and the feed collapses into a niche. The 30 % exploration arm exists to break that local maximum and to reset the response: a reward you can predict stops working as one. When the unseen pool empties, exploration falls back to cold categories (score > 0, no event in the last N articles) before degrading to pure exploitation. That exhaustion path is a test case, not an afterthought.

### Candidate selection

Candidates are the internal links of the current article, minus the session history. Two gates run before any weighting, and both restore the ungated pool instead of returning nothing: an empty feed is a worse failure than a repeated topic.

```text
gate 1  quality   drop extract.length < 300 or categories.size < 2
gate 2  boredom   drop any candidate in a rejected category
```

The quality gate is the anti-stub rule. A three-word extract cannot tell the reader whether the topic is boring or the article is empty, and the topic takes the blame either way. Measured on the English most-viewed ranking the two limits remove about a fifth of the list, and what they remove is junk: calendar pages, list pages, unreleased films.

### The weight

```text
weight(x) = affinity(x) * pathing(x) * authority(x)

affinity(x)  = 1 + score(x) / (score(x) + 20)      -> 1.0 .. 2.0
pathing(x)   = 2.5  if shared categories >= 2
               0.5  if shared categories == 0
               1.0  otherwise
authority(x) = 2.0  if x is in the most viewed ranking, else 1.0
```

Affinity is normalised rather than linear so that a heavily liked topic cannot drown the other two signals: ten likes on one category would otherwise outrank every narrative edge on the board. Twenty points, four likes, is where the affinity term reaches 1.5.

### Narrative pathing

Two shared categories is a story the reader is already inside, and the feed follows it: World War II leads to Adolf Hitler leads to Nazi Germany. A candidate sharing nothing is a topic change and pays half price for it. This is the Substack mechanic: the next piece continues the one just finished instead of competing with it.

### Authority

Articles in the day's most-viewed ranking carry isPopular and are worth double. A landmark is a better next step than an obscure neighbour even when both match. The ranking is fetched once per language on the seed call and reused to annotate every related article afterwards, so a swipe still costs three requests.

### Anti-boredom

A fast swipe buries every category of that article for five cards, and each processed event decays the remaining turns by one. A candidate in a buried category is dropped, which forces the feed to change subject when the reader is visibly not reading.

### The seed

Cold start does not draw a random article. The first card is sampled uniformly from the most viewed ranking of the chosen language, which is why the feed opens on something people read today instead of a 1956 census district.

### Variable ratio rewards

Every tenth article draws from a weighted table. The interval is fixed; the outcome is not.

| Payout | Probability |
|---|---|
| Cosmetic badge | 55 % |
| Streak freeze (protects one missed day) | 20 % |
| Deep dive: five articles from one high-score category | 15 % |
| Rare theme unlock | 9 % |
| Jackpot: full confetti and a rare badge | 1 % |

### Reference implementation

```kotlin
fun next(current: Article, candidates: List<Article>, state: InterestState, rng: Random): Article? {
    val pool = candidates
        .filterNot { it.id in state.sessionHistory }
        .filter { it.extract.length >= 300 && it.categories.size >= 2 }
        .filterNot { it.categories.any(state::isRejected) }

    return pool.weightedSampleOrNull(rng) { candidate ->
        val shared = candidate.categories.count { it in current.categories }
        val pathing = when {
            shared >= 2 -> 2.5
            shared == 0 -> 0.5
            else -> 1.0
        }
        val authority = if (candidate.isPopular) 2.0 else 1.0
        val score = state.matchScore(candidate.categories)
        val affinity = 1.0 + score / (score + 20.0)
        affinity * pathing * authority
    }
}
```

## Project Layout

```text
Wikiscroll/
  androidApp/     Android entry point
  desktopApp/     JVM desktop entry point
  iosApp/         Xcode project and SwiftUI host
  webApp/         Wasm browser entry point
  sharedUI/       product logic and UI (Compose Multiplatform)
    data/         Wikipedia API client, persistence, platform services
    domain/       RecommendationEngine, InterestState, rewards   (Phase 2)
    ui/           feed screen, dwell instrumentation, confetti   (Phase 3)
    theme/        Material 3 theming
```

Dependencies point one way: presentation reads domain, domain requires data, and data never sees Compose. The domain layer is plain Kotlin with a seedable `Random`, so the whole policy is testable without a device or a network.

## How to Build

Requires JDK 17 or newer, and an Android SDK for the Android target.

```bash
# Android
./gradlew :androidApp:assembleDebug

# Desktop (fastest iteration loop)
./gradlew :desktopApp:run

# WebAssembly
./gradlew :webApp:wasmJsBrowserDevelopmentRun

# Shared tests
./gradlew :sharedUI:allTests
```

For iOS, open `iosApp/iosApp.xcodeproj` in Xcode and run the `iosApp` scheme. If the Android SDK is not configured globally, add a `local.properties` with `sdk.dir=...` at the repository root.

## Roadmap and Contributing

Background research and the full task breakdown live in [docs/RESEARCH.md](docs/RESEARCH.md) and [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

- [x] **Phase 1 - Fork and identity.** Apache-2.0 fork, remotes wired, README and algorithm specification.
- [x] **Phase 2 - Recommendation engine.** `RecommendationEngine`, `InterestState`, adaptive 70/30 policy, interaction weights, quality gate, narrative pathing, authority multiplier, anti-boredom penalties, streak and reward tables.
- [x] **Phase 3 - Feed UI.** `WikiscrollScreen` on `VerticalPager`, settle-aware dwell timer, confetti on like, reward reveal every tenth article, onboarding with interest seeding, most-viewed cold start.
- [ ] **Phase 4 - Persistence and polish.** Interest vector in KStore, offline cache, accessibility pass.

Contributions are welcome: the domain layer has a stable contract and 101 seeded tests, and CI runs them on every push to `master`. Open an issue with the mechanism you want to change and the evidence behind it.

Two ground rules for pull requests. Every behavioural change needs a test that fails without it. Any change to the event weights or the payout table needs a short note in the pull request explaining the expected effect on reading time.

## License and Attribution

Apache License 2.0. The upstream `LICENSE` is preserved verbatim; modifications are recorded in [NOTICE](NOTICE) as required by sections 4(b) and 4(d).

Wikipedia content is served by the MediaWiki Action API under CC BY-SA 4.0. Every card carries the article title, its language and a link to the canonical page.

Wikiscroll is not affiliated with or endorsed by the Wikimedia Foundation.

## Scope and Ethics

This is an engineering study of engagement mechanics, published as a portfolio artifact. It is not a monetised product.

The following are design commitments rather than roadmap items:

- No telemetry. Every signal is computed and stored on-device.
- No push notifications. The loop never reaches out; the reader starts every session.
- Bounded prefetch. The pager buffers a window instead of running the network in the background.
- Gamification is a setting that can be switched off.
- No generated content. Text and images come from Wikipedia and are attributed.

The mechanics are documented here precisely because they are effective. Hiding them would be the worse choice.
