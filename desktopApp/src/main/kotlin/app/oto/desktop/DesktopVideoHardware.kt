package app.oto.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_NONE
import org.bytedeco.ffmpeg.global.avutil.av_buffer_unref
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_find_type_by_name
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer

/**
 * Music videos decoded by the graphics card, as a browser plays YouTube.
 *
 * On the processor alone, 2K and 4K could not keep up and were held back to FullHD. Through
 * Direct3D 11 the card decodes them, and they are offered again — but only while that is really
 * what happens: a card with no D3D11 decoder, a codec it cannot do (an old card and VP9, say), or
 * a copy back that fails puts the music video back on the processor, and back under FullHD.
 */
internal object DesktopVideoHardware {
    /** The tallest rendition the processor alone plays smoothly. */
    const val SOFTWARE_MAX_HEIGHT = 1080

    /** The tallest offered at all: 8K. */
    const val GPU_MAX_HEIGHT = 4320

    data class State(
        /** Off once the card has failed at a video, for the rest of the run. */
        val enabled: Boolean = true,
        /** Off once a video above FullHD turned out to decode on the processor. */
        val highRes: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** FFmpeg's D3D11 device type, or [AV_HWDEVICE_TYPE_NONE] off Windows or without one. */
    val deviceType: Int by lazy {
        if (!DesktopPlatform.isWindows) return@lazy AV_HWDEVICE_TYPE_NONE
        runCatching { av_hwdevice_find_type_by_name("d3d11va") }.getOrDefault(AV_HWDEVICE_TYPE_NONE)
    }

    /** Whether a D3D11 decoding device opens on this machine at all. Asked once. */
    val available: Boolean by lazy {
        val type = deviceType
        if (type == AV_HWDEVICE_TYPE_NONE) {
            DesktopTrackLog.log("music video: no GPU decoding on this system; using the processor")
            return@lazy false
        }
        val device = PointerPointer<AVBufferRef>(1)
        val opened = runCatching {
            av_hwdevice_ctx_create(device, type, null as BytePointer?, null as AVDictionary?, 0) >= 0
        }.getOrDefault(false)
        if (opened) av_buffer_unref(device)
        device.deallocate()
        DesktopTrackLog.log(
            if (opened) "music video: GPU decoding available (Direct3D 11)"
            else "music video: the GPU decoder would not open; using the processor",
        )
        opened
    }

    /** Whether the next video should try the card. */
    val useGpu: Boolean get() = _state.value.enabled && available

    /** The tallest rendition worth playing right now. */
    val maxHeight: Int
        get() = if (useGpu && _state.value.highRes) GPU_MAX_HEIGHT else SOFTWARE_MAX_HEIGHT

    /**
     * The tallest picture worth decoding to: the tallest screen attached, so a 4K video is shown
     * at the screen's own sharpness rather than at its full size on a smaller screen.
     */
    val screenHeight: Int by lazy {
        runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .maxOf { it.displayMode.height }
        }.getOrDefault(SOFTWARE_MAX_HEIGHT).coerceAtLeast(720)
    }

    fun disable(reason: String) {
        if (!_state.value.enabled) return
        DesktopTrackLog.log("music video: GPU decoding off for this run — $reason")
        _state.update { it.copy(enabled = false) }
    }

    fun noHighRes(reason: String) {
        if (!_state.value.highRes) return
        DesktopTrackLog.log("music video: above FullHD off for this run — $reason")
        _state.update { it.copy(highRes = false) }
    }
}
