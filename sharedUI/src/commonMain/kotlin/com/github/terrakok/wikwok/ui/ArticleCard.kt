package com.github.terrakok.wikwok.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.terrakok.wikwok.LocalImageLoader
import com.github.terrakok.wikwok.domain.Article
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-bleed card for one article.
 *
 * The image owns the whole viewport, so the card reads as one continuous poster instead of a
 * stacked layout with a photo on top and a text panel below. Everything else floats over it: the
 * identity bar under the status bar, and the text block anchored to the bottom above the navigation
 * bar. The scrim in between is what keeps white type legible over an arbitrary photograph, which is
 * the only reliable way to do this without knowing the image's luminance.
 *
 * A missing thumbnail is not a degraded state. The gradient alone is a legitimate backdrop, so the
 * card falls back to a dark wash instead of a placeholder box.
 *
 * The like control lives in the top bar rather than under the text: the whole surface is a swipe
 * target, and a control sitting at the bottom edge competes with the swipe gesture that advances the
 * feed.
 *
 * Recomposition is contained on purpose. The liked set arrives as [State] instead of a boolean, so
 * the card does not read it while composing; the read happens in the button that renders the answer,
 * through a [derivedStateOf] that only notifies when this particular article's flag flips. Liking one
 * article therefore repaints one button, not every card in the pager.
 *
 * @param article content to render.
 * @param likedIds every article already in the reader's likes.
 * @param onLike invoked with the article id on every tap, like and unlike alike.
 * @param onOpen invoked with the article the reader asked to read in full.
 * @param onReadMore invoked with the article id when the reader asks for the full text, before the
 *   link is opened. It exists separately from [onOpen] because it is the strongest retention
 *   signal the app can record and must reach the policy even when the browser fails to launch.
 * @param onOpenMetrics invoked when the reader taps the counters control in the top bar.
 * @param modifier applied to the outermost box.
 */
@Composable
fun ArticleCard(
    article: Article,
    likedIds: State<Set<String>>,
    onLike: (String) -> Unit,
    onOpen: (Article) -> Unit,
    onReadMore: (String) -> Unit = {},
    onOpenMetrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Keyed on the id so a recycled page does not inherit the previous article's derived state.
    val isLiked = remember(article.id) {
        derivedStateOf { article.id in likedIds.value }
    }
    // Remembered, not rebuilt on every recomposition: a fresh lambda would be an unstable parameter
    // and would defeat the skipping the immutable article makes possible.
    val onLikeClick = remember(article.id, onLike) { { onLike(article.id) } }
    val onOpenClick = remember(article, onOpen) { { onOpen(article) } }
    val onReadMoreClick = remember(article.id, onReadMore) { { onReadMore(article.id) } }
    // Keyed on the id, not rebuilt per frame: a pager scroll recomposes the card on every pixel,
    // and a fresh gradient for each of those is an allocation nobody sees.
    val backdrop = remember(article.id) { categoryGradient(article.categories) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // The fallback wash sits underneath the image, so an article without a thumbnail still gets
        // a graded backdrop instead of a flat rectangle.
        if (article.imageUrl == null) {
            // A colour field built from the article's own categories. Flat black was correct and
            // dead: when every third card is the same rectangle the feed stops feeling like a
            // place. The palette stays dark enough for white body text, and it is chosen from a
            // word match rather than from a hash so two articles on the same subject share a
            // backdrop instead of colliding randomly.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backdrop)
            )
        }

        article.imageUrl?.let { url ->
            AsyncImage(
                model = url,
                // Decorative: the title and the extract are already announced above it, and a
                // description for a photograph of the subject repeats what the text already says.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                imageLoader = LocalImageLoader.current,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = TopScrimAlpha),
                        0.30f to Color.Transparent,
                        0.58f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black,
                    )
                )
        )

        TopBar(
            languageTag = article.languageTag(),
            isLiked = isLiked,
            onLike = onLikeClick,
            onOpenMetrics = onOpenMetrics,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.extract.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = article.extract,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.80f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
                    .clickable(role = Role.Button, onClickLabel = "Open the full article") {
                        // Recorded before the deeplink: opening the browser can background the
                        // process, and an event queued after that may never run.
                        onReadMoreClick()
                        onOpenClick()
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Read more",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Floating identity bar pinned under the status bar.
 *
 * The row only pads for the status bar, never for the navigation bar: it is anchored to the top by
 * definition, and adding bottom insets here would push it back into the image for no reason.
 */
@Composable
private fun TopBar(
    languageTag: String,
    isLiked: State<Boolean>,
    onLike: () -> Unit,
    onOpenMetrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Wikiscroll",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            letterSpacing = 0.4.sp,
            modifier = Modifier
                .background(Color.Black.copy(alpha = PillAlpha), RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetricsButton(onOpenMetrics = onOpenMetrics)
            Canvas(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color.Black.copy(alpha = PillAlpha), RoundedCornerShape(percent = 50))
                    .padding(9.dp)
            ) {
                drawStar(color = StarColor)
            }
            Text(
                text = languageTag,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = PillAlpha), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            LikeButton(isLiked = isLiked, onLike = onLike)
        }
    }
}

/**
 * Counters control: three rising bars in the same pill as the star.
 *
 * Drawn rather than shipped as an asset, for the same reasons as the heart and the star: one shape,
 * no resource lookup on the composition path, and it stays sharp at any density. The visible pill is
 * 34dp to line up with the star, while the touch target is the 48dp the platform asks for.
 */
@Composable
private fun MetricsButton(onOpenMetrics: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(Color.Black.copy(alpha = PillAlpha), RoundedCornerShape(percent = 50))
            .minimumInteractiveComponentSize()
            .semantics { stateDescription = "Session metrics" }
            .clickable(role = Role.Button, onClickLabel = "Open session metrics") { onOpenMetrics() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            drawBars(color = Color.White)
        }
    }
}

/**
 * Heart control with a scale pop and a fading burst behind it.
 *
 * Both effects are driven by [Animatable] because they need to start from the current value on a
 * rapid double tap; a tween on a boolean would restart from zero and stutter.
 *
 * The liked flag is a [State] read here rather than a boolean passed down: this is the only place
 * that needs the answer, so this is the only scope that has to be invalidated when it changes.
 */
@Composable
private fun LikeButton(
    isLiked: State<Boolean>,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(BurstFinished) }
    val liked = isLiked.value
    val tint = if (liked) LikedColor else Color.White

    Box(
        modifier = modifier
            .size(44.dp)
            // The visible disc stays 44dp; the touch target grows to the 48dp the platform asks
            // for, so a tap that lands just outside the heart still registers.
            .minimumInteractiveComponentSize()
            // A toggle that announces only "Like" leaves a screen reader user unable to tell
            // whether the tap already landed, so the state travels with the control.
            .semantics { stateDescription = if (liked) "Liked" else "Not liked" }
            .clickable(role = Role.Button, onClickLabel = "Like") {
                onLike()
                scope.launch {
                    launch {
                        scale.animateTo(1.5f, tween(durationMillis = 90))
                        scale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                        )
                    }
                    launch {
                        burst.snapTo(0f)
                        burst.animateTo(BurstFinished, tween(durationMillis = 450, easing = FastOutLinearInEasing))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val progress = burst.value
            if (progress < BurstFinished) {
                drawCircle(
                    color = LikedColor.copy(alpha = (1f - progress) * 0.45f),
                    radius = size.minDimension * (0.18f + progress * 0.32f),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
        ) {
            drawHeart(filled = liked, color = tint)
        }
    }
}

/**
 * Draws three bars of rising height inside the current canvas.
 *
 * The widths and the step are fractions of the canvas, so the icon keeps its proportions whatever
 * size the caller gives it.
 */
private fun DrawScope.drawBars(color: Color) {
    val barWidth = size.width * BarWidthFraction
    val step = size.width * BarStepFraction
    BarHeights.forEachIndexed { index, fraction ->
        val height = size.height * fraction
        drawRect(
            color = color,
            topLeft = Offset(x = index * step, y = size.height - height),
            size = Size(width = barWidth, height = height),
        )
    }
}

/**
 * Draws the heart inside the current canvas.
 *
 * Hand-drawn instead of shipped as an icon asset: the same path is reused for the filled and the
 * outlined state, and it keeps the card free of platform resource lookups on every recomposition.
 */
private fun DrawScope.drawHeart(filled: Boolean, color: Color) {
    val width = size.width
    val height = size.height
    val path = Path().apply {
        moveTo(width * 0.5f, height * 0.88f)
        cubicTo(width * 0.08f, height * 0.60f, width * 0.02f, height * 0.32f, width * 0.22f, height * 0.18f)
        cubicTo(width * 0.36f, height * 0.08f, width * 0.46f, height * 0.16f, width * 0.5f, height * 0.28f)
        cubicTo(width * 0.54f, height * 0.16f, width * 0.64f, height * 0.08f, width * 0.78f, height * 0.18f)
        cubicTo(width * 0.98f, height * 0.32f, width * 0.92f, height * 0.60f, width * 0.5f, height * 0.88f)
        close()
    }
    if (filled) {
        drawPath(path = path, color = color)
    } else {
        drawPath(path = path, color = color, style = Stroke(width = size.width * 0.09f))
    }
}

/**
 * Draws a five-pointed star inside the current canvas.
 *
 * The ten vertices alternate between an outer and an inner radius, starting at the top, which is
 * what makes the shape symmetric without hard-coding coordinates.
 */
private fun DrawScope.drawStar(color: Color) {
    val outer = size.minDimension * 0.46f
    val inner = outer * 0.42f
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val path = Path()
    repeat(10) { index ->
        val radius = if (index % 2 == 0) outer else inner
        val angle = -PI / 2 + index * PI / 5
        val x = centerX + (radius * cos(angle)).toFloat()
        val y = centerY + (radius * sin(angle)).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path = path, color = color)
}

/**
 * Edition tag shown in the top bar.
 *
 * The article id is formatted as language:pageId, so the edition travels with the model and no extra
 * parameter has to be threaded through every composable in the feed.
 */
private fun Article.languageTag(): String =
    id.substringBefore(':', missingDelimiterValue = "en").uppercase()

/** Opacity of the pill backgrounds behind the top bar labels. */
private const val PillAlpha = 0.35f

/** Opacity of the dark wash at the very top, so the status bar icons stay readable. */
private const val TopScrimAlpha = 0.45f

/** Burst progress that marks the animation as over and the circle as invisible. */
private const val BurstFinished = 1f

/** Width of one bar of the counters icon, as a fraction of the canvas. */
private const val BarWidthFraction = 0.22f

/** Distance between the left edges of two bars, as a fraction of the canvas. */
private const val BarStepFraction = 0.39f

/** Height of each bar, as a fraction of the canvas, left to right. */
private val BarHeights = listOf(0.45f, 0.72f, 1f)

/**
 * Palettes for cards without a lead image, ordered from specific to generic.
 *
 * The first entry with a keyword present among the article categories wins. Every pair stays under
 * 25% luminance at its lightest stop, which is the ceiling that keeps white body text at 0.8 alpha
 * readable on top.
 *
 * Keywords are matched against whole words. Substring matching would hand the war palette to every
 * article about software, since "software" contains "war".
 */
private val CategoryPalettes: List<Pair<List<String>, List<Color>>> = listOf(
    listOf("history", "war", "politics", "guerra", "historia") to
        listOf(Color(0xFF3A2C1E), Color(0xFF17110C)),
    listOf("science", "biology", "physics", "medicine", "ciencia") to
        listOf(Color(0xFF10293A), Color(0xFF0A141C)),
    listOf("film", "television", "music", "album", "cine", "musica") to
        listOf(Color(0xFF2C1A36), Color(0xFF140D1B)),
    listOf("biography", "people", "births", "deaths", "biografia") to
        listOf(Color(0xFF252C34), Color(0xFF11151A)),
    listOf("sport", "football", "olympic", "deporte") to
        listOf(Color(0xFF1B3226), Color(0xFF0D1712)),
    listOf("geography", "city", "country", "geografia") to
        listOf(Color(0xFF2A2A18), Color(0xFF14140C)),
)

/** Backdrop used when no category matches. */
private val NeutralGradient: List<Color> = listOf(Color(0xFF1E1E1E), Color(0xFF101010))

/** Splits a category list into lowercase words, so keywords match as words and never as prefixes. */
private fun categoryWords(categories: List<String>): Set<String> =
    categories
        .joinToString(separator = " ")
        .lowercase()
        .split(NON_WORD)
        .filter { word -> word.isNotEmpty() }
        .toSet()

/**
 * Picks the two stops of the fallback backdrop for [categories].
 *
 * Pure and free of Compose state on purpose: the mapping is data, and keeping it in a function that
 * returns colours means it can be asserted in a unit test without a composition.
 */
internal fun categoryColors(categories: List<String>): List<Color> {
    if (categories.isEmpty()) return NeutralGradient

    val words = categoryWords(categories)
    return CategoryPalettes.firstOrNull { (keywords, _) -> keywords.any { it in words } }?.second
        ?: NeutralGradient
}

/** The backdrop itself, built from [categoryColors]. */
internal fun categoryGradient(categories: List<String>): Brush =
    Brush.verticalGradient(categoryColors(categories))

private val NON_WORD = Regex("[^a-z0-9]+")


private val BackgroundTop = Color(0xFF101018)
private val BackgroundBottom = Color(0xFF1B1B24)
private val LikedColor = Color(0xFFFF2D55)
private val StarColor = Color(0xFFFFD166)
