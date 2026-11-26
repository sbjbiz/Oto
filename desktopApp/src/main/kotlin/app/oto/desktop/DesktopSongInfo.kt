package app.oto.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.oto.data.SongInfo
import app.oto.data.SongInfoRepository
import app.oto.data.compactCount
import app.oto.data.model.Song

/**
 * "Song info", as the phone has it: album, release date, views, likes and comments for this exact
 * upload, then the credits YouTube Music lists for it. Loaded only when opened.
 *
 * Drawn in a window-sized popup so it sits over the whole window wherever the menu that opened it
 * was anchored.
 */
@Composable
internal fun DesktopSongInfoDialog(song: Song, onDismiss: () -> Unit) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song.videoId, retry) {
        value = null
        value = runCatching { SongInfoRepository.load(song) }.getOrElse { SongInfo(failed = true) }
    }

    Popup(
        popupPositionProvider = remember { FullWindowPosition },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.fillMaxSize()) {
            DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 420, popup = true) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        DesktopStrings["song_info", "Song info"],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        song.title,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.artist.isNotBlank()) {
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DesktopSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DesktopCardRule()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val current = info
                    val unavailable = if (current != null) "Unavailable" else null
                    val rows = listOf(
                        "Album" to (current?.album ?: song.albumName),
                        "Release date" to (current?.releaseDate ?: current?.releaseYear),
                        "Views" to current?.views?.let(::compactCount),
                        "Likes" to current?.likes,
                        "Comments" to current?.comments,
                    )
                    rows.forEachIndexed { index, (label, value) ->
                        if (index > 0) DesktopCardRule(Modifier.padding(start = 20.dp))
                        SongInfoRow(label, value ?: unavailable)
                    }
                    current?.credits?.forEach { credit ->
                        DesktopCardRule(Modifier.padding(start = 20.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text(credit.role, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
                            Text(
                                credit.text,
                                modifier = Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }
                    if (current?.failed == true) {
                        Text(
                            "Some details couldn't be loaded.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopDestructive,
                        )
                    }
                }
                DesktopCardRule()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (info?.failed == true) {
                        TextButton(onClick = { retry++ }) { Text("Retry", color = DesktopSecondary) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(DesktopStrings["done", "Done"], color = DesktopAccent) }
                }
            }
        }
    }
}

/** Label on the left, value on the right; a small spinner while it loads. */
@Composable
private fun SongInfoRow(label: String, value: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DesktopSecondary)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = DesktopSecondary,
                    strokeWidth = 1.75.dp,
                )
            } else {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** Puts a popup at the window's origin, so a full-size popup covers the whole window. */
private object FullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}
