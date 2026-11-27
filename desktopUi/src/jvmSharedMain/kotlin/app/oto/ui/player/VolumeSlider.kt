package app.oto.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The volume bar: [ThinSlider]'s hairline capsule, in volume units (1f is 100%).
 *
 * With [boost] off it is the plain bar it always was, 0–100%. With [boost] on it reaches
 * [VOLUME_BOOST_MAX] (600%): the left half of the bar is 0–100% at the same feel as before, the
 * right half is 100–600%, and the part past 100% turns pink, brighter and glowing more the
 * louder it goes. A drag snaps to 100% as it passes, a double-click goes back to it, and the
 * level shows above the bar while dragging.
 *
 * The mouse wheel over the bar moves it 5% a notch, boosted or not.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    boost: Boolean = false,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
    markerColor: Color = Color.White.copy(alpha = 0.5f),
) {
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val height by animateDpAsState(
        targetValue = if (dragging) activeHeight else idleHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "volumeHeight",
    )
    val latestValue by rememberUpdatedState(value)
    val boosted by rememberUpdatedState(boost)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The same generous invisible target as the seek bar.
            .height(activeHeight + 22.dp)
            .pointerInput(Unit) {
                var lastUpAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    widthPx = size.width.toFloat()
                    if (boosted && down.uptimeMillis - lastUpAt < DOUBLE_CLICK_MS) {
                        // Double-click: straight back to 100%.
                        down.consume()
                        change(1f)
                        finished?.invoke()
                        lastUpAt = 0L
                        while (awaitPointerEvent().changes.any { it.pressed }) Unit
                        return@awaitEachGesture
                    }
                    dragging = true
                    change(volumeAt(down.position.x / size.width, boosted))
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            lastUpAt = pointer.uptimeMillis
                            break
                        }
                        if (pointer.positionChanged()) {
                            change(volumeAt(pointer.position.x / size.width, boosted))
                            pointer.consume()
                        }
                    }
                    dragging = false
                    finished?.invoke()
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val wheel = event.changes.firstOrNull() ?: continue
                        val dy = wheel.scrollDelta.y
                        if (dy == 0f) continue
                        // Wheel up is louder. A notch is one step; a touchpad's fractions still move.
                        val steps = if (abs(dy) >= 1f) -dy.roundToInt() else -sign(dy).toInt()
                        change(scrolledVolume(latestValue, steps, if (boosted) VOLUME_BOOST_MAX else 1f))
                        finished?.invoke()
                        wheel.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val radius = CornerRadius(size.height / 2f)
            val position = positionOf(value, boost)
            val filled = size.width * position
            val hundred = size.width * HUNDRED_AT
            val boostShare = if (boost) ((value - 1f) / (VOLUME_BOOST_MAX - 1f)).coerceIn(0f, 1f) else 0f
            val hot = lerp(BoostPinkSoft, BoostPinkHot, boostShare)

            // The glow first, under everything: wider and brighter the further past 100%.
            if (boostShare > 0f && filled > hundred) {
                for (ring in 1..GLOW_RINGS) {
                    val spread = ring * GLOW_SPREAD.toPx() * (0.5f + boostShare)
                    drawRoundRect(
                        color = hot.copy(alpha = GLOW_ALPHA * boostShare / ring),
                        topLeft = Offset(hundred - spread, -spread),
                        size = Size(filled - hundred + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(size.height / 2f + spread),
                    )
                }
            }

            drawRoundRect(color = inactiveColor, cornerRadius = radius)
            // Where 100% sits, while the bar can go past it.
            if (boost && filled < hundred) {
                val tick = 1.5.dp.toPx()
                drawRect(
                    color = markerColor,
                    topLeft = Offset(hundred - tick / 2, 0f),
                    size = Size(tick, size.height),
                )
            }
            if (filled > 0f) {
                val width = filled.coerceAtLeast(size.height).coerceAtMost(size.width)
                if (boost && filled > hundred) {
                    // White to 100%, then pink from soft at the mark to [hot] at the level.
                    val split = (hundred / width).coerceIn(0f, 1f)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to activeColor,
                                split to activeColor,
                                (split + 0.0001f).coerceAtMost(1f) to BoostPinkSoft,
                                1f to hot,
                            ),
                            startX = 0f,
                            endX = width,
                        ),
                        size = Size(width, size.height),
                        cornerRadius = radius,
                    )
                } else {
                    drawRoundRect(
                        color = activeColor,
                        size = Size(width, size.height),
                        cornerRadius = radius,
                    )
                }
            }
        }

        // The level, above the bar while it is being dragged.
        if (boost && dragging && widthPx > 0f) {
            val density = LocalDensity.current
            val bubbleWidth = with(density) { BUBBLE_WIDTH.roundToPx() }
            val lift = with(density) { BUBBLE_LIFT.roundToPx() }
            val x = (widthPx * positionOf(value, true)).roundToInt() - bubbleWidth / 2
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x, -lift),
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                Box(
                    modifier = Modifier
                        .width(BUBBLE_WIDTH)
                        .background(Color(0xF0202020), RoundedCornerShape(8.dp))
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${(value * 100).roundToInt()}%",
                        color = if (value > 1.001f) BoostPinkSoft else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The three boost sounds, as plain text under the player's volume bar: the one in force in
 * white on a faint capsule, the others grey.
 */
@Composable
fun BoostToneRow(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((key, label) in BOOST_TONE_LABELS) {
            val on = key == selected
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .background(
                        color = if (on) tint.copy(alpha = 0.14f) else Color.Transparent,
                        shape = RoundedCornerShape(50),
                    )
                    .pointerInput(key) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            val up = waitForRelease()
                            if (up) onSelect(key)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = tint.copy(alpha = if (on) 0.95f else 0.5f),
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Waits for the press to end; true when it ended as a click rather than a cancel. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForRelease(): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: return false
        if (!change.pressed) {
            change.consume()
            val inside = change.position.x in 0f..size.width.toFloat() &&
                change.position.y in 0f..size.height.toFloat()
            return inside
        }
    }
}

private val BOOST_TONE_LABELS = listOf(
    BoostTone.DEFAULT to "Default",
    BoostTone.VOICE to "Voice boost",
    BoostTone.BASS to "Bass boost",
)

/** Where on the bar, 0..1, [volume] sits. */
internal fun positionOf(volume: Float, boost: Boolean): Float =
    if (!boost) {
        volume.coerceIn(0f, 1f)
    } else if (volume <= 1f) {
        volume.coerceAtLeast(0f) * HUNDRED_AT
    } else {
        HUNDRED_AT + (volume.coerceAtMost(VOLUME_BOOST_MAX) - 1f) / (VOLUME_BOOST_MAX - 1f) * (1f - HUNDRED_AT)
    }

/** The volume at [fraction] of the bar, snapping to 100% as a boosted drag passes it. */
internal fun volumeAt(fraction: Float, boost: Boolean): Float {
    val at = fraction.coerceIn(0f, 1f)
    if (!boost) return at
    val volume = if (at <= HUNDRED_AT) {
        at / HUNDRED_AT
    } else {
        1f + (at - HUNDRED_AT) / (1f - HUNDRED_AT) * (VOLUME_BOOST_MAX - 1f)
    }
    return if (volume in SNAP_BELOW..SNAP_ABOVE) 1f else volume
}

/** [volume] moved [steps] wheel notches, landing on whole 5% steps. */
internal fun scrolledVolume(volume: Float, steps: Int, max: Float): Float {
    if (steps == 0) return volume
    val percent = volume * 100f
    val grid = if (steps > 0) floor(percent / STEP_PERCENT + 0.001f) else ceil(percent / STEP_PERCENT - 0.001f)
    return ((grid + steps) * STEP_PERCENT / 100f).coerceIn(0f, max)
}

/** 100% sits halfway along a boosted bar. */
private const val HUNDRED_AT = 0.5f
private const val SNAP_BELOW = 0.96f
private const val SNAP_ABOVE = 1.08f
private const val STEP_PERCENT = 5f
private const val DOUBLE_CLICK_MS = 320L

private val BoostPinkSoft = Color(0xFFFF9ACB)
private val BoostPinkHot = Color(0xFFFF2E93)
private const val GLOW_RINGS = 3
private val GLOW_SPREAD = 1.6.dp
private const val GLOW_ALPHA = 0.32f

private val BUBBLE_WIDTH = 48.dp
private val BUBBLE_LIFT = 26.dp
