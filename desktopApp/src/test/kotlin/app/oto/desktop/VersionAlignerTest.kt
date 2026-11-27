package app.oto.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The aligner on synthetic audio, where the right answer is known. Runs everywhere, offline. */
class VersionAlignerTest {
    private val rate = DesktopVersionAligner.SAMPLE_RATE

    /** A "song": a tone whose loudness moves in an irregular pattern, as music does. */
    private fun song(seconds: Double, seed: Int): FloatArray {
        val random = Random(seed)
        val out = FloatArray((seconds * rate).toInt())
        var level = 0.5
        for (i in out.indices) {
            if (i % (rate / 8) == 0) level = 0.1 + random.nextDouble() * 0.9
            out[i] = (level * sin(2 * PI * 220.0 * i / rate)).toFloat()
        }
        return out
    }

    @Test
    fun findsAVideoIntroAndLinesTheTwoUp() {
        val track = song(120.0, seed = 7)
        val source = track.copyOf(50 * rate)
        // The "video": 12 seconds of something else, then the same recording.
        val intro = song(12.0, seed = 99)
        val video = intro + track.copyOf(98 * rate)
        val result = DesktopVersionAligner.compareSamples(source, video)
        println("aligner: offset=${result.offsetMs} confidence=${result.confidence}")
        val offset = assertNotNull(result.offsetMs, "no offset found")
        assertTrue(abs(offset - 12_000) <= 30, "offset $offset, wanted 12000")
        assertTrue(result.confidence > 0.8, "confidence ${result.confidence}")
    }

    @Test
    fun rejectsADifferentRecording() {
        val source = song(50.0, seed = 7)
        val other = song(110.0, seed = 1234)
        val result = DesktopVersionAligner.compareSamples(source, other)
        println("aligner (different): offset=${result.offsetMs} confidence=${result.confidence}")
        assertTrue(result.offsetMs == null, "matched a different recording at ${result.offsetMs}")
        assertTrue(result.different, "confidence ${result.confidence} not low enough to call it different")
    }
}
