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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.terrakok.wikwok.LocalImageLoader
import com.github.terrakok.wikwok.domain.Article
import kotlinx.coroutines.launch

/**
 * Full-screen card for one article.
 *
 * The layout follows the feed convention the audience already knows: an image on the upper half, a
 * scrim under it, and the text anchored to the bottom with the primary action within thumb reach.
 * A missing thumbnail is not a degraded state; the gradient alone is a legitimate backdrop, so the
 * card falls back to a dark wash instead of a placeholder box.
 *
 * The like control animates on the frame the finger lands rather than waiting for the view model to
 * echo the state back. That difference is what makes the tap feel responsive: the scale pop and the
 * burst are local, the persistence is remote.
 *
 * @param article content to render.
 * @param isLiked whether the article is already in the reader's likes.
 * @param onLike invoked on every tap, like and unlike alike.
 * @param onOpen invoked when the reader asks to read the full article.
 * @param modifier applied to the outermost box.
 */
@Composable
fun ArticleCard(
    article: Article,
    isLiked: Boolean,
    onLike: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BackgroundTop, BackgroundBottom)
                )
            )
    ) {
        article.imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                imageLoader = LocalImageLoader.current,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(ImageFraction)
                    .align(Alignment.TopCenter),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.38f to Color.Black.copy(alpha = 0.10f),
                        0.62f to Color.Black.copy(alpha = 0.72f),
                        1f to Color.Black,
                    )
                )
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
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(percent = 50),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color.White
                    ),
                ) {
                    Text(text = "Read more", fontWeight = FontWeight.SemiBold)
                }
                LikeButton(isLiked = isLiked, onLike = onLike)
            }
        }
    }
}

/**
 * Heart control with a scale pop and a fading burst behind it.
 *
 * Both effects are driven by [Animatable] because they need to start from the current value on a
 * rapid double tap; a tween on a boolean would restart from zero and stutter.
 */
@Composable
private fun LikeButton(
    isLiked: Boolean,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(BurstFinished) }
    val tint = if (isLiked) LikedColor else Color.White

    Box(
        modifier = modifier
            .size(56.dp)
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
                .size(32.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
        ) {
            drawHeart(filled = isLiked, color = tint)
        }
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

/** Fraction of the card height the lead image is allowed to occupy. */
private const val ImageFraction = 0.5f

/** Burst progress that marks the animation as over and the circle as invisible. */
private const val BurstFinished = 1f

private val BackgroundTop = Color(0xFF101018)
private val BackgroundBottom = Color(0xFF1B1B24)
private val LikedColor = Color(0xFFFF2D55)
