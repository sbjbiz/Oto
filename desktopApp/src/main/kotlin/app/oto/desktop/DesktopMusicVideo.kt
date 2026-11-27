package app.oto.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import app.oto.ui.player.MusicVideoQualityUi
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.oto.data.canvas.CanvasArtwork
import app.oto.data.innertube.StreamResolver
import app.oto.data.model.Song
import app.oto.ui.player.CanvasVideoSpec
import app.oto.ui.player.MUSIC_VIDEO_SCHEME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_hw_config
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_NONE
import org.bytedeco.ffmpeg.global.avutil.av_buffer_ref
import org.bytedeco.ffmpeg.global.avutil.av_buffer_unref
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create
import org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.swscale.sws_alloc_context
import org.bytedeco.ffmpeg.global.swscale.sws_scale_frame
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.swscale.sws_init_context
import org.bytedeco.ffmpeg.swscale.SwsFilter
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.javacpp.Pointer
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/*
 * Music videos — "Convert to video".
 *
 * The audio of a music video plays through the normal audio engine, exactly like any other track.
 * The picture is decoded here, separately, from YouTube's picture-only stream, and each frame is
 * shown when the audio playhead reaches it. It rides into the player through the same slot the
 * motion artwork (Canvas) uses, so the player lays it out where the cover sits.
 */

/** The audio playhead, for the picture to follow. Written by the window from the engine's state. */
internal object DesktopMusicVideoClock {
    @Volatile private var positionMs = 0L
    @Volatile private var sampledAtNanos = 0L
    @Volatile var playing = false
        private set
    @Volatile var seeks = 0
        private set

    fun update(positionMs: Long, sampledAtNanos: Long, playing: Boolean, seeks: Int) {
        this.positionMs = positionMs
        this.sampledAtNanos = sampledAtNanos
        this.playing = playing
        this.seeks = seeks
    }

    /** Where the listener is now, carried forward from the last reading while playing. */
    fun nowMs(): Long {
        if (!playing || sampledAtNanos == 0L) return positionMs
        return positionMs + ((System.nanoTime() - sampledAtNanos) / 1_000_000L).coerceIn(0L, 2_000L)
    }
}

/** The stand-in "canvas" that tells the player to show [song]'s own music video. */
internal fun musicVideoArtwork(song: Song): CanvasArtwork =
    CanvasArtwork(url = MUSIC_VIDEO_SCHEME + song.videoId)

/** Whether this track is one whose picture is a music video. */
internal fun Song.showsMusicVideo(): Boolean =
    isVideo && localUri == null && DesktopSearchClient.isVideoId(videoId)

/** The listener's video quality, and what the playing video offers — what the quality box reads. */
internal object DesktopMusicVideoQuality {
    private const val KEY = "music_video_quality"

    private val _state = MutableStateFlow(
        MusicVideoQualityUi(held = DesktopPersistence().int(KEY, 0).takeIf { it > 0 }),
    )
    val state: StateFlow<MusicVideoQualityUi> = _state

    /** Holds [height] for every video from now on; null is Auto. */
    fun hold(height: Int?) {
        DesktopPersistence().saveInt(KEY, height ?: 0)
        _state.update { it.copy(held = height) }
    }

    fun offered(heights: List<Int>) = _state.update { it.copy(heights = heights) }

    fun active(height: Int?) = _state.update { it.copy(activeHeight = height) }
}

/**
 * The steps Auto moves between, by the height the picture is drawn at — up to 4K, as a browser
 * does on a big screen in full screen. Above FullHD only while the card decodes; see
 * [DesktopVideoHardware.maxHeight].
 */
private val AUTO_STEPS = listOf(360, 480, 720, 1080, 1440, 2160)

/** The rendition to play: the held height where the video has it, otherwise Auto's pick. */
private fun pickRendition(
    all: List<StreamResolver.VideoOnly>,
    held: Int?,
    autoTarget: Int,
    maxHeight: Int,
): StreamResolver.VideoOnly? {
    val renditions = all.filter { it.height <= maxHeight }.ifEmpty { listOfNotNull(all.minByOrNull { it.height }) }
    if (renditions.isEmpty()) return null
    held?.let { wanted -> renditions.firstOrNull { it.height == wanted }?.let { return it } }
    val target = held ?: autoTarget
    // The smallest rendition that covers the target, else the largest there is.
    return renditions.filter { it.height >= target }.minByOrNull { it.height }
        ?: renditions.maxByOrNull { it.height }
}

/** Where the real picture is inside a decoded frame, once any baked-in black borders are cut off. */
internal data class VideoCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** The frame size these are measured in; a different rendition scales them. */
    val frameWidth: Int,
    val frameHeight: Int,
) {
    val aspect: Float get() = width.toFloat() / height

    /** The same picture, in a frame of another size. */
    fun scaledTo(newWidth: Int, newHeight: Int): VideoCrop {
        if (newWidth == frameWidth && newHeight == frameHeight) return this
        val sx = newWidth.toFloat() / frameWidth
        val sy = newHeight.toFloat() / frameHeight
        val l = (left * sx).roundToInt().coerceIn(0, newWidth - 1)
        val t = (top * sy).roundToInt().coerceIn(0, newHeight - 1)
        return VideoCrop(
            left = l,
            top = t,
            width = (width * sx).roundToInt().coerceIn(1, newWidth - l),
            height = (height * sy).roundToInt().coerceIn(1, newHeight - t),
            frameWidth = newWidth,
            frameHeight = newHeight,
        )
    }
}

/**
 * One playing music video, shared by every view showing it.
 *
 * The sleeve and the full-screen view draw from the same decoder, so switching between them is
 * instant. Decoding runs a few frames ahead of the picture on its own thread, and frames are
 * shown on the audio's clock: a slow frame to decode is absorbed by the ones already waiting
 * instead of showing as a stall. Quality follows the largest view showing the video, only ever
 * stepping up within a session, and never past FullHD — the most a processor decodes smoothly.
 */
internal class DesktopMusicVideoSession(val videoId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _frame = MutableStateFlow<ImageBitmap?>(null)

    /** The newest picture; it stays up across a change of rendition rather than going black. */
    val frame: StateFlow<ImageBitmap?> = _frame

    private val _crop = MutableStateFlow<VideoCrop?>(null)

    /** The picture inside the frame, borders cut off; null until the first frames have been seen. */
    val crop: StateFlow<VideoCrop?> = _crop

    private val wanted = MutableStateFlow<Map<Any, Int>>(emptyMap())
    internal var holders = 0
    internal var closing: Job? = null

    /** The shape watcher [DesktopMusicVideoSessions] runs for this session; ends with it. */
    internal var cropWatch: Job? = null

    /** How tall [holder] draws the picture, in pixels; 0 when it no longer does. */
    fun want(holder: Any, heightPx: Int) =
        wanted.update { if (heightPx <= 0) it - holder else it + (holder to heightPx) }

    init {
        scope.launch {
            val renditions = DesktopMusicVideoSessions.renditionsFor(videoId)
            if (renditions.isEmpty()) {
                DesktopTrackLog.log("music video: no picture stream for $videoId; showing the cover")
                return@launch
            }
            // Wait for a view to say how big it is, so the first open is already the right one.
            wanted.first { it.isNotEmpty() }
            var highest = 0
            combine(
                DesktopMusicVideoQuality.state.map { it.held }.distinctUntilChanged(),
                wanted.map { sizes -> sizes.values.maxOrNull() ?: 0 },
                DesktopVideoHardware.state,
            ) { held, tallest, _ ->
                highest = maxOf(highest, tallest)
                val maxHeight = DesktopVideoHardware.maxHeight
                // Only what can be played smoothly here is on the quality menu.
                DesktopMusicVideoQuality.offered(
                    renditions.map { it.height }.filter { it <= maxHeight }.distinct().sortedDescending(),
                )
                val autoTarget = AUTO_STEPS.firstOrNull { it >= highest } ?: AUTO_STEPS.last()
                pickRendition(renditions, held, autoTarget, maxHeight)?.let { it to DesktopVideoHardware.useGpu }
            }
                .distinctUntilChanged { old, new -> old?.first?.url == new?.first?.url && old?.second == new?.second }
                .collectLatest { picked -> if (picked != null) play(picked.first, picked.second) }
        }
    }

    private class Decoded(val slot: Int, val ptsMs: Long, val generation: Long)

    private suspend fun play(stream: StreamResolver.VideoOnly, useGpu: Boolean) {
        DesktopMusicVideoQuality.active(stream.height)
        val decoder = DesktopVideoDecoder()
        val opened = withContext(Dispatchers.IO) {
            decoder.open(
                stream.url,
                runCatching { StreamResolver.mediaHeadersFor(stream.url) }.getOrDefault(emptyMap()),
                useGpu = useGpu,
                gpuHeightCap = DesktopVideoHardware.screenHeight,
            )
        }
        if (opened.isFailure) {
            DesktopTrackLog.log("music video: ${opened.exceptionOrNull()?.message}")
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            return
        }
        DesktopTrackLog.log("music video: playing ${stream.height}p ${stream.codec.ifBlank { stream.mimeType }} as ${decoder.width}x${decoder.height}")
        // As BitChord does: the picture's shape from the stream itself, straight away, so the
        // player sizes the video before its first frame. The border check below refines it.
        _crop.value = _crop.value?.scaledTo(decoder.width, decoder.height)
            ?: VideoCrop(0, 0, decoder.width, decoder.height, decoder.width, decoder.height)
        // A small pool of bitmaps, reused for the whole video: [AHEAD] decoded and waiting, the one
        // on screen, and the one before it (still being drawn while the next goes up).
        val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
        val pool = Array(POOL) { Bitmap().apply { allocPixels(info) } }
        val images = pool.map { it.asComposeImageBitmap() }
        val free = Channel<Int>(POOL).apply { repeat(POOL) { trySend(it) } }
        val ready = Channel<Decoded>(AHEAD)
        val generation = java.util.concurrent.atomic.AtomicLong(0)
        val pendingSeek = java.util.concurrent.atomic.AtomicLong(DesktopMusicVideoClock.nowMs())
        val cropFinder = CropFinder(decoder.width, decoder.height)
        try {
            coroutineScope {
                // Decoding: as far ahead as the pool allows, on its own thread.
                launch(Dispatchers.IO) {
                    val pixels = ByteArray(decoder.width * decoder.height * 4)
                    var reported = false
                    while (isActive) {
                        val seekTo = pendingSeek.getAndSet(-1L)
                        val gen = generation.get()
                        if (seekTo >= 0) decoder.seekTo(seekTo)
                        val slot = free.receive()
                        val pts = decoder.nextFrame(pixels)
                        if (pts == DesktopVideoDecoder.GPU_FAILED) {
                            // Picked up again on the processor: the state change re-opens it.
                            free.send(slot)
                            DesktopVideoHardware.disable("a picture could not be copied back from the GPU")
                            delay(PAUSED_POLL_MS)
                            continue
                        }
                        if (pts == END_OF_STREAM) {
                            free.send(slot)
                            delay(PAUSED_POLL_MS)
                            continue
                        }
                        if (!reported) {
                            reported = true
                            val gpu = decoder.onGpu == true
                            DesktopTrackLog.log("music video: ${stream.height}p decoding on the ${if (gpu) "GPU" else "processor"}")
                            if (!gpu && stream.height > DesktopVideoHardware.SOFTWARE_MAX_HEIGHT) {
                                DesktopVideoHardware.noHighRes(
                                    "${stream.codec.ifBlank { stream.mimeType }} ${stream.height}p is not decoded by this GPU",
                                )
                            }
                        }
                        if (cropFinder.wants()) cropFinder.look(pixels)?.let { _crop.value = it }
                        pool[slot].installPixels(pixels)
                        pool[slot].notifyPixelsChanged()
                        ready.send(Decoded(slot, pts, gen))
                    }
                }
                // Showing: each frame when the audio reaches it.
                var onScreen = -1
                var behind = -1
                var shownPts = -1L
                var seeksSeen = DesktopMusicVideoClock.seeks
                fun retire(slot: Int) {
                    if (slot >= 0) free.trySend(slot)
                }
                fun restartAt(target: Long) {
                    pendingSeek.set(target)
                    generation.incrementAndGet()
                    while (true) retire(ready.tryReceive().getOrNull()?.slot ?: break)
                    shownPts = -1L
                }
                while (isActive) {
                    // Nobody can see it: show nothing, and the decoder idles on a full pool.
                    if (!DesktopWindowVisibility.visible.value || DesktopWindowVisibility.minimized.value) {
                        delay(HIDDEN_POLL_MS)
                        continue
                    }
                    val now = DesktopMusicVideoClock.nowMs()
                    val jumped = DesktopMusicVideoClock.seeks != seeksSeen
                    val drifted = shownPts >= 0 && (now < shownPts - 750 || now > shownPts + 2_500)
                    if (jumped || drifted) {
                        seeksSeen = DesktopMusicVideoClock.seeks
                        restartAt(now)
                    }
                    if (!DesktopMusicVideoClock.playing && shownPts >= 0) {
                        delay(PAUSED_POLL_MS)
                        continue
                    }
                    // Polled rather than awaited: a cancelled wait could swallow a frame, and with
                    // it a bitmap the pool would never get back.
                    val next = ready.tryReceive().getOrNull() ?: run {
                        delay(READY_POLL_MS)
                        null
                    } ?: continue
                    if (next.generation != generation.get()) {
                        retire(next.slot)
                        continue
                    }
                    var lead = next.ptsMs - DesktopMusicVideoClock.nowMs()
                    // A frame from before a seek the decoder had not yet acted on.
                    if (lead > STALE_MS) {
                        retire(next.slot)
                        continue
                    }
                    // Late: skip it, unless nothing is up yet.
                    if (lead < -LATE_MS && shownPts >= 0) {
                        retire(next.slot)
                        continue
                    }
                    while (lead > EARLY_MS && DesktopMusicVideoClock.playing && isActive) {
                        delay(lead.coerceAtMost(MAX_WAIT_MS))
                        lead = next.ptsMs - DesktopMusicVideoClock.nowMs()
                    }
                    _frame.value = images[next.slot]
                    retire(behind)
                    behind = onScreen
                    onScreen = next.slot
                    shownPts = next.ptsMs
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
        }
    }

    internal fun stop() {
        scope.cancel()
        // The watcher lived in the registry's scope and held this session — and its last frame —
        // for the rest of the run: one picture's worth of memory kept for every video ever played.
        cropWatch?.cancel()
        cropWatch = null
        _frame.value = null
    }
}

/**
 * Finds the black borders a video carries in its own frames — a 4:3 or cinema picture inside a
 * 16:9 stream — so they can be cut off. Looks at a handful of frames spread over the opening
 * seconds and keeps the union of what is lit, so a dark scene or a fade from black cannot shrink
 * the picture.
 */
private class CropFinder(private val width: Int, private val height: Int) {
    private var seen = 0
    private var looks = 0
    private var left = width
    private var top = height
    private var right = -1
    private var bottom = -1

    fun wants(): Boolean = looks < LOOKS && (seen++ % EVERY == EVERY - 1)

    fun look(bgra: ByteArray): VideoCrop? {
        looks++
        val step = 4
        for (y in 0 until height step 2) {
            val row = y * width * 4
            for (x in 0 until width step step) {
                val at = row + x * 4
                val luma = ((bgra[at + 2].toInt() and 0xFF) * 3 + (bgra[at + 1].toInt() and 0xFF) * 6 +
                    (bgra[at].toInt() and 0xFF)) / 10
                if (luma > BLACK) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0 || bottom < 0) return null
        val w = (right - left + step).coerceAtMost(width - left)
        val h = (bottom - top + 2).coerceAtMost(height - top)
        // Too small to be the whole picture yet (a dark opening): keep looking, show it all.
        if (w * h < width * height * 0.35) return null
        // Borders thinner than this are not borders.
        val trimmedX = width - w
        val trimmedY = height - h
        val crop = VideoCrop(
            left = if (trimmedX < width * 0.03) 0 else left,
            top = if (trimmedY < height * 0.03) 0 else top,
            width = if (trimmedX < width * 0.03) width else w,
            height = if (trimmedY < height * 0.03) height else h,
            frameWidth = width,
            frameHeight = height,
        )
        return crop
    }

    private companion object {
        const val LOOKS = 8
        const val EVERY = 12
        const val BLACK = 24
    }
}

/** The open music-video sessions, by video, and the renditions already looked up. */
internal object DesktopMusicVideoSessions {
    private val open = HashMap<String, DesktopMusicVideoSession>()
    private val lingering = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lookedUp = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StreamResolver.VideoOnly>>>()

    /** Kept a while after the last view lets go, so a view swap never closes the stream. */
    private const val LINGER_MS = 2_000L

    /** Stream URLs expire after a few hours; looked up again well before that. */
    private const val RENDITIONS_TTL_MS = 2L * 60 * 60 * 1000


    private val _aspect = MutableStateFlow(0f)

    /** How many videos are open right now, for the memory line in the log. */
    val openCount: Int get() = synchronized(open) { open.size }

    /** The playing video's picture shape, borders cut off; 0 until it is known. */
    val aspect: StateFlow<Float> = _aspect

    fun acquire(videoId: String): DesktopMusicVideoSession = synchronized(open) {
        val session = open.getOrPut(videoId) {
            _aspect.value = 0f
            DesktopMusicVideoSession(videoId).also { created ->
                val crops = created.crop
                created.cropWatch = lingering.launch { crops.collect { crop -> if (crop != null) _aspect.value = crop.aspect } }
            }
        }
        session.holders++
        session.closing?.cancel()
        session.closing = null
        session
    }

    fun release(session: DesktopMusicVideoSession) {
        synchronized(open) {
            session.holders--
            if (session.holders > 0) return
            session.closing = lingering.launch {
                delay(LINGER_MS)
                synchronized(open) {
                    if (session.holders > 0) return@launch
                    if (open[session.videoId] === session) open.remove(session.videoId)
                }
                session.stop()
            }
        }
    }

    suspend fun renditionsFor(videoId: String): List<StreamResolver.VideoOnly> {
        lookedUp[videoId]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < RENDITIONS_TTL_MS && list.isNotEmpty()) return list
        }
        val found = withContext(Dispatchers.IO) {
            runCatching { StreamResolver.resolveVideoOnly(videoId) }
                .onFailure { DesktopTrackLog.log("music video: lookup failed for $videoId: ${it.message}") }
                .getOrDefault(emptyList())
        }.filter { it.height <= DesktopVideoHardware.GPU_MAX_HEIGHT }
        if (found.isNotEmpty()) lookedUp[videoId] = System.currentTimeMillis() to found
        return found
    }
}

/** The player's cover slot, playing the music video in step with the audio. */
@Composable
internal fun DesktopMusicVideoView(videoId: String, spec: CanvasVideoSpec, modifier: Modifier) {
    val session = remember(videoId) { DesktopMusicVideoSessions.acquire(videoId) }
    val holder = remember(videoId) { Any() }
    DisposableEffect(session) {
        onDispose {
            session.want(holder, 0)
            DesktopMusicVideoSessions.release(session)
        }
    }
    // Read in the draw below, not here: a new frame then repaints the picture without recomposing.
    val frameState = session.frame.collectAsState()
    val cropState = session.crop.collectAsState()
    val rendered by remember(session) { derivedStateOf { frameState.value != null } }
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(rendered) {
        reportRendered(rendered)
        if (rendered) frameState.value?.let(reportFrame)
    }
    // Already showing when it mounts with a picture to hand (a view swap), so no fade then.
    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "musicVideoAlpha",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * presentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { session.want(holder, it.height) }
            .graphicsLayer { this.alpha = alpha * presentationAlpha() },
    ) {
        val image = frameState.value ?: return@Canvas
        // The frame on screen can be from the rendition before a quality change, so the crop is
        // scaled to whatever this frame's size is.
        val crop = cropState.value?.scaledTo(image.width, image.height)
            ?: VideoCrop(0, 0, image.width, image.height, image.width, image.height)
        // Only the picture itself, fitted to the view. The sleeve takes the picture's own shape,
        // so there it fills edge to edge; full screen keeps the whole picture in view.
        val clipAspect = crop.aspect
        val viewAspect = size.width / size.height
        val (drawWidth, drawHeight) = if (clipAspect > viewAspect) {
            size.width to size.width / clipAspect
        } else {
            size.height * clipAspect to size.height
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(crop.left, crop.top),
            srcSize = IntSize(crop.width, crop.height),
            dstOffset = IntOffset(((size.width - drawWidth) / 2f).roundToInt(), ((size.height - drawHeight) / 2f).roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
        )
    }
}

private const val PAUSED_POLL_MS = 60L
private const val POOL = 6
private const val AHEAD = 3
private const val STALE_MS = 3_000L
private const val READY_POLL_MS = 4L
private const val HIDDEN_POLL_MS = 250L
private const val LATE_MS = 120L
private const val EARLY_MS = 15L
private const val MAX_WAIT_MS = 40L
internal const val END_OF_STREAM = Long.MIN_VALUE

/** FFmpeg, picture only, with each frame's presentation time so it can be shown on the beat. */
internal class DesktopVideoDecoder {
    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var scaler: SwsContext? = null
    private var buffer: BytePointer? = null
    private var view: java.nio.ByteBuffer? = null
    private var planes: PointerPointer<BytePointer>? = null
    private var strides: IntPointer? = null
    private var scalerFormat = -1

    /** The card's decoding device, while this video decodes on it. */
    private var gpuDevice: PointerPointer<AVBufferRef>? = null

    /** The pixel format the card hands frames over in (a Direct3D surface); -1 on the processor. */
    private var gpuFormat = -1

    /** Where a frame from the card is copied back to, reused frame to frame. */
    private var copied: AVFrame? = null

    /** The finished BGRA picture, tightly packed, for the threaded scaler to write into. */
    private var scaled: AVFrame? = null
    private var frameScaling = true

    /**
     * Whether frames are really coming from the card: null before the first, false when FFmpeg
     * fell back to the processor (a codec the card cannot decode).
     */
    @Volatile
    var onGpu: Boolean? = null
        private set
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var streamIndex = -1
    private var timeBaseNum = 1
    private var timeBaseDen = 1000

    var width = 0
        private set
    var height = 0
        private set

    // Read through [DesktopRangeStream] — the Java HTTP stack, in bounded windows — rather than
    // FFmpeg's own HTTP client: googlevideo throttles or drops open-ended requests, which is the
    // same reason the audio path reads it this way.
    private var source: DesktopRangeStream? = null
    private var avio: AVIOContext? = null
    private var readPacket: Read_packet_Pointer_BytePointer_int? = null
    private var seekPacket: Seek_Pointer_long_int? = null

    /**
     * Opens [url]; with [useGpu] the card decodes it where it can, and the picture is decoded to
     * at most [gpuHeightCap] tall instead of FullHD.
     */
    fun open(
        url: String,
        headers: Map<String, String>,
        useGpu: Boolean = false,
        gpuHeightCap: Int = MAX_HEIGHT,
    ): Result<Unit> {
        var last: Result<Unit> = Result.failure(IllegalStateException("could not open the video"))
        repeat(OPEN_ATTEMPTS) { attempt ->
            last = openOnce(url, headers, useGpu, gpuHeightCap)
            if (last.isSuccess) return last
            DesktopTrackLog.log("music video: open attempt ${attempt + 1} failed: ${last.exceptionOrNull()?.message}")
            Thread.sleep(400L * (attempt + 1))
        }
        return last
    }

    private fun windowedContext(url: String, headers: Map<String, String>): AVFormatContext {
        val stream = DesktopRangeStream(url, headers)
        val scratch = ByteArray(IO_BUFFER_BYTES)
        val read = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, buffer: BytePointer?, size: Int): Int {
                if (buffer == null) return AVERROR_EOF
                val taken = stream.read(scratch, minOf(size, scratch.size))
                if (taken <= 0) return AVERROR_EOF
                buffer.put(scratch, 0, taken)
                return taken
            }
        }
        val seek = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                if (whence == AVSEEK_SIZE) return stream.length
                val target = when (whence) {
                    SEEK_CUR -> stream.position() + offset
                    SEEK_END -> stream.length + offset
                    else -> offset
                }
                stream.seek(target)
                return target
            }
        }
        val buffer = BytePointer(av_malloc(IO_BUFFER_BYTES.toLong()))
        val io = avio_alloc_context(buffer, IO_BUFFER_BYTES, 0, null, read, null, seek)
        val context = avformat_alloc_context()
        context.pb(io)
        source = stream
        readPacket = read
        seekPacket = seek
        avio = io
        return context
    }

    /** The card's device for [decoder], when it can decode it there; null otherwise. */
    private fun gpuFor(decoder: AVCodec): Pair<PointerPointer<AVBufferRef>, Int>? {
        val type = DesktopVideoHardware.deviceType
        if (type == AV_HWDEVICE_TYPE_NONE) return null
        var pixelFormat = -1
        var index = 0
        while (index < MAX_HW_CONFIGS) {
            val config = avcodec_get_hw_config(decoder, index++) ?: break
            if (config.isNull) break
            if ((config.methods() and AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0 && config.device_type() == type) {
                pixelFormat = config.pix_fmt()
                break
            }
        }
        if (pixelFormat < 0) return null
        val device = PointerPointer<AVBufferRef>(1)
        if (av_hwdevice_ctx_create(device, type, null as BytePointer?, null as AVDictionary?, 0) < 0) {
            device.deallocate()
            return null
        }
        return device to pixelFormat
    }

    private fun openOnce(
        url: String,
        headers: Map<String, String>,
        useGpu: Boolean,
        gpuHeightCap: Int,
    ): Result<Unit> = runCatching {
        DesktopAudioDecoder.ensureNetworkReady()
        val options = AVDictionary(null)
        val opened = windowedContext(url, headers)
        check(avformat_open_input(opened, null as String?, null, options) >= 0) { "could not open the video" }
        format = opened
        check(avformat_find_stream_info(opened, null as AVDictionary?) >= 0) { "no stream info" }
        streamIndex = av_find_best_stream(opened, AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
        check(streamIndex >= 0) { "no video stream" }
        val stream = opened.streams(streamIndex)
        timeBaseNum = stream.time_base().num()
        timeBaseDen = stream.time_base().den().coerceAtLeast(1)
        val parameters = stream.codecpar()
        val decoder = avcodec_find_decoder(parameters.codec_id()) ?: error("no decoder for this video")
        val context = avcodec_alloc_context3(decoder)
        check(avcodec_parameters_to_context(context, parameters) >= 0) { "bad codec parameters" }
        // With a device on the context, FFmpeg's own format choice takes the card's surfaces
        // wherever the card can decode this codec, and quietly stays on the processor where not.
        val gpu = if (useGpu) gpuFor(decoder) else null
        if (gpu != null) {
            context.hw_device_ctx(av_buffer_ref(gpu.first.get(AVBufferRef::class.java, 0)))
            gpuDevice = gpu.first
            gpuFormat = gpu.second
            // The card does the work; one thread just feeds it.
            context.thread_count(1)
        } else {
            gpuFormat = -1
            context.thread_count(0)
        }
        check(avcodec_open2(context, decoder, null as AVDictionary?) >= 0) { "decoder refused to open" }
        codec = context
        sourceWidth = context.width()
        sourceHeight = context.height()
        check(sourceWidth > 0 && sourceHeight > 0) { "the video has no size" }
        val cap = if (gpu != null) gpuHeightCap.coerceAtLeast(MAX_HEIGHT) else MAX_HEIGHT
        val scale = minOf(1.0, cap.toDouble() / sourceHeight)
        width = ((sourceWidth * scale).toInt() / 2) * 2
        height = ((sourceHeight * scale).toInt() / 2) * 2
        val size = av_image_get_buffer_size(AV_PIX_FMT_BGRA, width, height, 1)
        buffer = BytePointer(size.toLong())
        view = buffer?.asByteBuffer()
        planes = PointerPointer<BytePointer>(4)
        strides = IntPointer(4L)
        check(av_image_fill_arrays(planes, strides, buffer, AV_PIX_FMT_BGRA, width, height, 1) >= 0) {
            "could not lay out the frame buffer"
        }
        packet = av_packet_alloc()
        frame = av_frame_alloc()
    }.onFailure { close() }

    fun seekTo(ms: Long) {
        val container = format ?: return
        val context = codec ?: return
        val ts = ms.coerceAtLeast(0L) * timeBaseDen / (1000L * timeBaseNum.coerceAtLeast(1))
        av_seek_frame(container, streamIndex, ts, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(context)
    }

    /** Decodes the next frame into [into] and returns its time in ms, or [END_OF_STREAM]. */
    fun nextFrame(into: ByteArray): Long {
        val container = format ?: return END_OF_STREAM
        val context = codec ?: return END_OF_STREAM
        val pkt = packet ?: return END_OF_STREAM
        val decoded = frame ?: return END_OF_STREAM
        while (true) {
            if (avcodec_receive_frame(context, decoded) == 0) {
                var picture = decoded
                if (gpuFormat >= 0 && decoded.format() == gpuFormat) {
                    // Off the card into memory (NV12, or P010 for 10-bit), into the same frame
                    // each time rather than a fresh allocation per picture.
                    val target = copied ?: av_frame_alloc().also { copied = it }
                    if (av_hwframe_transfer_data(target, decoded, 0) < 0) return GPU_FAILED
                    picture = target
                    onGpu = true
                } else {
                    onGpu = false
                }
                val converter = scalerFor(picture.format()) ?: return END_OF_STREAM
                val bytes = minOf(into.size, width * height * 4)
                // The frame call is the one that spreads over the cores (the plain call stays on
                // one); it is used while it works, and dropped for good the first time it does not.
                val out = if (frameScaling) scaledFrame() else null
                if (out != null && sws_scale_frame(converter, out, picture) >= 0) {
                    out.data(0).get(into, 0, bytes)
                } else {
                    if (out != null) {
                        frameScaling = false
                        DesktopTrackLog.log("music video: threaded scaling refused; scaling on one core")
                        scalerFormat = -1
                    }
                    val plain = scalerFor(picture.format()) ?: return END_OF_STREAM
                    sws_scale(plain, picture.data(), picture.linesize(), 0, sourceHeight, planes, strides)
                    view?.let { pixels ->
                        pixels.rewind()
                        pixels.get(into, 0, bytes)
                    }
                }
                val pts = decoded.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: decoded.pts()
                return if (pts == AV_NOPTS_VALUE) 0L else pts * 1000L * timeBaseNum / timeBaseDen
            }
            if (av_read_frame(container, pkt) < 0) return END_OF_STREAM
            if (pkt.stream_index() == streamIndex) avcodec_send_packet(context, pkt)
            av_packet_unref(pkt)
        }
    }

    private fun scalerFor(pixelFormat: Int): SwsContext? {
        scaler?.let { if (pixelFormat == scalerFormat) return it }
        if (pixelFormat < 0) return null
        scaler?.let { sws_freeContext(it) }
        val built = threadedScaler(pixelFormat) ?: sws_getContext(
            sourceWidth, sourceHeight, pixelFormat,
            width, height, AV_PIX_FMT_BGRA,
            SWS_BILINEAR, null, null, null as DoublePointer?,
        ) ?: return null
        scaler = built
        scalerFormat = pixelFormat
        return built
    }

    /**
     * The colour conversion and resize spread over the processor's cores: from a 4K picture it
     * is the one big job left once the card has decoded it. Null where this FFmpeg cannot.
     */
    private fun threadedScaler(pixelFormat: Int): SwsContext? = runCatching {
        val context = sws_alloc_context() ?: return@runCatching null
        if (context.isNull) return@runCatching null
        av_opt_set_int(context, "srcw", sourceWidth.toLong(), 0)
        av_opt_set_int(context, "srch", sourceHeight.toLong(), 0)
        av_opt_set_int(context, "src_format", pixelFormat.toLong(), 0)
        av_opt_set_int(context, "dstw", width.toLong(), 0)
        av_opt_set_int(context, "dsth", height.toLong(), 0)
        av_opt_set_int(context, "dst_format", AV_PIX_FMT_BGRA.toLong(), 0)
        av_opt_set_int(context, "sws_flags", SWS_BILINEAR.toLong(), 0)
        av_opt_set_int(context, "threads", 0L, 0)
        if (sws_init_context(context, null as SwsFilter?, null as SwsFilter?) < 0) {
            sws_freeContext(context)
            null
        } else {
            context
        }
    }.getOrNull()

    private fun scaledFrame(): AVFrame? {
        scaled?.let { return it }
        val target = av_frame_alloc() ?: return null
        target.format(AV_PIX_FMT_BGRA)
        target.width(width)
        target.height(height)
        // Alignment 1: rows exactly width × 4 bytes apart, so the picture copies out in one go.
        if (av_frame_get_buffer(target, 1) < 0 || target.linesize(0) != width * 4) {
            av_frame_free(target)
            frameScaling = false
            return null
        }
        scaled = target
        return target
    }

    fun close() {
        copied?.let { av_frame_free(it) }
        copied = null
        scaled?.let { av_frame_free(it) }
        scaled = null
        frame?.let { av_frame_free(it) }
        packet?.let { av_packet_free(it) }
        scaler?.let { sws_freeContext(it) }
        codec?.let { avcodec_free_context(it) }
        // The context held its own reference to the card's device; this drops the last one.
        gpuDevice?.let {
            av_buffer_unref(it)
            it.deallocate()
        }
        gpuDevice = null
        gpuFormat = -1
        format?.let { avformat_close_input(it) }
        avio?.let {
            av_free(it.buffer())
            it.buffer(null as BytePointer?)
            avio_context_free(it)
        }
        avio = null
        source = null
        readPacket = null
        seekPacket = null
        buffer?.deallocate()
        strides?.deallocate()
        frame = null
        packet = null
        scaler = null
        scalerFormat = -1
        codec = null
        format = null
        buffer = null
        view = null
        planes = null
        strides = null
        streamIndex = -1
    }

    companion object {
        /** [nextFrame]: the card decoded a picture but it could not be copied back. */
        const val GPU_FAILED = -2L

        private const val MAX_HEIGHT = DesktopVideoHardware.SOFTWARE_MAX_HEIGHT
        private const val MAX_HW_CONFIGS = 16
        private const val OPEN_ATTEMPTS = 3
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val SEEK_CUR = 1
        private const val SEEK_END = 2
    }
}
