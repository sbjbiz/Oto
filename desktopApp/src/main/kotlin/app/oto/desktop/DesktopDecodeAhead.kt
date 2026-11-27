package app.oto.desktop

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What [DesktopDecodeAhead] reads from: a decoder, or a stand-in for one in tests. */
internal interface DesktopSampleSource {
    /** The next block as interleaved floats, or null at the end. The array may be reused. */
    fun readSamples(): FloatArray?

    /** How much of the array [readSamples] returned is actually this block. */
    val sampleCount: Int

    fun seek(micros: Long): Boolean

    fun close()
}

/**
 * Decodes a track a few seconds ahead of the speakers, on a thread of its own.
 *
 * The audio thread used to call the decoder directly, and the decoder reads straight from the
 * network: a YouTube stream stops for a whole 512 KiB range request every half-minute or so, and a
 * lossless stream waits on its socket whenever the connection hiccups. WASAPI holds only a fifth
 * of a second, so any read slower than that was heard as a gap of a few milliseconds. Here those
 * waits land on this thread while the audio thread plays what was decoded earlier.
 *
 * Only this thread touches [source] once constructed — seeks and the close are handed to it — so
 * the native decoder never sees two threads.
 *
 * The audio thread never waits on this one for long: a read that finds nothing hands back an
 * empty block after [READ_WAIT_MS], so it can get back to its commands, and a decoder that has
 * produced nothing for [STALL_MS] — or died — ends the track as [failed]. Waiting without a limit
 * is how one frozen download, or a decoder killed by a memory error, left the whole player
 * unable to play anything until Oto was restarted.
 */
internal class DesktopDecodeAhead(
    private val source: DesktopSampleSource,
    val outputFormat: DesktopPcmFormat,
    val durationUs: Long?,
    val measuredFormat: DesktopStreamFormat?,
    /** How far ahead to decode, in samples across all channels. */
    private val aheadSamples: Int = outputFormat.sampleRate * outputFormat.channels * AHEAD_SECONDS,
    name: String = "Oto-Decode",
    /** Nothing decoded for this long, with nothing ending it, is a stuck decoder. */
    private val stallMs: Long = STALL_MS,
) {

    private val lock = ReentrantLock()
    /** Signalled when there is something for the audio thread to take. */
    private val ready = lock.newCondition()
    /** Signalled when the worker has room, a seek, or a close to act on. */
    private val wake = lock.newCondition()

    private val queue = ArrayDeque<FloatArray>()
    private var queuedSamples = 0
    private var ended = false
    private var closed = false
    private var pendingSeekUs: Long? = null
    /** Bumped by every seek, so a block decoded before it is never played after it. */
    private var generation = 0
    /** Whether a block has been handed out since the last seek; a wait before the first is a load. */
    private var started = false

    /** How much of the array [readSamples] returned is actually this block. */
    var sampleCount: Int = 0
        private set

    /** Whether the track ended because the decoder failed or stalled, not because it was over. */
    @Volatile
    var failed: Boolean = false
        private set

    /** When the worker last produced anything, or was told to start afresh. */
    private var progressAt = System.nanoTime()

    private val worker = Thread(::decodeLoop, name).apply {
        isDaemon = true
        // Above the UI, below the audio thread itself.
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    /**
     * The next decoded block, waiting for the decoder only if it has fallen behind; an empty
     * block (with [sampleCount] 0) when it is still behind after a short wait; null at the end.
     */
    fun readSamples(): FloatArray? = lock.withLock {
        if (queue.isEmpty() && !ended && !closed) {
            val waitedFrom = System.nanoTime()
            var left = TimeUnit.MILLISECONDS.toNanos(READ_WAIT_MS)
            while (queue.isEmpty() && !ended && !closed && left > 0) left = ready.awaitNanos(left)
            val waitedMs = (System.nanoTime() - waitedFrom) / 1_000_000
            if (queue.isEmpty() && !ended && !closed) {
                val stalledMs = (System.nanoTime() - progressAt) / 1_000_000
                if (stalledMs < stallMs) {
                    sampleCount = 0
                    return EMPTY
                }
                DesktopTrackLog.log("decode-ahead: the decoder has produced nothing for ${stalledMs / 1_000}s; ending the track")
                failed = true
                ended = true
                // Whatever the stuck read eventually brings back belongs to no one.
                generation++
                wake.signal()
            } else if (started && waitedMs >= STARVED_LOG_MS && queue.isNotEmpty()) {
                DesktopTrackLog.log("decode-ahead ran dry: the audio thread waited ${waitedMs}ms for the decoder")
            }
        }
        val block = queue.removeFirstOrNull()
        if (block == null) {
            sampleCount = 0
            return null
        }
        queuedSamples -= block.size
        started = true
        wake.signal()
        sampleCount = block.size
        block
    }

    /** Moves to [micros]; whatever was decoded ahead of the old position is thrown away. */
    fun seek(micros: Long): Boolean {
        lock.withLock {
            generation++
            queue.clear()
            queuedSamples = 0
            ended = false
            failed = false
            started = false
            progressAt = System.nanoTime()
            pendingSeekUs = micros
            wake.signal()
        }
        return true
    }

    /** Stops decoding; the decoder itself is closed by the worker, once any read in flight returns. */
    fun close() {
        lock.withLock {
            closed = true
            queue.clear()
            queuedSamples = 0
            wake.signal()
            ready.signalAll()
        }
    }

    private fun decodeLoop() {
        try {
            while (true) {
                var seekTo: Long? = null
                val decodingFor = lock.withLock {
                    while (!closed && pendingSeekUs == null && (ended || queuedSamples >= aheadSamples)) {
                        wake.await()
                    }
                    if (closed) return
                    seekTo = pendingSeekUs
                    pendingSeekUs = null
                    // A stall is counted from here, the start of this read, never from a time the
                    // worker spent idle on a full queue during a pause.
                    progressAt = System.nanoTime()
                    generation
                }
                val micros = seekTo
                if (micros != null) {
                    if (!source.seek(micros)) DesktopTrackLog.log("decode-ahead: seek to ${micros / 1_000}ms failed")
                    continue
                }

                var broke = false
                val decoded = try {
                    source.readSamples()?.copyOf(source.sampleCount)
                } catch (failure: InterruptedException) {
                    throw failure
                } catch (failure: Throwable) {
                    // Errors too, not only exceptions: an out-of-memory from the native side used
                    // to kill this thread without a word, and the audio thread waited on it forever.
                    DesktopTrackLog.log("decode-ahead: decoder failed, ending the track: ${failure.message ?: failure.javaClass.simpleName}")
                    broke = true
                    null
                }
                lock.withLock {
                    // A seek came in while this block was being read; it belongs to the old position.
                    if (decodingFor != generation) return@withLock
                    // Close came in while this block was being read. Committing it would leave the
                    // queue non-empty after close, and the caller's very next readSamples() — which
                    // the contract says is null — would return this block instead. Seen on Windows
                    // CI, where scheduling lets the caller's close win this race consistently;
                    // Linux's timing hides it. The block dies with the closed reader.
                    if (closed) return@withLock
                    progressAt = System.nanoTime()
                    if (decoded == null) {
                        ended = true
                        if (broke) failed = true
                    } else if (decoded.isNotEmpty()) {
                        queue.addLast(decoded)
                        queuedSamples += decoded.size
                    }
                    ready.signalAll()
                }
            }
        } catch (_: InterruptedException) {
            // Closing the app; the decoder goes with the process.
        } catch (failure: Throwable) {
            DesktopTrackLog.log("decode-ahead: worker stopped: ${failure.message ?: failure.javaClass.simpleName}")
            lock.withLock { failed = true }
        } finally {
            // However this thread ends, the reader must hear of it rather than wait for a block
            // that will never come.
            lock.withLock {
                ended = true
                ready.signalAll()
            }
            runCatching { source.close() }
        }
    }

    private companion object {
        /** Long enough to ride out a slow range request or a reconnect. */
        const val AHEAD_SECONDS = 8

        /** Waits shorter than this are ordinary scheduling, not a network stall worth logging. */
        const val STARVED_LOG_MS = 20L

        /** The longest one read waits before handing the audio thread an empty block. */
        const val READ_WAIT_MS = 200L

        const val STALL_MS = 25_000L

        val EMPTY = FloatArray(0)
    }
}
