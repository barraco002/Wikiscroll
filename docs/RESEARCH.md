# Algorithm Research and Feedback Report

**Status:** research complete. Implementation is blocked on approval of this document and of [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

**Questions this answers.** Which mechanics behind TikTok-class feeds can be ported, legally and credibly, into an Apache-2.0 Wikipedia client that runs entirely on-device? And which failures do users of the upstream app actually report?

## 1. Method

Three evidence streams, kept separate on purpose:

1. **Published mechanics.** TikTok's own description of how the For You feed ranks content, ByteDance's Monolith paper on real-time recommendation serving, and the transparency obligations that force platforms to describe their ranking signals (EU DSA, Art. 27).
2. **Observed product behaviour.** The public user controls TikTok ships: *Not interested*, *Why am I seeing this*, topic management, feed reset, screen-time limits. These are shipped features, not speculation, and each one maps to a control Wikiscroll can implement locally.
3. **User complaints.** Every issue ever filed on `terrakok/WikWok` (19, of which 2 open), the App Store reviews of WikiTok (the web app that inspired the genre), and the 32 substantive comments in the Hacker News thread about it.

Marketing claims about "the TikTok algorithm" were discarded. Anything below is either documented by the platform, reproducible from a shipped feature, or a first-hand user report.

## 2. What TikTok actually does

### 2.1 Two-stage retrieval, not one score

The For You feed is not a single formula over a list of videos. It is:

```text
corpus (millions of items)
  -> candidate generation     (cheap, recall-oriented, embedding similarity + co-visitation)
  -> ranking                  (expensive, multi-task model over hundreds of features)
  -> re-ranking / business rules (diversity, dedupe, freshness, safety, policy)
  -> feed
```

The ranking model predicts several outcomes separately (probability of completion, of like, of share, of comment, of follow) and combines them with weights. The re-ranking stage is where monotony is fought: near-duplicate content, the same creator, or the same topic is throttled before it reaches the screen.

The transferable lesson for a client with no server is the **staged pipeline**, not the model. Candidate generation can be a graph walk. Ranking can be an additive score with a learned per-category weight. Re-ranking can be a diversity penalty. All three fit in a `commonMain` Kotlin module.

### 2.2 Signal hierarchy

Documented signals, ordered by how much they move the ranking. The distinction that matters: TikTok does not treat all engagement as equal, and it weights *negative* signals at least as strongly as positive ones.

| Tier | Signal | Why it ranks where it does |
|---|---|---|
| Strong positive | Watch-through / completion | The single best proxy for delivered value. Length-normalised, not absolute. |
| Strong positive | Rewatch, replay | Deliberate repetition. Almost impossible to fake by accident. |
| Strong positive | Share | Costs social capital. The strongest explicit endorsement. |
| Medium positive | Save / favourite | Intent to return. Cheap but meaningful. |
| Medium positive | Like | Cheap, reflexive, often social rather than evaluative. |
| Medium positive | Watch time (absolute) | Correlated with completion but confounded by length. |
| Weak positive | Comment | Ambiguous: agreement and outrage look identical. |
| Weak positive | Profile visit, follow | Account-level, slow-moving. |
| Strong negative | *Not interested*, hide, report | Explicit rejection. Suppresses the topic, not just the item. |
| Strong negative | Fast skip | Implicit rejection, usually interpreted relative to item length. |

Two properties of this table are worth copying verbatim: **length normalisation** (a 15-second video watched for 8 seconds is a rejection; a 3-minute video watched for 60 seconds is a strong signal) and **explicit negative feedback outranking implicit positive feedback**.

### 2.3 Real-time feedback

ByteDance's Monolith (arXiv:2209.07663) exists because batch-trained models adapt too slowly to short-video feedback. Its two contributions are a collisionless embedding table and streaming updates, so that a signal from the last minute influences the next recommendation. The mechanism is a server-scale system, but the principle is model-agnostic: **feedback should update state immediately, not on a nightly job.**

For Wikiscroll this is trivially achievable and currently missing from the README specification: the interest vector must mutate on the same frame the dwell timer fires, and the next card must be sampled from the mutated state.

### 2.4 User controls (the part most clones skip)

Modern TikTok ships, and the DSA requires, controls that let a user interrogate and steer the ranking:

- *Not interested* on a card, which suppresses the topic rather than just the item.
- *Why am I seeing this*, an explanation attached to every recommended item.
- Topic management: choose what to see more or less of.
- Feed reset: discard the learned profile and start cold.
- Screen-time limits and break reminders.

These are product features with retention value, not compliance tax. A feed the user can steer is a feed the user trusts enough to keep using, and every one of them is cheap to implement in a local client.

## 3. What transfers to a text client, and what does not

| TikTok mechanic | Wikiscroll equivalent | Verdict |
|---|---|---|
| Video duration | Article word count, converted to expected reading time | **Direct port.** Enables length-normalised dwell. |
| Completion rate | `dwellMs / expectedReadMs`, clamped | **Direct port.** |
| Rewatch | Re-opening an article already read | **Direct port.** |
| Share / save | Share sheet, saved list | **Direct port.** |
| Creator diversity | Category and portal diversity | **Port with care.** Categories are coarser than creators. |
| Embedding recall | Random walk over the link graph, weighted by interest | **Substitute.** Cheaper, explainable, no model. |
| Multi-task model | Additive scoring with per-signal weights | **Substitute.** Testable, debuggable, tunable by hand. |
| Real-time training | Immediate state mutation | **Direct port.** |
| Notification-driven return | None | **Deliberately refused.** See section 8. |

## 4. What users actually complain about

### 4.1 Upstream issues (`terrakok/WikWok`)

| # | Issue | State | What it tells us |
|---|---|---|---|
| 12 | Remove "TikTok-style" from the description | closed | The framing itself is polarising. Position the product as reading, not as a slot machine. |
| 13 | Support lists in Wikipedia | closed | The renderer drops structure. Long articles read as a wall of text. |
| 14 | Support line breaks | closed | Same root cause: wikitext is not handled faithfully. |
| 6 | Arabic text renders as tofu on Android | closed | Font fallback is broken for non-Latin scripts. |
| 7 | RTL display issues (Arabic) | closed | Bidi layout not handled. |
| 10 | Support RTL | closed | Structural, not a one-off bug. |
| 5 | Double tap to like | closed | Users arrive with TikTok muscle memory; the gesture was missing. |
| 1 | Snapped scrolling feels wrong on desktop web (open) | open | Pager behaviour is tuned for touch only. |
| 2 | Share button crash on web (open) | open | Platform-specific API gaps. |
| 15, 16, 17, 19 | AppImage, Linux executable, Flatpak, F-Droid reproducibility | closed | Distribution is a real demand, not a niche. |

### 4.2 Sibling app reviews (WikiTok, App Store)

Selected one-star reviews, paraphrased:

- "Great idea, flawed execution. Lots of UI issues and a visibly poor understanding of mobile UX."
- "Cool app, pretty bad UI and functionality" - followed by a report of being charged twice for the subscription.
- "Terrible interface, the app glitches, and it costs money on top."

The pattern is consistent: **the idea is praised, the execution is not.** Nobody complains that an infinite Wikipedia feed is a bad idea. They complain about jank, about paywalls bolted onto a free corpus, and about double charges. Wikiscroll's answer is structural: no paywall, no ads, no subscription, and a UI budget spent on the reader.

### 4.3 Hacker News thread (32 comments)

- "Cute and fun idea" / "making knowledge and science somewhat addictive is a cool goal" - the premise has goodwill.
- "I've never used TikTok; if this is an accurate representation, it's jarring" - the interface over-imitates the source.
- "Graph structure of wiki could be exploited for user-specific content" - a user independently proposed the exact random-walk design this project is building.
- Recurring objection: engagement ranking applied to an encyclopedia could amplify fringe or vandalised content.

### 4.4 Root causes, ranked by damage

1. **No memory and no personalisation.** The feed is effectively `random.choice(articles)`. Users cannot tell whether it is learning; it is not.
2. **Naive dwell measurement.** A fixed 8-second threshold treats a 200-word stub and a 6,000-word article identically, so the strongest signal in the product is also the least accurate.
3. **No negative feedback.** There is no way to say "not this", so the only exit from a bad topic is closing the app.
4. **Broken text rendering.** Lists, line breaks, tables and references are the difference between reading and skimming.
5. **Platform gaps.** RTL, font fallback, desktop scroll, web share.
6. **Positioning.** The TikTok comparison sets expectations the UI cannot meet and invites the wrong criticism.

## 5. Design principles derived from the evidence

1. **Length-normalise every signal.** Dwell, completion and skip are ratios, never raw seconds.
2. **Weight rejection above approval.** One explicit *not interested* must outweigh three likes.
3. **Explain the ranking.** Every card can answer "why this one" in one sentence.
4. **Bound the exploration.** Never below 15 % or above 40 % of cards, regardless of how confident the model is.
5. **Fight monotony explicitly.** A diversity penalty is not a nicety; it is what separates a feed from a loop.
6. **Keep the state on the device, mutate it immediately.** No server, no batch job, no telemetry.
7. **Render the article faithfully.** Structure is content.
8. **Refuse the mechanics that only work by coercion.** No notifications, no streaks that shame, no fake scarcity.

## 6. Feature backlog

Impact and effort are relative (H/M/L). Every item is traceable to a source above.

| ID | Feature | Source | Impact | Effort | Phase |
|---|---|---|---|---|---|
| F1 | Length-normalised dwell ratio (expected reading time from word count) | TikTok completion rate; root cause 2 | H | M | 2 |
| F2 | Explicit negative feedback: *Not interested*, topic suppressed for N days | TikTok hide; root cause 3 | H | S | 2 |
| F3 | Thompson-sampling exploration with a hard 15-40 % band | TikTok exploration; principle 4 | H | M | 2 |
| F4 | Diversity penalty over the last N cards (category repeat decay) | TikTok re-ranking | H | S | 2 |
| F5 | Hub-aware random walk (inverse-link-frequency weighting) | HN comment; graph design | H | M | 2 |
| F6 | Revisit signal (re-opening a read article) | TikTok rewatch | M | S | 2 |
| F7 | Streak with freeze, variable-ratio reward table | Existing spec | M | S | 2 |
| F8 | *Why am I seeing this* explanation per card | TikTok; DSA Art. 27 | H | S | 3 |
| F9 | Topic controls and feed reset | TikTok controls | M | M | 3 |
| F10 | Faithful wikitext rendering (lists, breaks, tables, references) | Issues 13, 14 | H | L | 3 |
| F11 | Double tap to like, with haptics | Issue 5 | M | S | 3 |
| F12 | RTL and font fallback (Arabic, Hebrew, CJK) | Issues 6, 7, 10 | H | M | 4 |
| F13 | Desktop scroll and web share fixes | Issues 1, 2 | M | S | 4 |
| F14 | Bounded prefetch and offline cache | Principle 6 | M | M | 4 |
| F15 | Optional session timer and break reminder | TikTok wellbeing controls | M | S | 4 |
| F16 | F-Droid reproducible build, AppImage and Flatpak | Issues 15-17, 19 | M | L | 5 |
| F17 | Offline evaluation harness with synthetic readers | Principle 1 | H | M | 2 |
| F18 | Accessibility pass: TalkBack, dynamic type, reduced motion | Not covered upstream | M | M | 4 |
| F19 | Local-only metrics panel (diversity, completion, skip rate) | Transparency principle | M | S | 5 |
| F20 | Article quality gate (stubs, disambiguation, empty sections) | HN objection | M | S | 2 |

## 7. What this project will not do

- No server-side model, no telemetry, no account, no advertising, no subscription.
- No notification-driven re-engagement. The loop never initiates contact.
- No engagement metric that rewards outrage: comments are excluded as a signal because agreement and anger are indistinguishable.
- No unbounded prefetch, no background network activity.
- No use of the TikTok name or marks in the product identity.
- No synthetic or generated article text. Content stays attributed Wikipedia text under CC BY-SA.

## 8. Legal position

- **Code.** Upstream WikWok is Apache-2.0. Forking, renaming and redistributing are permitted, with attribution preserved in `LICENSE` and `NOTICE` (sections 4(b) and 4(d)).
- **Content.** Wikipedia text is CC BY-SA 4.0. Every card must link to the canonical source and credit the article and language. Derivative text has to remain CC BY-SA; the app does not modify article text, so attribution and linking are sufficient.
- **Mechanics.** Ranking ideas, reward schedules and dwell-time heuristics are not patentable subject matter in this jurisdiction and are published here in full. No TikTok code, model weights, data or trademarks are used.
- **Data protection.** No personal data leaves the device, so there is no controller-processor relationship and no GDPR transfer surface. The DSA transparency obligations that apply to very large platforms do not bind a local client, but their user-facing controls are adopted voluntarily because they make the product better.

## 9. Sources

- TikTok Support, *How TikTok recommends content*: https://support.tiktok.com/en/using-tiktok/exploring-videos/how-tiktok-recommends-content
- TikTok Transparency, *Recommender systems*: https://www.tiktok.com/transparency/en/recommender-systems
- Liu et al., *Monolith: Real Time Recommendation System With Collisionless Embedding Table*, arXiv:2209.07663: https://arxiv.org/abs/2209.07663
- EU Digital Services Act, Article 27 (recommender system transparency): https://dsa-library.com/article/27
- Upstream issues: https://github.com/terrakok/WikWok/issues?q=is%3Aissue
- WikiTok App Store reviews: https://apps.apple.com/us/app/wikitok-scroll-read-learn/id6584225756
- Hacker News discussion: https://news.ycombinator.com/item?id=46557029