# Implementation Plan

**Status:** proposed. Nothing below is implemented until [RESEARCH.md](RESEARCH.md) and this document are approved.

**Scope:** phases 2 to 6 of [the roadmap](../README.md#roadmap-and-contributing). Phase 1 (fork, identity, documentation) is complete.

## 0. Constraints that shape every decision

| Constraint | Consequence |
|---|---|
| Apache-2.0 fork, upstream still mergeable | Keep the `com.github.terrakok.wikwok` package. New code goes under `domain/`, existing files are touched only where integration requires it. |
| No backend, no telemetry | All state is local, serialised with KStore, mutated synchronously on the UI thread's domain call. |
| Domain must be testable without a device | `domain/` imports only `kotlin.*` and `kotlinx.*`. No Compose, no Ktor, no platform types. |
| Deterministic tests | Every engine entry point takes a `Random` (seeded) and an immutable state snapshot. |
| Wikimedia API etiquette | One request per second, identifying User-Agent, aggressive on-device cache. |

## 1. Target package layout

```text
sharedUI/src/commonMain/kotlin/com/github/terrakok/wikwok/
  domain/
    model/
      Article.kt              id, title, lang, extract, categories, links, wordCount, quality flags
      InteractionEvent.kt     sealed interface: Dwell, FastSwipe, Like, Unlike, Reopen, Share, NotInterested
      CategoryScore.kt        score, alpha, beta, lastEventAt, suppressedUntil
      InterestState.kt        immutable snapshot: categories, sessionHistory, seenIds, streak, recentCategories
      Reward.kt               sealed interface: Badge, StreakFreeze, DeepDive, Theme, Jackpot
    engine/
      ReadingTime.kt          word count -> expected milliseconds
      InterestModel.kt        event -> new state, weights, decay, suppression
      ExplorationPolicy.kt    Thompson sampling + adaptive epsilon band
      DiversityPolicy.kt      recency penalty over the last N cards
      RandomWalk.kt           candidate generation from links, hub penalty, dead-end fallback
      RewardTable.kt          weighted payout draw
      StreakTracker.kt        epoch-day streak with freeze
      RecommendationEngine.kt orchestrates the four above; the only public entry point
    WhyThis.kt                human-readable explanation for a ranked card
  data/
    WikipediaRepository.kt    REST summary + Action API for categories and links
    ArticleMapper.kt          DTO -> Article, including quality flags
    ArticleCache.kt           bounded LRU with TTL
    InterestStore.kt          KStore persistence, schema versioned
  ui/
    feed/WikiscrollScreen.kt  VerticalPager, dwell tracker, confetti host
    feed/DwellTracker.kt      settle-aware timing, pager state aware
    feed/ConfettiOverlay.kt   particle animation, honours reduced motion
    feed/WhyThisSheet.kt      explanation and topic controls
    feed/StreakBadge.kt       streak and reward reveal
sharedUI/src/commonTest/kotlin/com/github/terrakok/wikwok/domain/
  ReadingTimeTest.kt  InterestModelTest.kt  ExplorationPolicyTest.kt
  DiversityPolicyTest.kt  RandomWalkTest.kt  RewardTableTest.kt
  StreakTrackerTest.kt  RecommendationEngineTest.kt  FeedSimulationTest.kt
```

## 2. Phase 2 - Domain engine (no UI, no network)

**Goal:** a pure, seeded, fully tested recommendation engine that satisfies the specification in the README plus the improvements in the research report.

### 2.1 Contracts

```kotlin
data class Article(
    val id: String,          // lang:pageid
    val title: String,
    val language: String,
    val extract: String,
    val categories: List<String>,
    val links: List<String>,
    val wordCount: Int,
    val hasImages: Boolean,
    val isDisambiguation: Boolean,
    val isStub: Boolean,
)

sealed interface InteractionEvent {
    data class Dwell(val article: Article, val dwellMs: Long) : InteractionEvent
    data class FastSwipe(val article: Article, val dwellMs: Long) : InteractionEvent
    data class Like(val article: Article) : InteractionEvent
    data class Reopen(val article: Article) : InteractionEvent
    data class Share(val article: Article) : InteractionEvent
    data class NotInterested(val article: Article) : InteractionEvent
}

data class InterestState(
    val categories: Map<String, CategoryScore>,
    val sessionHistory: List<String>,
    val seenIds: Set<String>,
    val recentCategories: List<String>,
    val streak: StreakState,
)

class RecommendationEngine(
    private val exploration: ExplorationPolicy = ExplorationPolicy.Default,
    private val diversity: DiversityPolicy = DiversityPolicy.Default,
    private val rng: Random,
) {
    fun next(current: Article?, state: InterestState): RankedArticle
    fun onEvent(event: InteractionEvent, state: InterestState): InterestState
    fun rewardIfDue(state: InterestState): Reward?
}
```

### 2.2 Signal weights

The three canonical weights from the README stay exactly as specified. Length normalisation is additive, so the original rules remain true:

| Signal | Condition | Weight |
|---|---|---|
| `Dwell` | `dwellMs >= 8_000` | **+2** |
| `FastSwipe` | `dwellMs < 1_500` | **-1** |
| `Like` | explicit tap | **+5** |
| `CompletionBonus` | `dwellMs >= 0.5 * expectedReadMs` | **+1** (stacks with Dwell) |
| `Reopen` | article already in `seenIds` | **+1** |
| `Share` | share sheet completed | **+2** |
| `NotInterested` | explicit | **-10**, category suppressed for 30 days |
| *none* | `1_500 <= dwellMs < 8_000` | 0 |

`expectedReadMs = wordCount / 200 * 60_000` (200 wpm, configurable). Scores floor at 0 for ranking purposes but the raw value is kept, so a suppressed category can recover with one strong article.

Decay: every category score is multiplied by `0.5 ^ (daysSinceLastEvent / 7)` on read. A fortnight of silence halves a category's weight; nothing is ever deleted.

### 2.3 Selection policy

```text
epsilon = clamp(0.30 + 0.10 * coldFraction, 0.15, 0.40)   // coldFraction = share of categories with < 3 events

if rng.nextDouble() < epsilon:
    pool = categories never seen, else cold categories, else all
else:
    pool = categories with score > 0

utility(c) = max(0, score(c)) + 2.0 * betaSample(alpha_c, beta_c)
category = pool.weightedSample(rng) { utility(it) }

candidates = current.links ∪ current.categories
candidates = candidates.filter { it !in sessionHistory }
ranked = candidates.map { RankedArticle(it, walkWeight(it) * diversityPenalty(it)) }
next = ranked.weightedSample(rng) { it.weight } ?: deadEndFallback(current, state)
```

- `walkWeight(x) = (1 + 0.5 * score(category(x))) * hubPenalty(x)`, with `hubPenalty = 1 / (1 + ln(1 + outLinkCount(x)))` when known.
- `diversityPenalty(c) = 0.5 ^ (occurrences of c in the last 5 cards)`, floored at 0.1.
- `deadEndFallback` samples from the current top category instead of the global corpus. Global jumps are the last resort and are counted in diagnostics.
- `NotInterested` removes the category from every pool until `suppressedUntil` passes.

### 2.4 Gamification

- Streak: `epochDay` comparison, freeze consumes on a single missed day, no timezone arithmetic.
- Reward: every 10th served article, drawn from the README table (55/20/15/9/1).
- Pity timer (optional, on by default): if 40 articles pass without a Jackpot, the next payout is forced to Jackpot. Documented as the only deterministic branch.

### 2.5 Acceptance criteria

- [ ] 10,000 seeded draws with a neutral profile produce an exploit share between 0.60 and 0.80.
- [ ] The same seed and the same event sequence produce byte-identical `RankedArticle` sequences.
- [ ] A category at score 0 never exceeds 5 % of served cards unless it is the only candidate left.
- [ ] Three consecutive cards from one category are impossible unless it is the only remaining pool.
- [ ] A dead end is handled without an exception and without a global random jump in 95 % of cases.
- [ ] Every weight in the table above has a unit test asserting the exact delta.
- [ ] `domain/` compiles with no dependency on Compose, Ktor or platform APIs (verified by a dependency check in CI).

## 3. Phase 3 - Data layer

**Goal:** turn Wikipedia into a stream of `Article` values with categories and links, without breaking API etiquette.

```kotlin
interface WikipediaRepository {
    suspend fun summary(language: String, title: String): Article
    suspend fun random(language: String): Article
    suspend fun related(article: Article, limit: Int = 20): List<Article>
}
```

- Summary: `GET /api/rest_v1/page/summary/{title}` (extract, thumbnail, description).
- Graph: `GET /w/api.php?action=query&prop=links|categories&titles={title}&plnamespace=0&clshow=!hidden&pllimit=500&format=json&origin=*`.
- Quality gate: drop disambiguation pages, pages under 150 words, and pages with fewer than 3 links.
- Cache: LRU, 200 entries, 24 h TTL, persisted via KStore. Requests serialised at 1 per second with an identifying `User-Agent`.
- Failure policy: three retries with jitter, then fall back to the cached neighbourhood; a dead end never blocks the pager.

**Acceptance:** repository contract tests against recorded fixtures (no live network in CI), plus one manual smoke test per platform.

## 4. Phase 4 - Feed UI

- `WikiscrollScreen`: `VerticalPager` with a bounded window (`beyondViewportPageCount = 1`), one card per article, tap targets for like and why-this.
- `DwellTracker`: starts on `settledPage` and stops on `isScrollInProgress`; emits exactly one `Dwell` or `FastSwipe` per page transition; verified by a fake clock in a Compose test.
- `ConfettiOverlay`: Canvas particles, 700 ms, disabled when `LocalMotionDurationScale` reports reduced motion.
- `WhyThisSheet`: "Because you read 4 articles about Carthage" / "New topic: you have not seen Physics yet".
- `StreakBadge` and reward reveal: every 10th card, skippable with one tap, no modal trap.
- Double tap on the card likes it; haptics via `expect/actual`.

**Acceptance:** no dropped frames on a mid-range device during a 60-card scroll; dwell values within 50 ms of a manual stopwatch on the same interaction.

## 5. Phase 5 - Quality, i18n, distribution

- RTL: mirror the pager and the action rail; test with Arabic fixtures (issues 6, 7, 10).
- Rendering: minimal wikitext renderer for lists, line breaks and references (issues 13, 14); tables deferred with an explicit "open on Wikipedia" affordance.
- Platform fixes: desktop scroll (issue 1), web share (issue 2).
- Accessibility: TalkBack labels, dynamic type, contrast, reduced motion.
- Distribution: F-Droid reproducible build, AppImage and Flatpak manifests (issues 15-17, 19).

## 6. Phase 6 - Evaluation

No analytics, so evaluation is offline:

- `FeedSimulationTest` with three synthetic readers (curious, narrow, bored) over a recorded article graph. Asserts category entropy > 1.5 bits over 200 cards, repeat rate < 5 %, and that the narrow reader converges while the bored reader keeps receiving novel categories.
- A local diagnostics panel (debug builds only) showing category scores, explore share and dead-end count.

## 7. Test matrix

| Layer | Type | Runs in CI |
|---|---|---|
| Domain weights and decay | unit, seeded | yes |
| Exploration band | statistical, 10k draws | yes |
| Diversity and walk | deterministic with fake graph | yes |
| Repository mapping | fixture-based | yes |
| Dwell tracker | Compose test with fake clock | Android only |
| Feed simulation | property-style, 3 personas | yes |

## 8. Commit sequence

```text
feat(domain): add reading-time model and length-normalised signals
feat(domain): implement interest model with decay and suppression
feat(domain): add Thompson-sampling exploration policy with adaptive band
feat(domain): add diversity and hub-aware random walk
feat(domain): add streak tracker and variable-ratio reward table
feat(domain): wire RecommendationEngine and expose ranked cards
test(domain): cover weights, band limits, diversity and simulation
feat(data): add WikipediaRepository with cached graph extraction
feat(ui): add WikiscrollScreen with settle-aware dwell tracking
feat(ui): add confetti, why-this sheet and topic controls
fix(ui): mirror layout for RTL locales
chore(build): add F-Droid reproducible build metadata
```

## 9. Open decisions for the owner

1. **Exploration band.** Fixed 70/30 as specified, or adaptive 60-85 % exploitation with a 15 % hard floor? Recommendation: adaptive, with 70/30 as the default state.
2. **Package rename.** Keep `com.github.terrakok.wikwok` for merge compatibility, or migrate to `com.barraco002.wikiscroll`? Recommendation: keep, and rename at the first major release.
3. **Pity timer.** Deterministic jackpot every 40 articles, or pure randomness? Recommendation: pity timer on, since a 1 % payout has a 40 % chance of not appearing in 50 draws.
4. **Wikitext rendering depth.** Lists and breaks only, or tables and formulas too? Recommendation: lists and breaks in phase 5, everything else delegated to "open on Wikipedia".

## 10. Risks

| Risk | Mitigation |
|---|---|
| Renderer scope creep | Ship extracts first; wikitext is an explicit phase-5 item. |
| Wikimedia rate limits | 1 req/s, cache-first, bounded prefetch. |
| Compose tests are platform-limited | Domain carries the test weight; UI relies on a fake clock and manual verification. |
| Fork diverges from upstream | Package kept, integration surface limited to `App.kt` and the existing screen. |
| Engagement mechanics misfire on a text corpus | Offline simulation with three personas before any UI work. |