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
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
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

/** The steps Auto moves between, by the height the picture is drawn at. */
private val AUTO_STEPS = listOf(360, 480, 720, 1080)

/** The rendition to play: the held height where the video has it, otherwise Auto's pick. */
private fun pickRendition(
    renditions: List<StreamResolver.VideoOnly>,
    held: Int?,
    autoTarget: Int,
): StreamResolver.VideoOnly? {
    if (renditions.isEmpty()) return null
    held?.let { wanted -> renditions.firstOrNull { it.height == wanted }?.let { return it } }
    val target = held ?: autoTarget
    // The smallest rendition that covers the target, else the largest there is.
    return renditions.filter { it.height >= target }.minByOrNull { it.height }
        ?: renditions.maxByOrNull { it.height }
}

/**
 * One playing music video, shared by every view showing it.
 *
 * The player's sleeve and the full-screen view used to open the stream each on their own, so
 * going full screen (and back) meant a fresh lookup, a fresh connection and a seek — a second or
 * more of black each way. Now the decoder lives here, and a view only draws its latest frame:
 * switching views is instant. Quality follows the largest view showing it and only ever steps
 * up within a session, so leaving full screen never reopens the stream at a lower rendition.
 */
internal class DesktopMusicVideoSession(val videoId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _frame = MutableStateFlow<ImageBitmap?>(null)

    /** The newest picture; it stays up across a change of rendition rather than going black. */
    val frame: StateFlow<ImageBitmap?> = _frame

    private val wanted = MutableStateFlow<Map<Any, Int>>(emptyMap())
    internal var holders = 0
    internal var closing: Job? = null

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
            DesktopMusicVideoQuality.offered(renditions.map { it.height })
            // Wait for a view to say how big it is, so the first open is already the right one.
            wanted.first { it.isNotEmpty() }
            var highest = 0
            combine(
                DesktopMusicVideoQuality.state.map { it.held }.distinctUntilChanged(),
                wanted.map { sizes -> sizes.values.maxOrNull() ?: 0 },
            ) { held, tallest ->
                highest = maxOf(highest, tallest)
                val autoTarget = AUTO_STEPS.firstOrNull { it >= highest } ?: AUTO_STEPS.last()
                pickRendition(renditions, held, autoTarget)
            }
                .distinctUntilChanged { old, new -> old?.url == new?.url }
                .collectLatest { stream -> if (stream != null) play(stream) }
        }
    }

    private suspend fun play(stream: StreamResolver.VideoOnly) {
        DesktopMusicVideoQuality.active(stream.height)
        val decoder = DesktopVideoDecoder()
        val opened = withContext(Dispatchers.IO) {
            decoder.open(stream.url, runCatching { StreamResolver.mediaHeadersFor(stream.url) }.getOrDefault(emptyMap()))
        }
        if (opened.isFailure) {
            DesktopTrackLog.log("music video: ${opened.exceptionOrNull()?.message}")
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            return
        }
        // Two bitmaps written in turn and reused for the whole video, so a frame costs a copy into
        // memory that already exists rather than a fresh full-size allocation.
        val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
        val buffers = Array(2) { Bitmap().apply { allocPixels(info) } }
        val images = buffers.map { it.asComposeImageBitmap() }
        var next = 0
        try {
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            var shownPts = -1L
            var seeksSeen = DesktopMusicVideoClock.seeks
            var pendingPts = -1L
            // Start where the audio already is: a conversion lands mid-song.
            withContext(Dispatchers.IO) { decoder.seekTo(DesktopMusicVideoClock.nowMs()) }
            while (currentCoroutineContext().isActive) {
                val target = DesktopMusicVideoClock.nowMs()
                val jumped = DesktopMusicVideoClock.seeks != seeksSeen
                val drifted = shownPts >= 0 && (target < shownPts - 750 || target > shownPts + 2_500)
                if (jumped || drifted) {
                    seeksSeen = DesktopMusicVideoClock.seeks
                    withContext(Dispatchers.IO) { decoder.seekTo(target) }
                    pendingPts = -1L
                    shownPts = -1L
                }
                if (!DesktopMusicVideoClock.playing && shownPts >= 0 && pendingPts < 0) {
                    delay(PAUSED_POLL_MS)
                    continue
                }
                if (pendingPts < 0) {
                    pendingPts = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                    if (pendingPts == END_OF_STREAM) {
                        delay(PAUSED_POLL_MS)
                        continue
                    }
                }
                val now = DesktopMusicVideoClock.nowMs()
                when {
                    pendingPts < now - LATE_MS && shownPts >= 0 -> pendingPts = -1L
                    pendingPts > now + EARLY_MS && shownPts >= 0 ->
                        delay((pendingPts - now).coerceAtMost(MAX_WAIT_MS))
                    else -> {
                        val slot = next
                        next = 1 - next
                        withContext(Dispatchers.IO) {
                            buffers[slot].installPixels(pixels)
                            buffers[slot].notifyPixelsChanged()
                        }
                        _frame.value = images[slot]
                        shownPts = pendingPts
                        pendingPts = -1L
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
        }
    }

    internal fun stop() = scope.cancel()
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

    fun acquire(videoId: String): DesktopMusicVideoSession = synchronized(open) {
        val session = open.getOrPut(videoId) { DesktopMusicVideoSession(videoId) }
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
        }
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
    val frame by session.frame.collectAsState()
    val rendered = frame != null
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(rendered) {
        reportRendered(rendered)
        if (rendered) frame?.let(reportFrame)
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
        val image = frame ?: return@Canvas
        // Letterboxed rather than cropped: a music video's frame is the picture, not decoration.
        drawRect(Color.Black)
        val clipAspect = image.width.toFloat() / image.height
        val viewAspect = size.width / size.height
        val (drawWidth, drawHeight) = if (clipAspect > viewAspect) {
            size.width to size.width / clipAspect
        } else {
            size.height * clipAspect to size.height
        }
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(((size.width - drawWidth) / 2f).roundToInt(), ((size.height - drawHeight) / 2f).roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
        )
    }
}

private const val PAUSED_POLL_MS = 60L
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

    fun open(url: String, headers: Map<String, String>): Result<Unit> {
        var last: Result<Unit> = Result.failure(IllegalStateException("could not open the video"))
        repeat(OPEN_ATTEMPTS) { attempt ->
            last = openOnce(url, headers)
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

    private fun openOnce(url: String, headers: Map<String, String>): Result<Unit> = runCatching {
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
        context.thread_count(0)
        check(avcodec_open2(context, decoder, null as AVDictionary?) >= 0) { "decoder refused to open" }
        codec = context
        sourceWidth = context.width()
        sourceHeight = context.height()
        check(sourceWidth > 0 && sourceHeight > 0) { "the video has no size" }
        val scale = minOf(1.0, MAX_HEIGHT.toDouble() / sourceHeight)
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
                val converter = scalerFor(decoded.format()) ?: return END_OF_STREAM
                sws_scale(converter, decoded.data(), decoded.linesize(), 0, sourceHeight, planes, strides)
                view?.let { pixels ->
                    pixels.rewind()
                    pixels.get(into, 0, minOf(into.size, width * height * 4))
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
        val built = sws_getContext(
            sourceWidth, sourceHeight, pixelFormat,
            width, height, AV_PIX_FMT_BGRA,
            SWS_BILINEAR, null, null, null as DoublePointer?,
        ) ?: return null
        scaler = built
        scalerFormat = pixelFormat
        return built
    }

    fun close() {
        frame?.let { av_frame_free(it) }
        packet?.let { av_packet_free(it) }
        scaler?.let { sws_freeContext(it) }
        codec?.let { avcodec_free_context(it) }
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

    private companion object {
        const val MAX_HEIGHT = 1080
        const val OPEN_ATTEMPTS = 3
        const val IO_BUFFER_BYTES = 64 * 1024
        const val SEEK_CUR = 1
        const val SEEK_END = 2
    }
}
