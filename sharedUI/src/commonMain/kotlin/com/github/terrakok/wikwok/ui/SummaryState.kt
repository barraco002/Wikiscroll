package com.github.terrakok.wikwok.ui

/**
 * What the detail screen shows for the summarization action.
 *
 * Kept out of [WikiscrollUiState] because it belongs to one panel rather than to the feed: a summary
 * that arrives while the reader is back on the pager must not survive into the next article they
 * open.
 */
/**
 * Copy shown when no summarizer was wired into the view model at all.
 *
 * Distinct from a missing key: that one is a deployment choice the reader can act on, this one means
 * the feature was compiled out.
 */
internal const val SUMMARY_UNAVAILABLE_REASON: String = "Summarizing is not available in this build."

sealed interface SummaryState {
    /** No request has been made for the article on screen. */
    data object Idle : SummaryState

    /** A request is in flight. The panel owns the spinner. */
    data object Loading : SummaryState

    /**
     * A summary arrived.
     *
     * @property articleId article the summary belongs to, so a late response for a previous card
     *   cannot be rendered under the current one.
     * @property bullets the three lines.
     * @property model model identifier reported by the client.
     */
    data class Ready(
        val articleId: String,
        val bullets: List<String>,
        val model: String,
    ) : SummaryState

    /**
     * The attempt did not produce a summary.
     *
     * @property reason reader-facing sentence. Transport exception text is never surfaced: a class
     *   name is a log line, not an explanation.
     * @property needsConfiguration true when the failure is a missing credential rather than a
     *   failed request. The panel then explains how to configure it instead of offering a retry that
     *   would fail the same way.
     */
    data class Failed(
        val reason: String,
        val needsConfiguration: Boolean = false,
    ) : SummaryState
}
