package com.github.terrakok.wikwok.domain

/**
 * Turns one article into a short, scannable summary.
 *
 * The presentation layer depends on this interface rather than on a transport, so the view model can
 * be tested against an outcome it produces in memory. The remote model is an implementation detail:
 * swapping it changes one class in the data layer and nothing above it.
 */
interface Summarizer {
    /**
     * Summarizes [article]. Never throws: a failure is part of the returned value, because every
     * caller of this method is a button on a screen and an exception there becomes a crash.
     *
     * @param article card the reader is looking at.
     * @param language language edition the article came from, used to answer in the same language.
     */
    suspend fun summarize(article: Article, language: String): SummaryOutcome
}

/** Result of one summarization attempt. */
sealed interface SummaryOutcome {
    /**
     * Bullets the reader can scan in a couple of seconds.
     *
     * @property bullets one line per bullet, already stripped of list markers.
     * @property model identifier of the model that produced them, shown for honesty: a summary is a
     *   generated claim, and the reader deserves to know what wrote it.
     */
    data class Ready(val bullets: List<String>, val model: String) : SummaryOutcome

    /**
     * No credential was configured, so no request left the device.
     *
     * Separate from [Failed] because the two call for different copy: one asks for configuration,
     * the other offers a retry.
     */
    data class Unconfigured(val reason: String) : SummaryOutcome

    /** The request was made and did not produce a summary. */
    data class Failed(val reason: String) : SummaryOutcome
}
