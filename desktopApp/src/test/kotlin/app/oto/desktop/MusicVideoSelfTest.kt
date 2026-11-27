package app.oto.desktop

import app.oto.data.TrackLog
import app.oto.data.YtMusicRepository
import app.oto.data.innertube.InnerTubeXResolver
import app.oto.data.innertube.potoken.PoTokenGenerator
import app.oto.data.innertube.StreamResolver
import app.oto.data.model.SearchFilter
import app.oto.data.model.SearchResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End to end over the network: finds a music video's picture-only renditions, opens one and
 * decodes frames into the same reusable bitmap the player draws. Only runs when OTO_NET_TEST=1.
 */
class MusicVideoSelfTest {
    private companion object {
        /** The same YouTube setup the app does at start-up, so streams resolve as they do there. */
        val youtubeReady: Boolean by lazy {
            TrackLog.echo = { level, tag, message, error ->
                if (tag.contains("align", true) || level == 'W' || level == 'E') {
                    println("  log $tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
                }
            }
            StreamResolver.maxKbps = { DesktopStreamClient.ceiling.get() ?: Int.MAX_VALUE }
            InnerTubeXResolver.init(
                filesDir = DesktopMediaCache.directory.toFile(),
                store = DesktopInnerTubeXStore,
                poTokenProvider = PoTokenGenerator(
                    createMinter = { DesktopPoTokenWebView.getNewPoTokenGenerator() },
                    available = { DesktopPoTokenWebView.available },
                ).asTokenProvider(),
            )
            true
        }
    }

    @Test
    fun resolvesAndDecodesAMusicVideo() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val renditions = runBlocking { StreamResolver.resolveVideoOnly("dQw4w9WgXcQ") }
        println("renditions: " + renditions.joinToString { "${it.height}p ${it.mimeType}" })
        assertTrue(renditions.isNotEmpty(), "no picture-only renditions")
        val pick = renditions.filter { it.height <= 720 }.maxByOrNull { it.height } ?: renditions.last()
        val decoder = DesktopVideoDecoder()
        decoder.open(pick.url, StreamResolver.mediaHeadersFor(pick.url)).getOrThrow()
        try {
            println("decoding ${pick.height}p at ${decoder.width}x${decoder.height}")
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)) }
            decoder.seekTo(30_000)
            var frames = 0
            var lastPts = -1L
            repeat(30) {
                val pts = decoder.nextFrame(pixels)
                if (pts != END_OF_STREAM) {
                    frames++
                    lastPts = pts
                    assertTrue(bitmap.installPixels(pixels), "installPixels refused the frame")
                    bitmap.notifyPixelsChanged()
                }
            }
            println("decoded $frames frames, last at ${lastPts}ms")
            assertTrue(frames >= 20, "decoded only $frames frames")
            assertTrue(lastPts in 25_000..40_000, "seek landed at ${lastPts}ms")
        } finally {
            decoder.close()
        }
    }

    @Test
    fun convertToVideoFindsTheOfficialVideoOnly() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        fun song(query: String) = runBlocking {
            YtMusicRepository.search(query, SearchFilter.SONGS).getOrThrow()
                .filterIsInstance<SearchResult.Track>().map { it.song }.first { !it.isVideo }
        }
        val rick = song("Never Gonna Give You Up Rick Astley")
        val next = runBlocking { app.oto.data.innertube.Innertube.next(rick.videoId) }.toString()
        println("next: ${next.length} chars, wrappers=${Regex("playlistPanelVideoWrapperRenderer").findAll(next).count()}, counterpart=${next.contains("counterpart")}")
        Regex("musicVideoType\":\"([A-Z_]+)").findAll(next).take(4).forEach { println("next type: ${it.groupValues[1]}") }
        val vids = runBlocking { YtMusicRepository.search("Never Gonna Give You Up Rick Astley", SearchFilter.VIDEOS) }
            .getOrNull().orEmpty().filterIsInstance<SearchResult.Track>().take(5)
        vids.forEach { println("video row: ${it.song.videoId} '${it.song.title}' by '${it.song.artist}' video=${it.song.isVideo}") }
        val video = runBlocking { YtMusicRepository.resolveVideo(rick) }
        println("convert: '${rick.title}' (${rick.videoId}) -> ${video?.videoId} '${video?.title}'")
        assertTrue(video?.videoId == "dQw4w9WgXcQ", "expected the official video, got ${video?.videoId}")
        // Round trip, as the player does it: back to audio lands on the exact song it started from.
        YtMusicRepository.rememberVersions(audio = rick, video = video!!)
        val back = runBlocking { YtMusicRepository.resolveAudio(video.copy(isVideo = true)) }
        println("convert back: ${video.videoId} -> ${back.videoId} '${back.title}'")
        assertTrue(back.videoId == rick.videoId, "round trip landed on ${back.videoId}, not ${rick.videoId}")
        for (query in listOf("Blinding Lights The Weeknd", "Speak to Me Pink Floyd", "Comfortably Numb Pink Floyd")) {
            val track = runCatching { song(query) }.getOrNull() ?: continue
            val found = runBlocking { YtMusicRepository.resolveVideo(track) }
            println("convert: '${track.title}' by ${track.artist} -> ${found?.let { "${it.videoId} '${it.title}'" } ?: "no video"}")
        }
    }

    @Test
    fun convertToVideoAcrossPopularSongs() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val queries = listOf(
            "One Kiss Calvin Harris", "Levitating Dua Lipa", "Shape of You Ed Sheeran", "As It Was Harry Styles",
            "Espresso Sabrina Carpenter", "Die With A Smile Lady Gaga", "BIRDS OF A FEATHER Billie Eilish",
            "Starboy The Weeknd", "Too Many Nights Metro Boomin", "BbY WOW Karol G", "Queen St twentythree",
            "Despacito Luis Fonsi", "Raindance Dave Tems", "Cruel Summer Taylor Swift",
        )
        var converted = 0
        for (q in queries) {
            val song = runCatching {
                runBlocking { YtMusicRepository.search(q, SearchFilter.SONGS) }.getOrThrow()
                    .filterIsInstance<SearchResult.Track>().map { it.song }.first { !it.isVideo }
            }.getOrNull() ?: run { println("bitchord '$q': no song row"); continue }
            val video = runCatching { runBlocking { YtMusicRepository.resolveVideo(song) } }.getOrNull()
            if (video != null) converted++
            println("bitchord '${song.title}' by '${song.artist}' -> ${video?.let { "${it.videoId} '${it.title}' by '${it.artist}'" } ?: "NO VIDEO"}")
        }
        println("bitchord: $converted of ${queries.size} converted")
    }

    @Test
    fun homeLoadsWithoutAnAccount() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val home = runBlocking { YtMusicRepository.home() }.getOrThrow()
        home.shelves.forEach { println("home shelf: ${it.title} (${it.items.size})") }
        assertTrue(home.shelves.isNotEmpty(), "home came back empty")
    }

    @Test
    fun videoPipelineKeepsUpWithTheClock() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        // The audio clock, played by hand: 30 s into the song, playing.
        val startNanos = System.nanoTime()
        DesktopMusicVideoClock.update(30_000, startNanos, playing = true, seeks = 1)
        val session = DesktopMusicVideoSessions.acquire("dQw4w9WgXcQ")
        val holder = Any()
        session.want(holder, 720)
        try {
            runBlocking {
                kotlinx.coroutines.withTimeout(30_000) { session.frame.first { it != null } }
                val shown = HashSet<Any>()
                var changes = 0
                var last: Any? = null
                val until = System.currentTimeMillis() + 4_000
                while (System.currentTimeMillis() < until) {
                    val now = session.frame.value
                    if (now !== last) { changes++; last = now }
                    kotlinx.coroutines.delay(2)
                }
                println("pipeline: $changes frames shown in 4 s, crop=${session.crop.value}, aspect=${DesktopMusicVideoSessions.aspect.value}")
                assertTrue(changes >= 60, "only $changes frames in 4 s")
            }
        } finally {
            session.want(holder, 0)
            DesktopMusicVideoSessions.release(session)
        }
    }

    @Test
    fun autoplayStationWorksWithoutAnAccount() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val station = runBlocking { DesktopSearchClient.radio("dQw4w9WgXcQ") }.getOrThrow()
        println("autoplay station: ${station.size} tracks, first: ${station.take(3).joinToString { it.title }}")
        assertTrue(station.size >= 5, "autoplay station came back with ${station.size} tracks")
    }
}
