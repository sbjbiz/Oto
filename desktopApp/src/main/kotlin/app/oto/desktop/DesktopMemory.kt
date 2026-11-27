package app.oto.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Oto small while nobody is looking at it.
 *
 * Music keeps playing from the tray or a minimized window, and that needs very little: the audio
 * engine and a few buffers. What took the memory was everything drawn on screen — decoded cover
 * art, Skia's caches, a heap grown while browsing. A little after the window leaves the screen
 * those are let go and the unused pages handed back to Windows; they come back as soon as the
 * window does.
 */
internal object DesktopMemory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /** Long enough that a quick minimize-and-restore never pays for a trim. */
    private const val SETTLE_MS = 8_000L

    @Synchronized
    fun onWindowShown(shown: Boolean) {
        pending?.cancel()
        pending = null
        if (shown) return
        pending = scope.launch {
            delay(SETTLE_MS)
            trim()
        }
    }

    private fun trim() {
        runCatching {
            coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).memoryCache?.clear()
        }
        // Skia's CPU-side font and resource caches. Looked up by name so a Skia without the call
        // simply skips it.
        runCatching {
            val graphics = Class.forName("org.jetbrains.skia.Graphics")
            val instance = graphics.getField("INSTANCE").get(null)
            graphics.getMethod("purgeAllCaches").invoke(instance)
        }
        System.gc()
        DesktopWindowsFrame.trimWorkingSet()
        DesktopTrackLog.log("memory: trimmed while the window is out of sight")
    }
}
