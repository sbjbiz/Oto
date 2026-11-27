package app.oto.desktop

import app.oto.playback.PeakLimiter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Volume boost: Settings → Audio output, off until turned on.
 *
 * Off, Oto's volume stops at 100% as it always has. On, it goes up to [MAX_VOLUME] (600%), and
 * the player offers three sounds under its volume bar — Default, Voice boost and Bass boost —
 * which sit on top of whatever the equalizer is doing. Turning it off brings both back: the
 * volume to at most 100%, the sound to Default.
 */
internal object DesktopVolumeBoost {
    /** 600%. */
    const val MAX_VOLUME = 6f

    enum class Tone(val key: String) { DEFAULT("default"), VOICE("voice"), BASS("bass") }

    private val persistence = DesktopPersistence()
    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    private val _tone = MutableStateFlow(
        if (_enabled.value) Tone.entries.firstOrNull { it.key == persistence.string(KEY_TONE, "default") } ?: Tone.DEFAULT
        else Tone.DEFAULT,
    )

    private val _toneKey = MutableStateFlow(_tone.value.key)

    val enabled: StateFlow<Boolean> = _enabled
    val tone: StateFlow<Tone> = _tone

    /** [tone] by its key, as the shared player reads it. */
    val toneKey: StateFlow<String> = _toneKey

    /** The loudest the volume may go right now: 600% with boost on, 100% without. */
    val maxVolume: Float get() = if (_enabled.value) MAX_VOLUME else 1f

    fun setEnabled(on: Boolean) {
        persistence.saveBoolean(KEY_ENABLED, on)
        _enabled.value = on
        if (!on) setTone(Tone.DEFAULT)
    }

    fun setTone(tone: Tone) {
        persistence.saveString(KEY_TONE, tone.key)
        _tone.value = tone
        _toneKey.value = tone.key
    }

    fun setTone(key: String): Unit = setTone(Tone.entries.firstOrNull { it.key == key } ?: Tone.DEFAULT)

    private const val KEY_ENABLED = "volume_boost"
    private const val KEY_TONE = "volume_boost_tone"
}

/**
 * The boost itself, on the way to the device: the chosen sound's filters, then the volume past
 * 100%, then a limiter that turns down only the loudest moments so a boosted song gets louder
 * without crackling. Used by the output only while it has something to do — at 100% and
 * Default, samples pass through untouched, exactly as before.
 */
internal class DesktopBoostProcessor {
    private val limiter = PeakLimiter()
    private var rate = 0
    private var channels = 0
    private var builtFor: DesktopVolumeBoost.Tone? = null
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var scratch = FloatArray(0)

    /** Forgets the filters' history, for when the processor comes back after time unused. */
    fun reset() {
        builtFor = null
    }

    /** Whether [gain] and [tone] need this at all. */
    fun needed(gain: Float, tone: DesktopVolumeBoost.Tone): Boolean =
        gain > 1.0001f || tone != DesktopVolumeBoost.Tone.DEFAULT

    /**
     * [count] interleaved samples of [channelCount] channels, shaped and boosted into a buffer of
     * this processor's; the device then gets them at unity gain.
     */
    fun process(samples: FloatArray, count: Int, channelCount: Int, sampleRate: Int, gain: Float, tone: DesktopVolumeBoost.Tone): FloatArray {
        if (sampleRate != rate || channelCount != channels || tone != builtFor) {
            rate = sampleRate
            channels = channelCount.coerceAtLeast(1)
            builtFor = tone
            filters = Array(channels) { designFor(tone, sampleRate.toDouble()) }
            limiter.configure(sampleRate)
        }
        if (scratch.size < count) scratch = FloatArray(count)
        val out = scratch
        val frames = count / channels
        for (frame in 0 until frames) {
            val base = frame * channels
            var peak = 0f
            for (c in 0 until channels) {
                var x = samples[base + c]
                for (filter in filters[c]) x = filter.process(x)
                x *= gain
                out[base + c] = x
                val a = abs(x)
                if (a > peak) peak = a
            }
            val g = limiter.gainFor(peak)
            if (g < 1f) for (c in 0 until channels) out[base + c] *= g
        }
        // A partial frame at the end, if any, just passes through scaled.
        for (i in frames * channels until count) out[i] = samples[i] * gain
        return out
    }

    private fun designFor(tone: DesktopVolumeBoost.Tone, fs: Double): Array<Biquad> = when (tone) {
        DesktopVolumeBoost.Tone.DEFAULT -> emptyArray()
        // Fuller low end: a shelf under ~120 Hz.
        DesktopVolumeBoost.Tone.BASS -> arrayOf(
            Biquad.lowShelf(fs, 120.0, gainDb = 7.0),
        )
        // Voices forward: less boom, more presence where speech is understood.
        DesktopVolumeBoost.Tone.VOICE -> arrayOf(
            Biquad.lowShelf(fs, 180.0, gainDb = -4.0),
            Biquad.peaking(fs, 2_200.0, q = 0.9, gainDb = 5.0),
            Biquad.peaking(fs, 4_500.0, q = 1.2, gainDb = 2.0),
        )
    }

    /** One second-order filter (RBJ cookbook), direct form I. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Float): Float {
            val x = input.toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y.toFloat()
        }

        companion object {
            fun lowShelf(fs: Double, f0: Double, gainDb: Double, slope: Double = 1.0): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2 * PI * f0 / fs
                val cosW = cos(w0)
                val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
                val sqrtA2alpha = 2 * sqrt(a) * alpha
                val a0 = (a + 1) + (a - 1) * cosW + sqrtA2alpha
                return Biquad(
                    b0 = a * ((a + 1) - (a - 1) * cosW + sqrtA2alpha) / a0,
                    b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0,
                    b2 = a * ((a + 1) - (a - 1) * cosW - sqrtA2alpha) / a0,
                    a1 = -2 * ((a - 1) + (a + 1) * cosW) / a0,
                    a2 = ((a + 1) + (a - 1) * cosW - sqrtA2alpha) / a0,
                )
            }

            fun peaking(fs: Double, f0: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2 * PI * f0 / fs
                val alpha = sin(w0) / (2 * q)
                val cosW = cos(w0)
                val a0 = 1 + alpha / a
                return Biquad(
                    b0 = (1 + alpha * a) / a0,
                    b1 = -2 * cosW / a0,
                    b2 = (1 - alpha * a) / a0,
                    a1 = -2 * cosW / a0,
                    a2 = (1 - alpha / a) / a0,
                )
            }
        }
    }
}
