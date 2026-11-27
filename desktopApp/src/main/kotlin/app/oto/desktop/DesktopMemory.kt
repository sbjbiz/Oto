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

    /** How often the memory line goes into oto.log. */
    private const val REPORT_EVERY_MS = 5 * 60_000L

    /**
     * A line in oto.log every few minutes: the heap, the whole process as Windows counts it, and
     * the native side FFmpeg's bindings track — so memory that climbs over a long session shows
     * when it started and where it went.
     */
    fun startReporting() {
        scope.launch {
            delay(60_000L)
            while (true) {
                report()
                delay(REPORT_EVERY_MS)
            }
        }
    }

    private fun report() {
        runCatching {
            val mb = 1024L * 1024L
            val runtime = Runtime.getRuntime()
            val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / mb
            val heapMax = runtime.maxMemory() / mb
            val process = org.bytedeco.javacpp.Pointer.physicalBytes() / mb
            val native = org.bytedeco.javacpp.Pointer.totalBytes() / mb
            DesktopTrackLog.log(
                "memory: process ${process}MB · heap ${heapUsed}/${heapMax}MB · " +
                    "native tracked ${native}MB · videos open ${DesktopMusicVideoSessions.openCount}",
            )
        }
    }

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
