package app.oto.desktop

import app.oto.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BitChord's version aligner, on the desktop's own decoder.
 *
 * Two cuts of one song — the album track and its music video — rarely start on the same second:
 * the video has an intro, a skit, a longer fade. This listens to the opening of each, as an
 * energy envelope (how loud, every 10 ms), and slides one over the other to find where they line
 * up. That gives the exact moment to switch to, so the song carries on rather than jumping.
 *
 * The same comparison answers a second question Oto asks of it: whether the other cut is the
 * same recording at all. A lyric video by someone else, a live take or a different song under
 * the same title does not line up with anything, and that is how a wrong video is caught before
 * it plays.
 */
internal object DesktopVersionAligner {

    /** What a comparison found. */
    data class Result(
        /** Where the other cut is, relative to this one: its time = this time + [offsetMs]. */
        val offsetMs: Long?,
        /** How well the two line up, -1 to 1; meaningful only when [measured]. */
        val confidence: Double,
        /** False when the audio could not be fetched or decoded, so nothing is known either way. */
        val measured: Boolean,
    ) {
        /** The two are the same recording, lined up. */
        val matches: Boolean get() = offsetMs != null

        /** The two were compared and are clearly not the same recording. */
        val different: Boolean get() = measured && confidence < DIFFERENT_BELOW
    }

    private const val RATE = 8_000
    private const val HOP_MS = 10.0
    private const val WIN_MS = 40.0

    /** How far apart the two cuts may start: a long video intro is common. */
    private const val MAX_LAG_SECONDS = 60.0

    /** The playing cut's opening that is compared… */
    private const val SOURCE_SECONDS = 50.0

    /** …against this much of the other, so an intro up to [MAX_LAG_SECONDS] still overlaps. */
    private const val TARGET_SECONDS = 110.0

    /** BitChord's bar for "these line up". */
    private const val MIN_CONFIDENCE = 0.40

    /** Below this the two plainly are not the same recording. */
    private const val DIFFERENT_BELOW = 0.22

    /** A comparison never holds a switch longer than this. */
    private const val TIMEOUT_MS = 12_000L

    private val offsets = ConcurrentHashMap<Pair<String, String>, Long>()

    /** A shift already measured for this pair, without going near the network. */
    fun cached(sourceId: String, targetId: String): Long? =
        if (sourceId == targetId) 0L else offsets[sourceId to targetId]

    suspend fun compare(source: Song, target: Song): Result {
        if (source.videoId == target.videoId) return Result(0L, 1.0, measured = true)
        offsets[source.videoId to target.videoId]?.let { return Result(it, 1.0, measured = true) }
        return withTimeoutOrNull(TIMEOUT_MS) { measure(source, target) }
            ?: Result(null, 0.0, measured = false).also {
                DesktopTrackLog.log("align: '${source.title}' -> ${target.videoId} timed out")
            }
    }

    private suspend fun measure(source: Song, target: Song): Result = withContext(Dispatchers.IO) {
        val (sourcePcm, targetPcm) = coroutineScope {
            val a = async { head(source, SOURCE_SECONDS) }
            val b = async { head(target, TARGET_SECONDS) }
            a.await() to b.await()
        }
        if (sourcePcm == null || targetPcm == null) {
            DesktopTrackLog.log("align: could not fetch audio to compare '${source.title}' with ${target.videoId}")
            return@withContext Result(null, 0.0, measured = false)
        }
        val result = compareSamples(sourcePcm, targetPcm)
        DesktopTrackLog.log(
            "align: '${source.title}' -> ${target.videoId}: confidence=${"%.2f".format(result.confidence)}, " +
                "offset=${result.offsetMs ?: "none"}",
        )
        result.offsetMs?.let { offset ->
            offsets[source.videoId to target.videoId] = offset
            offsets[target.videoId to source.videoId] = -offset
        }
        result
    }

    /** The comparison itself, on two openings already decoded to mono floats at [RATE]. */
    internal fun compareSamples(sourcePcm: FloatArray, targetPcm: FloatArray): Result {
        val correlation = crossCorrelate(envelope(sourcePcm), envelope(targetPcm))
        val pinned = correlation.lagFrames == correlation.floor || correlation.lagFrames == correlation.ceiling
        val offset = (correlation.lagFrames * HOP_MS).toLong()
        return if (correlation.confidence >= MIN_CONFIDENCE && !pinned) {
            Result(offset, correlation.confidence, measured = true)
        } else {
            Result(null, correlation.confidence, measured = true)
        }
    }

    internal const val SAMPLE_RATE = RATE

    /** The first [seconds] of [song] as mono floats at [RATE], or null if it cannot be had. */
    private suspend fun head(song: Song, seconds: Double): FloatArray? {
        val stream = DesktopStreamClient.resolve(song).getOrNull() ?: return null
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(
            url = stream.url,
            headers = stream.headers,
            requested = DesktopPcmFormat(RATE, channels = 1, bytesPerSample = 4, isFloat = true),
            windowed = stream.windowedReads,
            transport = stream.transport,
        )
        if (opened.isFailure) {
            decoder.close()
            return null
        }
        return try {
            val wanted = (seconds * RATE).toInt()
            val out = FloatArray(wanted)
            var filled = 0
            while (filled < wanted) {
                val block = decoder.readSamples() ?: break
                val count = min(decoder.sampleCount, wanted - filled)
                if (count <= 0) continue
                block.copyInto(out, filled, 0, count)
                filled += count
            }
            if (filled < RATE * 5) null else out.copyOf(filled)
        } finally {
            decoder.close()
        }
    }

    /** RMS loudness over 40 ms windows every 10 ms — BitChord's envelope. */
    private fun envelope(samples: FloatArray): FloatArray {
        val hop = max(1, (RATE * HOP_MS / 1000.0).toInt())
        val window = max(hop, (RATE * WIN_MS / 1000.0).toInt())
        val frames = max(0, (samples.size - window) / hop)
        val env = FloatArray(frames)
        for (i in 0 until frames) {
            val start = i * hop
            val end = min(samples.size, start + window)
            var sum = 0.0
            for (j in start until end) {
                val s = samples[j]
                sum += s * s
            }
            env[i] = sqrt(sum / (end - start)).toFloat()
        }
        return env
    }

    private data class Correlation(val lagFrames: Int, val confidence: Double, val floor: Int, val ceiling: Int)

    /**
     * Normalised cross-correlation of the two envelopes over every allowed lag; positive lag
     * means the other cut is later. BitChord's prefix-sum and FFT form, unchanged.
     */
    private fun crossCorrelate(source: FloatArray, target: FloatArray): Correlation {
        if (source.isEmpty() || target.isEmpty()) return Correlation(0, 0.0, 0, 0)
        val maxLag = (MAX_LAG_SECONDS * 1000.0 / HOP_MS).toInt()
        val minOverlap = (3.0 * 1000.0 / HOP_MS).toInt()
        val lowest = max(-maxLag, -(source.size - minOverlap))
        val highest = min(maxLag, target.size - minOverlap)
        if (lowest > highest) return Correlation(0, 0.0, 0, 0)

        val cross = crossTerms(source, target)
        val sp = DoubleArray(source.size + 1)
        val sq = DoubleArray(source.size + 1)
        for (i in source.indices) {
            val v = source[i].toDouble()
            sp[i + 1] = sp[i] + v
            sq[i + 1] = sq[i] + v * v
        }
        val tp = DoubleArray(target.size + 1)
        val tq = DoubleArray(target.size + 1)
        for (i in target.indices) {
            val v = target[i].toDouble()
            tp[i + 1] = tp[i] + v
            tq[i + 1] = tq[i] + v * v
        }

        var bestLag = 0
        var best = -1.0
        for (lag in lowest..highest) {
            val s0 = max(0, -lag)
            val t0 = max(0, lag)
            val n = min(source.size - s0, target.size - t0)
            if (n < minOverlap) continue
            val sumS = sp[s0 + n] - sp[s0]
            val sumT = tp[t0 + n] - tp[t0]
            val sumSS = sq[s0 + n] - sq[s0]
            val sumTT = tq[t0 + n] - tq[t0]
            val num = cross[lag + source.size - 1] - sumS * sumT / n
            val den = sqrt((sumSS - sumS * sumS / n) * (sumTT - sumT * sumT / n))
            if (den > 1e-6) {
                val r = num / den
                if (r > best) {
                    best = r
                    bestLag = lag
                }
            }
        }
        return Correlation(bestLag, best, lowest, highest)
    }

    /** Σ source[i]·target[i+lag] for every lag, indexed `lag + source.size - 1`. */
    private fun crossTerms(source: FloatArray, target: FloatArray): DoubleArray {
        val n = source.size
        val m = target.size
        var size = 1
        while (size < n + m) size = size shl 1
        val aRe = DoubleArray(size)
        val aIm = DoubleArray(size)
        for (i in 0 until n) aRe[i] = source[i].toDouble()
        val bRe = DoubleArray(size)
        val bIm = DoubleArray(size)
        for (i in 0 until m) bRe[i] = target[i].toDouble()
        fft(aRe, aIm, inverse = false)
        fft(bRe, bIm, inverse = false)
        for (i in 0 until size) {
            val a = aRe[i]
            val b = -aIm[i]
            val c = bRe[i]
            val d = bIm[i]
            aRe[i] = a * c - b * d
            aIm[i] = a * d + b * c
        }
        fft(aRe, aIm, inverse = true)
        val out = DoubleArray(n + m - 1)
        for (lag in -(n - 1) until m) {
            val bin = ((lag % size) + size) % size
            out[lag + n - 1] = aRe[bin]
        }
        return out
    }

    /** In-place iterative radix-2 FFT; [inverse] divides by the length. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (k in 0 until n) {
                re[k] /= n
                im[k] /= n
            }
        }
    }
}
