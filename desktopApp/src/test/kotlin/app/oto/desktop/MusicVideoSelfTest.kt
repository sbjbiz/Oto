package app.oto.desktop

import app.oto.data.YtMusicRepository
import app.oto.data.innertube.StreamResolver
import app.oto.data.model.SearchFilter
import app.oto.data.model.SearchResult
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
        // Round trip: back to audio must land on the exact song it started from.
        val back = runBlocking { YtMusicRepository.resolveAudio(video!!.copy(isVideo = true)) }
        println("convert back: ${video.videoId} -> ${back.videoId} '${back.title}'")
        assertTrue(back.videoId == rick.videoId, "round trip landed on ${back.videoId}, not ${rick.videoId}")
        for (query in listOf("Blinding Lights The Weeknd", "Speak to Me Pink Floyd", "Comfortably Numb Pink Floyd")) {
            val track = runCatching { song(query) }.getOrNull() ?: continue
            val found = runBlocking { YtMusicRepository.resolveVideo(track) }
            println("convert: '${track.title}' by ${track.artist} -> ${found?.let { "${it.videoId} '${it.title}'" } ?: "no video"}")
        }
    }

    @Test
    fun homeLoadsWithoutAnAccount() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val home = runBlocking { YtMusicRepository.home() }.getOrThrow()
        home.shelves.forEach { println("home shelf: ${it.title} (${it.items.size})") }
        assertTrue(home.shelves.isNotEmpty(), "home came back empty")
    }

    @Test
    fun autoplayStationWorksWithoutAnAccount() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val station = runBlocking { DesktopSearchClient.radio("dQw4w9WgXcQ") }.getOrThrow()
        println("autoplay station: ${station.size} tracks, first: ${station.take(3).joinToString { it.title }}")
        assertTrue(station.size >= 5, "autoplay station came back with ${station.size} tracks")
    }
}
