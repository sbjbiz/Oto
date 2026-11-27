package app.oto.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player must never wait for good on a decoder that died or froze, and Volume boost must get
 * louder without going past full scale. Offline; runs everywhere.
 */
class PlaybackSafetyTest {
    private val format = DesktopPcmFormat(44_100, 2, bytesPerSample = 4, isFloat = true)

    /** A source that hands out [good] blocks, then does [then] on the next read. */
    private class Source(private val good: Int, private val then: () -> FloatArray?) : DesktopSampleSource {
        private var reads = 0
        private val block = FloatArray(2_048) { 0.1f }
        override var sampleCount: Int = 0
            private set

        override fun readSamples(): FloatArray? {
            reads++
            if (reads > good) {
                val out = then()
                sampleCount = out?.size ?: 0
                return out
            }
            sampleCount = block.size
            return block
        }

        override fun seek(micros: Long): Boolean = true
        override fun close() = Unit
    }

    /** Reads until the end, as the audio thread does; fails the test if that takes [limitMs]. */
    private fun readToEnd(ahead: DesktopDecodeAhead, limitMs: Long): Pair<Int, Int> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limitMs)
        var blocks = 0
        var empties = 0
        while (true) {
            assertTrue(System.nanoTime() < deadline, "the reader was still waiting after ${limitMs}ms")
            val started = System.nanoTime()
            val block = ahead.readSamples() ?: return blocks to empties
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 1_000, "one read held the audio thread for ${tookMs}ms")
            if (ahead.sampleCount == 0) empties++ else blocks++
            assertTrue(block.size >= ahead.sampleCount)
        }
    }

    @Test
    fun aDecoderKilledByAnErrorEndsTheTrackInsteadOfHangingThePlayer() {
        // What the log showed: FFmpeg's bindings throwing an out-of-memory error inside the
        // decoder thread, which then died without a word while the audio thread waited on it.
        val ahead = DesktopDecodeAhead(
            source = Source(good = 3) { throw OutOfMemoryError("Physical memory usage is too high") },
            outputFormat = format,
            durationUs = 180_000_000L,
            measuredFormat = null,
        )
        val (blocks, _) = readToEnd(ahead, limitMs = 5_000)
        assertEquals(3, blocks)
        assertTrue(ahead.failed, "a decoder that died must say so, so the song is opened again")
        ahead.close()
    }

    @Test
    fun aFrozenReadHandsBackEmptyBlocksAndThenEndsAsFailed() {
        val release = CountDownLatch(1)
        val ahead = DesktopDecodeAhead(
            source = Source(good = 2) {
                // A download frozen mid-body.
                release.await(60, TimeUnit.SECONDS)
                null
            },
            outputFormat = format,
            durationUs = 180_000_000L,
            measuredFormat = null,
            stallMs = 1_500L,
        )
        try {
            val (blocks, empties) = readToEnd(ahead, limitMs = 6_000)
            assertEquals(2, blocks)
            assertTrue(empties > 0, "while the read is frozen the audio thread must get empty blocks back")
            assertTrue(ahead.failed)
        } finally {
            release.countDown()
            ahead.close()
        }
    }

    @Test
    fun anOrdinaryEndIsNotAFailure() {
        val ahead = DesktopDecodeAhead(Source(good = 5) { null }, format, 1_000_000L, null)
        val (blocks, _) = readToEnd(ahead, limitMs = 5_000)
        assertEquals(5, blocks)
        assertFalse(ahead.failed)
        ahead.close()
    }

    private fun tone(hz: Double, amplitude: Float, seconds: Double = 1.0, rate: Int = 48_000): FloatArray {
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * 2) { i ->
            (amplitude * sin(2 * PI * hz * (i / 2) / rate)).toFloat()
        }
    }

    private fun rms(samples: FloatArray, from: Int = samples.size / 2): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from))
    }

    private fun boosted(input: FloatArray, gain: Float, tone: DesktopVolumeBoost.Tone): FloatArray =
        DesktopBoostProcessor().process(input, input.size, 2, 48_000, gain, tone).copyOf(input.size)

    @Test
    fun sixHundredPercentIsLouderButNeverPastFullScale() {
        val input = tone(440.0, 0.8f, seconds = 2.0)
        for (sound in DesktopVolumeBoost.Tone.entries) {
            val out = boosted(input, 6f, sound)
            val peak = out.maxOf { abs(it) }
            assertTrue(peak <= 0.99f, "$sound at 600% peaked at $peak")
            assertTrue(rms(out) > rms(input), "$sound at 600% was not louder")
        }
    }

    @Test
    fun theBoostOnlyRunsWhenItHasSomethingToDo() {
        val processor = DesktopBoostProcessor()
        assertFalse(processor.needed(1f, DesktopVolumeBoost.Tone.DEFAULT))
        assertFalse(processor.needed(0.4f, DesktopVolumeBoost.Tone.DEFAULT))
        assertTrue(processor.needed(1.5f, DesktopVolumeBoost.Tone.DEFAULT))
        assertTrue(processor.needed(0.4f, DesktopVolumeBoost.Tone.BASS))
    }

    @Test
    fun bassBoostLiftsTheLowsAndVoiceBoostLiftsSpeech() {
        val low = tone(60.0, 0.05f)
        val speech = tone(2_200.0, 0.05f)
        val bassLow = rms(boosted(low, 1f, DesktopVolumeBoost.Tone.BASS)) / rms(low)
        val bassSpeech = rms(boosted(speech, 1f, DesktopVolumeBoost.Tone.BASS)) / rms(speech)
        assertTrue(bassLow > 1.8, "Bass boost raised 60 Hz only ${"%.2f".format(bassLow)}x")
        assertTrue(abs(bassSpeech - 1.0) < 0.1, "Bass boost moved 2.2 kHz by ${"%.2f".format(bassSpeech)}x")

        val voiceSpeech = rms(boosted(speech, 1f, DesktopVolumeBoost.Tone.VOICE)) / rms(speech)
        val voiceLow = rms(boosted(low, 1f, DesktopVolumeBoost.Tone.VOICE)) / rms(low)
        assertTrue(voiceSpeech > 1.5, "Voice boost raised 2.2 kHz only ${"%.2f".format(voiceSpeech)}x")
        assertTrue(voiceLow < 0.85, "Voice boost left 60 Hz at ${"%.2f".format(voiceLow)}x")
    }
}
