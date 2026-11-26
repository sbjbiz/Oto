package app.oto.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo

// The window's own frame, drawn by the application rather than by the system.

/** What the caption buttons act on. */
internal class DesktopWindowActions(
    val minimize: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
)

internal val LocalDesktopWindowActions = staticCompositionLocalOf<DesktopWindowActions?> { null }

/** The window, for the drag area. */
internal val LocalDesktopWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/** A region that moves the window, and maximizes it on a double click. */
@Composable
internal fun DesktopTitleBarDragArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val scope = LocalDesktopWindowScope.current
    val actions = LocalDesktopWindowActions.current
    if (!DesktopPlatform.drawsOwnWindowFrame || scope == null) {
        Box(modifier) { content() }
        return
    }
    // Behind the content rather than around it. Wrapped around it, the drag area saw every press
    // in the toolbar too — dragging the volume slider moved the window with it. As a sibling
    // underneath, anything with its own pointer input (buttons, sliders) is hit first and the
    // press never reaches here; only empty toolbar falls through to move the window.
    Box(modifier) {
        Box(Modifier.matchParentSize().captionPress(scope, actions))
        content()
    }
}

/**
 * The Windows caption buttons — minimize, maximize/restore, close — drawn the way Windows 11 draws
 * them: flat 46×32 cells at the window's top-right corner, a grey wash on hover and red under close.
 */
@Composable
internal fun DesktopWindowButtons(modifier: Modifier = Modifier) {
    if (!DesktopPlatform.drawsOwnWindowFrame) return
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val glyphColor = if (focused) Color.White else Color.White.copy(alpha = 0.45f)

    Row(
        modifier = modifier.height(CAPTION_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.Top,
    ) {
        WindowsCaptionButton("Minimize", HOVER_WASH, glyphColor, onClick = actions.minimize) { glyph ->
            val half = GLYPH_HALF.toPx()
            drawLine(glyph, Offset(center.x - half, center.y), Offset(center.x + half, center.y), HAIRLINE.toPx())
        }
        WindowsCaptionButton(
            if (maximized) "Restore" else "Maximize",
            HOVER_WASH,
            glyphColor,
            onClick = actions.toggleMaximize,
        ) { glyph ->
            val half = GLYPH_HALF.toPx()
            val line = HAIRLINE.toPx()
            val style = androidx.compose.ui.graphics.drawscope.Stroke(width = line)
            if (!maximized) {
                drawRect(
                    glyph,
                    topLeft = Offset(center.x - half, center.y - half),
                    size = androidx.compose.ui.geometry.Size(half * 2, half * 2),
                    style = style,
                )
            } else {
                // Two overlapping squares: the front one, and the corner of the one behind it.
                val shift = 2.dp.toPx()
                val side = half * 2 - shift
                drawRect(
                    glyph,
                    topLeft = Offset(center.x - half, center.y - half + shift),
                    size = androidx.compose.ui.geometry.Size(side, side),
                    style = style,
                )
                drawLine(glyph, Offset(center.x - half + shift, center.y - half + shift), Offset(center.x - half + shift, center.y - half), line)
                drawLine(glyph, Offset(center.x - half + shift, center.y - half), Offset(center.x + half, center.y - half), line)
                drawLine(glyph, Offset(center.x + half, center.y - half), Offset(center.x + half, center.y + half - shift), line)
                drawLine(glyph, Offset(center.x + half, center.y + half - shift), Offset(center.x + half - shift, center.y + half - shift), line)
            }
        }
        WindowsCaptionButton("Close", CLOSE_RED, glyphColor, hoverGlyph = Color.White, onClick = actions.close) { glyph ->
            val half = GLYPH_HALF.toPx()
            val line = HAIRLINE.toPx()
            drawLine(glyph, Offset(center.x - half, center.y - half), Offset(center.x + half, center.y + half), line, StrokeCap.Square)
            drawLine(glyph, Offset(center.x + half, center.y - half), Offset(center.x - half, center.y + half), line, StrokeCap.Square)
        }
    }
}

@Composable
private fun WindowsCaptionButton(
    label: String,
    hoverFill: Color,
    glyphColor: Color,
    hoverGlyph: Color = glyphColor,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(width = CAPTION_BUTTON_WIDTH, height = CAPTION_HEIGHT)
            .hoverable(interaction)
            // The arrow, not the hand: this is window furniture.
            .pointerHoverIcon(PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .drawBehind {
                if (hovered) drawRect(hoverFill)
                glyph(if (hovered) hoverGlyph else glyphColor)
            },
    ) {}
}

/** Windows 11's own caption metrics. */
private val CAPTION_BUTTON_WIDTH = 46.dp
private val CAPTION_HEIGHT = 32.dp

/** Half the width of a glyph, and the weight every one of them is drawn at. */
private val GLYPH_HALF = 5.dp
private val HAIRLINE = 1.dp

private val HOVER_WASH = Color.White.copy(alpha = 0.09f)
private val CLOSE_RED = Color(0xFFC42B1C)

/**
 * A press on empty caption: moves the window, and a second one in quick succession maximizes it,
 * the way the system caption always has.
 *
 * On Windows the move is handed to the system ([DesktopWindowsFrame.startDrag]), which runs its own
 * move loop — DWM slides the window, Aero Snap works, and no edge is left exposed to paint white.
 * The double click is counted here rather than left to Windows: the area is client area as far as
 * Windows knows, so it never sends the caption's own double click. Without the native frame the
 * window is moved from the pointer, as WindowDraggableArea did.
 */
private fun Modifier.captionPress(scope: WindowScope, actions: DesktopWindowActions?): Modifier =
    pointerInput(scope, actions) {
        var lastPressAt = 0L
        var lastPressPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown()
            val double = down.uptimeMillis - lastPressAt <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastPressPosition).getDistance() <= viewConfiguration.touchSlop
            if (double) {
                lastPressAt = 0L
                down.consume()
                actions?.toggleMaximize()
                return@awaitEachGesture
            }
            lastPressAt = down.uptimeMillis
            lastPressPosition = down.position
            if (DesktopWindowsFrame.startDrag()) return@awaitEachGesture
            val window = scope.window
            val windowAtStart = window.location
            val pointerAtStart = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            drag(down.id) {
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@drag
                window.setLocation(
                    windowAtStart.x + pointer.x - pointerAtStart.x,
                    windowAtStart.y + pointer.y - pointerAtStart.y,
                )
            }
        }
    }
