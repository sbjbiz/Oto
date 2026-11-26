package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.ui.player.MUSIC_VIDEO_SCHEME
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
import org.jetbrains.skia.Image
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

/** The player's cover slot, playing the music video in step with the audio. */
@Composable
internal fun DesktopMusicVideoView(videoId: String, spec: CanvasVideoSpec, modifier: Modifier) {
    var frame by remember(videoId) { mutableStateOf<ImageBitmap?>(null) }
    val rendered = frame != null
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(videoId) {
        val stream = withContext(Dispatchers.IO) {
            runCatching { StreamResolver.resolveVideoOnly(videoId) }
                .onFailure { DesktopTrackLog.log("music video: lookup failed for $videoId: ${it.message}") }
                .getOrNull()
        } ?: run {
            DesktopTrackLog.log("music video: no picture stream for $videoId; showing the cover")
            return@LaunchedEffect
        }
        val decoder = DesktopVideoDecoder()
        val opened = withContext(Dispatchers.IO) {
            decoder.open(stream.url, runCatching { StreamResolver.mediaHeadersFor(stream.url) }.getOrDefault(emptyMap()))
        }
        if (opened.isFailure) {
            DesktopTrackLog.log("music video: ${opened.exceptionOrNull()?.message}")
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            return@LaunchedEffect
        }
        try {
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
            var shownPts = -1L
            var seeksSeen = DesktopMusicVideoClock.seeks
            var pendingPts = -1L
            // Start where the audio already is: a conversion lands mid-song.
            withContext(Dispatchers.IO) { decoder.seekTo(DesktopMusicVideoClock.nowMs()) }
            while (isActive) {
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
                    // Paused with something on screen: hold the frame and wait.
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
                    // Behind the audio: drop it and decode on, unless nothing is up yet.
                    pendingPts < now - LATE_MS && shownPts >= 0 -> pendingPts = -1L
                    // Early: wait for its moment, but keep checking for seeks.
                    pendingPts > now + EARLY_MS && shownPts >= 0 ->
                        delay((pendingPts - now).coerceAtMost(MAX_WAIT_MS))
                    else -> {
                        frame = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                        shownPts = pendingPts
                        pendingPts = -1L
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
        }
    }

    LaunchedEffect(rendered) {
        reportRendered(rendered)
        if (rendered) frame?.let(reportFrame)
    }
    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
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

    Canvas(modifier = modifier.graphicsLayer { this.alpha = alpha * presentationAlpha() }) {
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
        )
    }
}

private const val PAUSED_POLL_MS = 60L
private const val LATE_MS = 120L
private const val EARLY_MS = 15L
private const val MAX_WAIT_MS = 40L
private const val END_OF_STREAM = Long.MIN_VALUE

/** FFmpeg, picture only, with each frame's presentation time so it can be shown on the beat. */
private class DesktopVideoDecoder {
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

    fun open(url: String, headers: Map<String, String>): Result<Unit> = runCatching {
        DesktopAudioDecoder.ensureNetworkReady()
        val options = AVDictionary(null)
        if (headers.isNotEmpty()) {
            av_dict_set(options, "headers", headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" }, 0)
        }
        av_dict_set(options, "rw_timeout", "15000000", 0)
        av_dict_set(options, "reconnect", "1", 0)
        av_dict_set(options, "reconnect_streamed", "1", 0)

        val opened = AVFormatContext(null)
        check(avformat_open_input(opened, url, null, options) >= 0) { "could not open the video" }
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
        context.thread_count(2)
        check(avcodec_open2(context, decoder, null as AVDictionary?) >= 0) { "decoder refused to open" }
        codec = context
        sourceWidth = context.width()
        sourceHeight = context.height()
        check(sourceWidth > 0 && sourceHeight > 0) { "the video has no size" }
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight))
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
        const val MAX_EDGE = 854
    }
}
