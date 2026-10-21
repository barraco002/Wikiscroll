package com.github.terrakok.wikwok.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * First run: the reader states what they came for.
 *
 * This screen exists because of the cold start. The engine ranks by observed interest, and a brand
 * new profile has none, so the first cards would be noise and the reader would judge the product on
 * a random article. The chosen topics are seeded with enough weight to steer the exploitation arm
 * from the very first swipe.
 *
 * Two kinds of input feed the same list. The presets cover the common cases in one tap, and the text
 * field covers everything else, which matters because the interesting readers are the ones whose
 * taste is "Byzantine sigillography" and who would bounce off a screen that only offered six boxes.
 * They are kept apart in the UI only to know how to render them; the engine receives one list.
 *
 * @param onContinue invoked with the chosen topics, presets and free text merged.
 * @param modifier applied to the root column.
 */
/**
 * Mark shown above the onboarding headline.
 *
 * Drawn instead of shipped as a bitmap: it follows the theme colour, stays sharp at any density
 * and adds nothing to the APK. An open book is the one shape that says "reading" without a
 * caption, and it survives at 64 dp where a detailed logo would turn to mush.
 */
@Composable
private fun BookMark(modifier: Modifier = Modifier) {
    val pageColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.background
    Canvas(modifier = modifier.size(64.dp)) {
        val w = size.width
        val h = size.height
        val lineWidth = h * 0.035f

        // Each page tilts up towards the spine, which is what makes the two shapes read as one
        // open book instead of two separate rectangles.
        fun drawPage(inner: Float, outer: Float) {
            val path = Path().apply {
                moveTo(w * inner, h * 0.30f)
                lineTo(w * outer, h * 0.38f)
                lineTo(w * outer, h * 0.74f)
                lineTo(w * inner, h * 0.66f)
                close()
            }
            drawPath(path = path, color = pageColor)
        }

        drawPage(inner = 0.46f, outer = 0.06f)
        drawPage(inner = 0.54f, outer = 0.94f)

        for (row in 0..1) {
            val y = h * (0.45f + row * 0.12f)
            drawLine(
                color = lineColor,
                start = Offset(w * 0.13f, y),
                end = Offset(w * 0.40f, y - h * 0.035f),
                strokeWidth = lineWidth
            )
            drawLine(
                color = lineColor,
                start = Offset(w * 0.60f, y - h * 0.035f),
                end = Offset(w * 0.87f, y),
                strokeWidth = lineWidth
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onContinue: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPresets by remember { mutableStateOf(emptySet<String>()) }
    var customTopics by remember { mutableStateOf(emptyList<String>()) }
    var typed by remember { mutableStateOf("") }

    val total = selectedPresets.size + customTopics.size

    fun addCustomTopic() {
        val topic = typed.trim()
        val duplicate = (selectedPresets + customTopics).any { it.equals(topic, ignoreCase = true) }
        if (topic.isNotEmpty() && !duplicate && customTopics.size < MAX_CUSTOM_TOPICS) {
            customTopics = customTopics + topic
        }
        typed = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        BookMark(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Choose your interests",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The feed starts from these. You can change them later by reading.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(28.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (topic in PRESET_TOPICS) {
                val selected = topic in selectedPresets
                FilterChip(
                    selected = selected,
                    onClick = {
                        selectedPresets = if (selected) selectedPresets - topic else selectedPresets + topic
                    },
                    label = { Text(text = topic) },
                )
            }
        }

        if (customTopics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (topic in customTopics) {
                    InputChip(
                        selected = true,
                        onClick = { customTopics = customTopics - topic },
                        label = { Text(text = topic) },
                        trailingIcon = {
                            val cross = MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(modifier = Modifier.size(14.dp)) {
                                val stroke = 1.5.dp.toPx()
                                drawLine(cross, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                                drawLine(cross, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
                            }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text(text = "Add custom topic...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addCustomTopic() }),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { addCustomTopic() },
                enabled = typed.isNotBlank(),
            ) {
                val plus = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.size(18.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(plus, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, StrokeCap.Round)
                    drawLine(plus, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                onContinue((selectedPresets.toList() + customTopics).distinctBy { it.lowercase() })
            },
            enabled = total > 0,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(text = "Continue", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Topics offered as one-tap choices. Broad enough to cover most readers in one screen. */
private val PRESET_TOPICS: List<String> = listOf(
    "History",
    "Science",
    "Biology",
    "Philosophy",
    "Art",
    "Technology",
    "Geography",
    "Psychology",
)

/** Ceiling on free-text topics, so the seed list stays a choice rather than a second feed. */
private const val MAX_CUSTOM_TOPICS = 8
