package app.oto.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the window is sitting on the screen: floating, maximized, or filling it. */
internal object DesktopWindowMode {

    private val _placement = MutableStateFlow(WindowPlacement.Floating)
    private val _fullScreen = MutableStateFlow(false)
    private val _maximized = MutableStateFlow(false)

    /** What the window's own `placement` is driven from. */
    val placement: StateFlow<WindowPlacement> = _placement

    /** Whether the player is filling the screen, for the controls that say so. */
    val fullScreen: StateFlow<Boolean> = _fullScreen

    /** Whether the window is maximized, for the caption button's own glyph. */
    val maximized: StateFlow<Boolean> = _maximized

    /**
     * The placement the player's full-screen button asks for.
     *
     * Maximized on Windows, not Fullscreen: Compose routes Fullscreen through Skiko's layer, which
     * swaps the rendering surface and there leaves the whole screen white.
     */
    private val fillsScreen: WindowPlacement =
        if (DesktopPlatform.drawsOwnWindowFrame) WindowPlacement.Maximized else WindowPlacement.Fullscreen

    /**
     * True while the player's video button, not F11, is what put the window in full screen — Esc
     * then belongs to the video view rather than to the window.
     */
    @Volatile
    var heldByVideo = false

    /** F11: true full screen over the taskbar, and back to exactly the window it was before. */
    fun toggle() = setFullScreen(!_fullScreen.value)

    @Volatile
    private var keysInstalled = false

    /** Keys whose release is swallowed because their press was handled here. */
    private val swallowRelease = HashSet<Int>()

    /**
     * F11 and Esc for full screen, read from AWT before Compose sees anything — so they work
     * whatever has focus, or when nothing in the window has focus at all (the Compose key handler
     * only hears keys while something inside it is focused, which is why F11 used to miss).
     */
    fun installKeys() {
        if (keysInstalled) return
        keysInstalled = true
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            when (e.id) {
                java.awt.event.KeyEvent.KEY_PRESSED -> when {
                    e.keyCode == java.awt.event.KeyEvent.VK_F11 -> {
                        // Held down, Windows repeats the press; only the first one toggles.
                        if (swallowRelease.add(e.keyCode)) toggle()
                        e.consume()
                        true
                    }
                    e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE && _fullScreen.value && !heldByVideo -> {
                        swallowRelease.add(e.keyCode)
                        setFullScreen(false)
                        e.consume()
                        true
                    }
                    else -> false
                }
                java.awt.event.KeyEvent.KEY_RELEASED -> {
                    if (swallowRelease.remove(e.keyCode)) {
                        e.consume()
                        true
                    } else {
                        false
                    }
                }
                java.awt.event.KeyEvent.KEY_TYPED ->
                    e.keyChar == '\u001B' && swallowRelease.contains(java.awt.event.KeyEvent.VK_ESCAPE)
                else -> false
            }
        }
    }

    /**
     * Maximize or restore, which is what the caption button in the corner does and what
     * double-clicking the title bar has always done.
     */
    fun toggleMaximized() {
        if (DesktopPlatform.isWindows && _fullScreen.value && DesktopWindowsFrame.setFullscreen(false)) {
            DesktopWindowBackdrop.apply()
        }
        _fullScreen.value = false
        set(if (_placement.value == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized)
    }

    /**
     * Leaves full screen, for the paths that can only mean "get me out" — closing the player, or
     * the window being put away to the tray.
     */
    fun exit() {
        if (_fullScreen.value) setFullScreen(false)
    }

    /** Takes the window's word for how it is sitting, rather than this object's. */
    fun adopt(placement: WindowPlacement) {
        if (placement == _placement.value) return
        if (placement == WindowPlacement.Floating) _fullScreen.value = false
        set(placement)
    }

    internal fun setFullScreen(on: Boolean) {
        if (!on) heldByVideo = false
        _fullScreen.value = on
        // On Windows the only thing that covers a taskbar told to stay on top is a native borderless
        // window raised above it; no Compose placement does, and true Fullscreen paints the screen
        // white. With that in place the window's placement is left as it was — it is the Win32 style
        // that changes, not the placement.
        if (DesktopPlatform.isWindows && DesktopWindowsFrame.setFullscreen(on)) {
            // The native window resets DWM's frame margin on the way out; the chosen material goes
            // back over it so the window is exactly as it was before fullscreen.
            if (!on) DesktopWindowBackdrop.apply()
            return
        }
        set(if (on) fillsScreen else WindowPlacement.Floating)
    }

    private fun set(placement: WindowPlacement) {
        _placement.value = placement
        _maximized.value = placement == WindowPlacement.Maximized
    }
}
