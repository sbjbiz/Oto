package app.oto.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oto.data.canvas.CanvasArtwork
import app.oto.data.model.Song
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/** How long the controls stay over a playing full-screen video untouched. */
private const val VIDEO_CONTROLS_IDLE_MS = 3_000L

/** Whether this "canvas" is the track's own music video rather than motion artwork. */
internal fun CanvasArtwork?.isMusicVideo(): Boolean = this?.url?.startsWith(MUSIC_VIDEO_SCHEME) == true

/**
 * The two glass boxes in a music video's top-right corner: the quality box, which names the
 * rendition on screen and opens the quality drawer, and the full-screen button beside it.
 */
@Composable
internal fun VideoCornerControls(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoQualityBox(onClick = onOpenQuality)
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the quality box and drawer say is playing: the held height, else the one on screen. */
private fun MusicVideoQualityUi.selected(): Int? =
    if (held == null || held !in heights) activeHeight else held

/** The playing video's quality — 360p, 480p, HD, FullHD, 2K, 4K — or Auto until it is known. */
@Composable
private fun VideoQualityBox(onClick: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    val label = quality.selected()?.let(::videoQualityLabel) ?: "Auto"
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "videoQualityLabel",
        ) { text ->
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * The quality list: Auto, then every height this video really offers, the one playing marked.
 * Picking one holds it from now on — for the next video too, where that video has it.
 */
@Composable
internal fun VideoQualitySheet(hazeState: HazeState, onDismiss: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    val selected = quality.selected()
    PlayerDrawer(
        hazeState = hazeState,
        title = "Video quality",
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QualityRow(
                name = "Auto",
                detail = if (quality.held == null) selected?.let { "${it}p" } else null,
                active = quality.held == null,
            ) {
                PlayerPlatform.host.setMusicVideoQuality(null)
                onDismiss()
            }
            quality.heights.forEach { height ->
                QualityRow(
                    name = videoQualityLabel(height),
                    detail = "${height}p",
                    active = quality.held != null && selected == height,
                ) {
                    PlayerPlatform.host.setMusicVideoQuality(height)
                    onDismiss()
                }
            }
        }
    }
}

/** One row of [VideoQualitySheet], drawn as the audio output drawer draws an output. */
@Composable
private fun QualityRow(name: String, detail: String?, active: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !active,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = Color.White.copy(alpha = if (active) 1f else 0.85f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A music video filling the window: the picture letterboxed in black, and over it the title,
 * the corner controls, the transport and the scrubber. Moving the mouse brings the controls up;
 * they stand down again a few seconds after the last movement while it plays.
 */
@Composable
internal fun FullscreenVideoPlayer(
    song: Song,
    video: @Composable (Modifier) -> Unit,
    isPlaying: Boolean,
    onExitFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsShown by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, isPlaying, touches) {
        if (controlsShown && isPlaying) {
            delay(VIDEO_CONTROLS_IDLE_MS)
            controlsShown = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any pointer movement or press wakes the controls and starts the idle timer over.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        controlsShown = true
                        touches++
                    }
                }
            },
    ) {
        video(Modifier.fillMaxSize())

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.62f),
                            0.25f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    )
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    VideoCornerControls(
                        fullscreen = true,
                        onToggleFullscreen = onExitFullscreen,
                        onOpenQuality = onOpenQuality,
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 900.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    scrubber()
                    Box(Modifier.widthIn(max = 420.dp)) { transport() }
                }
            }
        }
    }
}
