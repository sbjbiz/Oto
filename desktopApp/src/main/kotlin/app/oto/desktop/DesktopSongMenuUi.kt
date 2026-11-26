package app.oto.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.oto.data.model.Song
import app.oto.ui.icons.OtoIcons

/** What the song menu can do to a track. */
internal data class DesktopSongActions(
    val downloaded: Boolean,
    val downloadInProgress: Boolean,
    val onAddToPlaylist: (Song) -> Unit,
    val onDownload: (Song) -> Unit,
    val onRemoveDownload: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onOpenAlbum: (String) -> Unit,
    val onOpenArtist: (String) -> Unit,
)

/** The "…" on a row, and the menu it opens. */
@Composable
internal fun DesktopSongMenuAnchor(
    song: Song,
    actions: DesktopSongActions,
    modifier: Modifier = Modifier,
) {
    var open by remember(song.videoId) { mutableStateOf(false) }
    Box(modifier) {
        DesktopMoreButton(onClick = { open = true })
        if (open) {
            DesktopSongMenuFor(song = song, actions = actions, onDismiss = { open = false })
        }
    }
}

/**
 * Oto's song menu: Convert to video (player only), Add to playlist, Play next, Open album,
 * Open artist, Download, Song info.
 */
@Composable
internal fun DesktopSongMenuFor(
    song: Song,
    actions: DesktopSongActions,
    onDismiss: () -> Unit,
    /** "Convert to video" / "Convert to audio" for the playing track; null on a list's menu. */
    onConvertVersion: (() -> Unit)? = null,
    converting: Boolean = false,
) {
    var showingSongInfo by remember(song.videoId) { mutableStateOf(false) }
    if (showingSongInfo) {
        // The menu steps aside for the dialog; closing it closes both.
        DesktopSongInfoDialog(song = song, onDismiss = onDismiss)
        return
    }
    // A local file or a finished download has no YouTube identity to queue into a playlist or
    // fetch again.
    val isOffline = song.localUri != null
    fun run(action: () -> Unit) {
        action()
        onDismiss()
    }

    DesktopMenuSurface(onDismiss = onDismiss) {
        onConvertVersion?.let { convert ->
            DesktopMenuRow(
                icon = if (song.isVideo) Icons.Rounded.MusicNote else Icons.Rounded.Videocam,
                label = if (song.isVideo) "Convert to audio" else "Convert to video",
                busy = converting,
                enabled = !converting,
            ) { run(convert) }
            DesktopMenuDivider()
        }
        if (!isOffline) {
            DesktopMenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") {
                run { actions.onAddToPlaylist(song) }
            }
        }
        DesktopMenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { run { actions.onPlayNext(song) } }

        val albumId = song.albumId
        val artistId = song.artistId
        if (albumId != null) {
            DesktopMenuRow(Icons.Rounded.Album, "Open album") { run { actions.onOpenAlbum(albumId) } }
        }
        if (artistId != null) {
            DesktopMenuRow(Icons.Rounded.Person, "Open artist") { run { actions.onOpenArtist(artistId) } }
        }

        DesktopMenuDivider()
        when {
            actions.downloadInProgress -> DesktopMenuRow(
                icon = OtoIcons.Download,
                label = "Cancel download",
                busy = true,
            ) { run { actions.onDownload(song) } }
            actions.downloaded -> DesktopMenuRow(
                icon = Icons.Rounded.DownloadDone,
                label = "Saved to Downloads",
                tint = DesktopAccent,
                value = "Delete",
            ) { run { actions.onRemoveDownload(song) } }
            !isOffline -> DesktopMenuRow(OtoIcons.Download, "Download") { run { actions.onDownload(song) } }
        }
        DesktopMenuRow(Icons.Rounded.Info, "Song info") { showingSongInfo = true }
    }
}

@Composable
private fun DesktopMenuSurface(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        popupPositionProvider = remember { DesktopMenuPosition() },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .widthIn(min = 232.dp, max = 300.dp)
                .clip(shape)
                .desktopCard(shape)
                .padding(vertical = 6.dp)
                // A menu with every optional row on it is taller than a small window; capped and
                // scrolled rather than run off the screen.
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/** Where a menu hangs relative to the button that opened it. */
private class DesktopMenuPosition : PopupPositionProvider {

    private var above: Boolean? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = MENU_MARGIN
        val below = anchorBounds.bottom + margin
        val above = anchorBounds.top - popupContentSize.height - margin
        val fitsBelow = below + popupContentSize.height + margin <= windowSize.height
        val fitsAbove = above >= margin
        val placeAbove = this.above ?: (!fitsBelow && fitsAbove)
        if (this.above == null) this.above = placeAbove
        val y = when {
            placeAbove && fitsAbove -> above
            fitsBelow -> below
            // Neither side has room, so it is as tall as the window allows and sits between the
            // margins.
            else -> margin
        }
        val x = anchorBounds.left.coerceIn(
            margin,
            (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin),
        )
        return IntOffset(x, y.coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
    }
}

/** Air kept between a menu and the window's edges. */
private const val MENU_MARGIN = 12

@Composable
private fun DesktopMenuDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 5.dp),
        thickness = 0.5.dp,
        color = DesktopCardEdge,
    )
}

/** One menu row. */
@Composable
private fun DesktopMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    value: String = "",
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered && enabled) DesktopRowHover else Color.Transparent)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(15.dp), color = DesktopAccent, strokeWidth = 2.dp)
        } else {
            Icon(
                icon,
                null,
                tint = tint ?: if (enabled) Color.White.copy(alpha = 0.82f) else DesktopSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = tint ?: if (enabled) Color.White else DesktopSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value.isNotBlank()) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        }
    }
}

/** The "…" the menu hangs off, sized and tinted to sit beside the player's heart. */
@Composable
internal fun DesktopMoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Three dots drawn rather than taken from the icon set.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.75f)),
                )
            }
        }
    }
}
