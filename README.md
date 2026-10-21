# Wikiscroll

> **Hacking Wikipedia's rabbit hole with a dopamine-driven infinite feed.**
>
> *Hackeando el agujero de conejo de Wikipedia con un feed infinito impulsado por dopamina.*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.0-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![Ktor](https://img.shields.io/badge/Ktor-3.5.0-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20Wasm-3DDC84)](#-tech-stack)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-Phase%201%20%C2%B7%20fork%20%2B%20spec-yellow)](#-roadmap)

**Wikiscroll** takes the single most addictive reading surface on the internet — the Wikipedia rabbit hole — and gives it the delivery mechanism it never had: a vertical, full-screen, endless feed. Each swipe is one random-walk step through the article graph, re-weighted by a local interest model that learns from *how long you stay*, not from what you type.

No search box. No home page. No sign-up. Just the next article.

---

## Table of contents

- [Screenshots](#-screenshots)
- [Why Wikiscroll](#-why-wikscroll)
- [The Algorithm](#-the-algorithm-specification)
- [Architecture](#-architecture)
- [Tech stack](#-tech-stack)
- [How to build](#-how-to-build)
- [Roadmap](#-roadmap)
- [Ethics, legal & attribution](#-ethics-legal--attribution)
- [License](#-license)

---

## 📱 Screenshots

<p align="center">
  <img src="docs/screenshots/feed.svg" width="240" alt="Wikiscroll feed" />
  <img src="docs/screenshots/like-confetti.svg" width="240" alt="Like action with confetti" />
  <img src="docs/screenshots/streak.svg" width="240" alt="Daily streak and variable reward" />
</p>

<p align="center"><em>UI placeholders — replaced with real device captures as Phase 3 lands.</em></p>

---

## 🧠 Why Wikiscroll

Wikipedia is an infinite graph of human knowledge wrapped in the worst possible discovery UI: **a search box that requires you to already know what you are looking for**. Its famous "rabbit hole" effect is real, but it is accidental — it depends on the user clicking a blue link at the exact right moment.

TikTok solved discovery, not knowledge. It replaced *intent* with *momentum*: one item per screen, an infinite supply, and a reward schedule the user cannot predict. Wikiscroll transplants that delivery mechanism onto a corpus that is actually worth the time spent:

| | TikTok | Wikipedia | **Wikiscroll** |
|---|---|---|---|
| Unit of content | short video | article | article, one per screen |
| Discovery | ranking model | search box | random walk + interest model |
| Supply | creator uploads | ~7M articles (EN) | same corpus, zero curation |
| Cost per item | bandwidth | text | **text** |
| Value retained | ~0 | high | **high** |

The premise: *the dopamine loop is a delivery mechanism, and delivery mechanisms are value-neutral*. Pointed at short videos it costs you an evening. Pointed at an encyclopedia it costs you an evening and hands you back a working knowledge of Carthage.

### Built on the Hook Model

Wikiscroll is an explicit, documented implementation of Nir Eyal's four-phase hook (Trigger → Action → Variable Reward → Investment), applied to a non-commercial corpus:

| Hook phase | Wikiscroll mechanism |
|---|---|
| **Trigger** | *External:* app icon in the feed position. *Internal:* "I have three minutes and I want to learn something" — no search required. |
| **Action** | One gesture. Vertical swipe. No menus, no navigation stack, no back button. |
| **Variable reward** | 70/30 exploitation/exploration mix plus a variable-ratio payout every 10 articles. The next article is *never* predictable — that is the entire point. |
| **Investment** | Every dwell, fast-swipe and like mutates a persisted `UserInterestScore`. The feed gets measurably better at being *your* Wikipedia over time. |

---

## ⚙️ The Algorithm (specification)

The recommendation engine is a deterministic, testable state machine over an append-only interaction log. Nothing in it requires a server, an account, or a network call beyond the Wikipedia REST API.

### 1. Interest model

Every category `c` carries a persisted score. Only three events are recorded, and each one has a fixed weight:

```text
UserInterestScore(c) = max( 0 , Σ w(event) for every event on an article of category c )

w(DWELL_QUALIFIED) = +2     dwell >= 8.0 s        (interest)
w(FAST_SWIPE)      = -1     advance < 1.5 s        (rejection)
w(LIKE)            = +5     explicit like         (strong interest, confetti)
```

The `max(0, ·)` floor is deliberate: a category can be *disliked down to zero*, but it can never go negative and it can never be permanently blacklisted. A single good article can revive it — which is what keeps the exploration arm honest.

Scores are normalised across the user's active category set to produce a sampling distribution:

```text
P(c) = UserInterestScore(c) / Σ UserInterestScore(·)
```

### 2. Exploitation vs exploration — 70 / 30

The core loop is a biased coin, drawn once per article:

```text
if random() < 0.70:  EXPLOIT  -> sample from the top-scoring categories
                     (weighted by P(c), never uniformly)
else:               EXPLORE  -> sample a NEW category the user has never seen
                     (uniform over the unseen set — serendipity arm)
```

Why 30% and not 5%? Because exploitation without exploration is a **local maximum trap**: the model converges on the first three categories it ever scored and the feed collapses into a niche. The exploration arm exists to *reset* the dopaminergic response — a reward you can predict stops being a reward — and, incidentally, to keep the interest vector from starving.

The unseen-category pool is finite and shrinking. When it empties, the exploration arm falls back to *recently cold* categories (score > 0, no event in the last N articles) before finally degrading to pure exploitation. That exhaustion path is a first-class test case, not an afterthought.

### 3. Feedback loop

Dwell time is measured by a pager-aware timer that starts on page settle and stops on scroll-away — not on composition, so pre-fetching never inflates it.

| Signal | Condition | Effect |
|---|---|---|
| `DwellTime` | `>= 8.0 s` | `+2` to every category of the article |
| `FastSwipe` | `< 1.5 s` | `-1` to every category of the article |
| `LikeAction` | explicit tap | `+5`, article persisted to *Liked*, confetti burst |
| *no event* | `1.5 s .. 8.0 s` | no score change — the dead zone is intentional |

### 4. Random walk over the article graph

The next article is never fetched from "random page". It is drawn from the **neighbourhood of the current one**, so the feed reads as a train of thought rather than a slot machine:

```text
candidates  = internal links(current) ∪ categories(current) ∪ same-language siblings
weight(x)   = 1 + λ · UserInterestScore(category(x))        λ = 0.5
next        = weightedSample(candidates, weight)
```

Consequences that matter: the walk is *reversible* (Carthage → Rome → Carthage is possible), it has *no repeats* inside a session window, and dead ends (articles with no usable links) are the only place the engine is allowed to jump globally.

### 5. Gamification

- **Daily streak.** A visit on consecutive local days increments the streak; one missed day resets it to zero. Stored as `(lastVisitEpochDay, streak)` — no calendar arithmetic on timestamps, no time-zone bugs.
- **Variable-ratio payout.** Every 10 articles, a reward is drawn from a weighted table instead of being granted deterministically:

| Payout | Probability |
|---|---|
| Cosmetic badge | 55% |
| Streak freeze (protects one missed day) | 20% |
| "Deep dive" — 5 articles from one high-score category | 15% |
| Rare theme unlock | 9% |
| Jackpot: full confetti + rare badge | 1% |

The interval is fixed, the payout is not. That asymmetry — predictable *when*, unpredictable *what* — is the textbook variable-ratio schedule, and it is the single strongest retention lever in the design.

### Reference pseudocode

```kotlin
fun next(current: Article, state: InterestState, rng: Random): Article {
    val explore = rng.nextDouble() < 0.30
    val pool = if (explore) state.unseenCategories() else state.topCategories()
    if (pool.isEmpty()) return current.neighbours().maxBy { state.score(it.category) }

    val target = pool.weightedSample(rng) { state.score(it) }
    return current.neighbours()
        .filter { it.category == target && it.id !in state.sessionHistory }
        .maxByOrNull { state.score(it.category) }
        ?: current.neighbours().random(rng)   // dead end -> global jump
}
```

---

## 🏗 Architecture

```text
Wikiscroll/
├── androidApp/      Android entry point (Activity + Application)
├── desktopApp/      JVM desktop entry point (Compose Desktop)
├── iosApp/          Xcode project + SwiftUI host
├── webApp/          Wasm/JS browser entry point
└── sharedUI/        ★ all product logic and UI (Compose Multiplatform)
    ├── data/        Wikipedia API, persistence, platform services
    ├── domain/      RecommendationEngine, InterestState, gamification  ← Phase 2
    ├── ui/          WikipediaScreen, WikiscrollScreen, liked articles  ← Phase 3
    └── theme/       Material 3 theming (dynamic color via MaterialKolor)
```

The layering rule is one-directional and mechanically enforced by module boundaries:

```text
Presentation (Compose)  ──reads──▶  Domain (pure Kotlin)  ──requires──▶  Data (Ktor, KStore)
        │                                   ▲
        └────────────── events ─────────────┘
```

- **Domain** is pure Kotlin with zero Compose, zero Ktor, zero platform types. `RecommendationEngine` takes a seedable `Random` and an immutable state snapshot, so the entire 70/30 policy is testable without a device, a network, or a coroutine.
- **Data** exposes `suspend` functions only; it never leaks response DTOs upward.
- **Presentation** holds no engagement logic. It emits `InteractionEvent`s and renders whatever the domain hands back.

---

## 🧰 Tech stack

| Layer | Choice | Version |
|---|---|---|
| Language | Kotlin Multiplatform | 2.4.0 |
| UI | Compose Multiplatform + Material 3 | 1.12.0 |
| Networking | Ktor client (Darwin / OkHttp / Js / WinHttp) | 3.5.0 |
| Serialization | kotlinx.serialization | 1.11.0 |
| DI | kotlin-inject + KSP | 0.9.0 / 2.3.9 |
| Persistence | multiplatform-settings + KStore | 1.3.0 / 1.0.0 |
| Images | Coil 3 | 3.5.0 |
| Dates | kotlinx-datetime | 0.8.0 |
| Navigation | Navigation 3 (AndroidX multiplatform) | 1.2.0-alpha01 |
| Theming | MaterialKolor | 4.1.1 |
| Build | Gradle + AGP | 9.2.1 |
| Targets | Android · iOS · JVM desktop · Wasm |

No backend. No analytics SDK. No user accounts. The only network dependency is `*.wikipedia.org`.

---

## 🚀 How to build

### Prerequisites

- JDK 17 or newer (`java -version`)
- Android SDK with `ANDROID_HOME` set, or a `local.properties` in the repo root:
  ```properties
  sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
  ```
- Xcode 16+ *only* for the iOS target
- Optional: [KDoctor](https://github.com/Kotlin/kdoctor) to validate the toolchain

### Android

```bash
./gradlew :androidApp:assembleDebug
# APK -> androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### Desktop (fastest loop)

```bash
./gradlew :desktopApp:run          # run
./gradlew :desktopApp:hotRun --auto  # hot reload
```

### iOS

Open `iosApp/iosApp.xcodeproj` in Xcode and run the `iosApp` scheme (the Gradle framework is built automatically).

### Web (Wasm)

```bash
./gradlew :webApp:wasmJsBrowserDevelopmentRun
```

### Tests

```bash
./gradlew :sharedUI:allTests
```

---

## 🗺 Roadmap

- [x] **Phase 1 — Fork & identity.** Apache-2.0 fork of [terrakok/WikWok](https://github.com/terrakok/WikWok), remotes wired (`origin` = this repo, `upstream` = WikWok), README + algorithmic specification.
- [ ] **Phase 2 — Engagement engine.** `RecommendationEngine`, `InterestState`, 70/30 policy, interaction weights, random walk, streak + variable-ratio rewards. Pure `commonTest` suite with a seeded RNG.
- [ ] **Phase 3 — Feed UI.** `WikiscrollScreen` (`VerticalPager`), settle-aware dwell timer, confetti on like, reward reveal on every 10th article.
- [ ] **Phase 4 — Persistence & polish.** KStore-backed interest vector, cold-start bootstrap, offline cache, screenshots from real devices.

---

## ⚖️ Ethics, legal & attribution

This is an **engineering study of engagement mechanics**, published as a portfolio artifact — not a shipped product, and not a vehicle for monetising anyone's attention.

**Guardrails that are design commitments, not roadmap items:**

- **No telemetry.** Every signal (dwell, swipe, like) is computed and stored on-device. Nothing leaves the device. There is no analytics SDK in the dependency graph.
- **No push notifications.** The loop is never allowed to reach out and interrupt. The user starts every session.
- **No infinite pre-fetch.** The pager buffers a bounded window, so the feed cannot run the network in the background while the app is closed.
- **Opt-out of gamification.** Streaks and variable rewards are a switch, not a mandate.
- **Content is cited, not generated.** Every card carries its article title, language, and a deep link to the canonical Wikipedia page.

**Legal.** Wikiscroll is a fork of [WikWok](https://github.com/terrakok/WikWok) by Konstantin Tskhovrebov ([@terrakok](https://github.com/terrakok)), licensed under the Apache License 2.0. The original `LICENSE` is preserved verbatim; modifications are documented in [NOTICE](NOTICE) as required by §4(b) and §4(d). Wikipedia content is served by the Wikimedia REST API under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); Wikiscroll surfaces the required attribution and links back to the source article on every card.

"Wikiscroll" is not affiliated with or endorsed by the Wikimedia Foundation.

---

## 🙏 Credits

- **Konstantin Tskhovrebov** — author of WikWok, the multiplatform shell this project builds on.
- **Nir Eyal** — *Hooked: How to Build Habit-Forming Products*, the four-phase model implemented here.
- **B. F. Skinner** — variable ratio schedules of reinforcement, the mechanism behind the payout table.
- **Wikimedia** — the corpus, and the REST API that makes a keyless client possible.

---

## 📄 License

[Apache License 2.0](LICENSE) © 2026 barraco002 and Wikiscroll contributors.
Original work © 2024–2026 Konstantin Tskhovrebov.
