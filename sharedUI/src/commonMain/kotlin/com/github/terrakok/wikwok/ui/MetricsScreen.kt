package com.github.terrakok.wikwok.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.terrakok.wikwok.domain.SessionMetrics

/**
 * The session, counted.
 *
 * A product that claims to hold attention owes the reader a way to check the claim, and this screen
 * is that. Every number is computed from the same events the recommendation engine scored, on the
 * device, with no network call and no identifier: there is nothing here that could be sent anywhere
 * even if someone wanted to.
 *
 * The copy is plain on purpose. A panel of percentages would look like analytics; a list of things
 * the reader did - how many cards, how long, how many they liked, how many they left early - reads
 * as a receipt.
 *
 * @param metrics counters for the current process.
 * @param onClose invoked when the reader dismisses the panel.
 * @param modifier applied to the root box.
 */
@Composable
fun MetricsScreen(
    metrics: SessionMetrics,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session metrics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Close",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClickLabel = "Close session metrics", onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Counted on this device. No account, no identifier, nothing uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(28.dp))

        MetricRow(label = "Articles read", value = metrics.articlesRead.toString())
        MetricRow(label = "Total reading time", value = formatDuration(metrics.totalDwellMs))
        MetricRow(label = "Average per article", value = formatDuration(metrics.averageDwellMs))
        MetricRow(label = "Likes", value = metrics.likes.toString())
        MetricRow(label = "Read more", value = metrics.openMoreCount.toString())
        MetricRow(
            label = "Fast swipes",
            value = formatFastSwipes(metrics.fastSwipes, metrics.fastSwipeShare),
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "A fast swipe is a card left before half of its expected reading time. The " +
                "expected time comes from the length of the extract on screen, so a short article " +
                "and a long one are judged on what they asked for, not on a fixed number of seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
        )

        if (metrics.isEmpty) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Nothing recorded yet. Swipe a few cards and come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** One label and its value, separated by a hairline. */
@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.65f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

/**
 * Formats a millisecond total as minutes and seconds.
 *
 * Hours are not a separate field: a session that reaches one shows minutes past sixty, which is
 * honest and keeps the widest value on screen five characters long.
 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0L)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:" + if (seconds < 10) "0$seconds" else "$seconds"
}

/** Count and share of early exits, or a dash when nothing has been measured. */
internal fun formatFastSwipes(count: Int, share: Double): String {
    if (count == 0) return "0"
    val percent = (share * PERCENT).toInt()
    return "$count ($percent%)"
}

/** Milliseconds in one second. */
private const val MILLIS_PER_SECOND = 1_000L

/** Seconds in one minute. */
private const val SECONDS_PER_MINUTE = 60L

/** Percent denominator, written as a Double because the share is a fraction. */
private const val PERCENT = 100.0
