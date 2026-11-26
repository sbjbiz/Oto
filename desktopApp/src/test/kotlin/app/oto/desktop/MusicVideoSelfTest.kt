package app.oto.desktop

import app.oto.data.innertube.StreamResolver
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
    fun autoplayStationWorksWithoutAnAccount() {
        if (System.getenv("OTO_NET_TEST") != "1") return
        val station = runBlocking { DesktopSearchClient.radio("dQw4w9WgXcQ") }.getOrThrow()
        println("autoplay station: ${station.size} tracks, first: ${station.take(3).joinToString { it.title }}")
        assertTrue(station.size >= 5, "autoplay station came back with ${station.size} tracks")
    }
}
