package com.github.terrakok.wikwok.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The summarization control and its result.
 *
 * Four states, four different things on screen, because they ask the reader for four different
 * actions: press, wait, read, or fix the configuration. Collapsing them into "loading or error" would
 * leave a reader whose build has no key pressing a button that can never work.
 *
 * @param state what to render.
 * @param onSummarize invoked by the initial button and by the retry action.
 * @param modifier applied to the column.
 */
@Composable
internal fun SummarySection(
    state: SummaryState,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            SummaryState.Idle -> OutlinedButton(onClick = onSummarize) {
                Text(text = SummarizeLabel)
            }

            SummaryState.Loading -> LoadingRow()

            is SummaryState.Ready -> SummaryBody(state)

            is SummaryState.Failed -> FailureBody(state, onSummarize)
        }
    }
}

/** Spinner and the sentence that explains what is being waited for. */
@Composable
private fun LoadingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = SummarizingLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

/**
 * The bullets, in a card that reads as commentary rather than as article body.
 *
 * The model identifier is printed above them: a generated paragraph and an encyclopedic one look the
 * same on a phone, and the reader is entitled to know which one they are reading.
 */
@Composable
private fun SummaryBody(state: SummaryState.Ready) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Claude (" + state.model + ")",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            state.bullets.forEach { bullet ->
                Text(
                    text = BulletPrefix + bullet,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Failure copy, with a retry only when a retry could plausibly succeed. */
@Composable
private fun FailureBody(state: SummaryState.Failed, onSummarize: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = state.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
        if (state.needsConfiguration) {
            Text(
                text = ConfigurationHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        } else {
            TextButton(onClick = onSummarize) {
                Text(text = RetryLabel)
            }
        }
    }
}

private const val SummarizeLabel = "Summarize with Claude"
private const val SummarizingLabel = "Summarizing with Claude"
private const val RetryLabel = "Try again"
private const val ConfigurationHint =
    "Set ANTHROPIC_API_KEY in the environment, or anthropic.api.key in local.properties, and restart."

/** Rendered as an escape so the source file stays ASCII. */
private const val BulletPrefix = "\u2022  "
