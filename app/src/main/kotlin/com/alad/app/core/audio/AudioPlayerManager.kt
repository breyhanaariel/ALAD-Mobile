package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Streams Gemini's 24 kHz mono PCM output using the system media route.
 * Never forces a specific Bluetooth device: Android selects the active media output.
 */
class AudioPlayerManager(private val context: Context) {
    companion object {
        private const val TAG = "ALADAudioOutput"
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2 // mono 16-bit PCM
        // Keep a bounded queue: the Bluetooth hardware buffer cannot be reduced here.
        private const val MAX_PENDING_AUDIO_MS = 750L
        private const val TARGET_TRACK_BUFFER_MS = 120
        private const val MAX_PENDING_BYTES = BYTES_PER_SECOND * MAX_PENDING_AUDIO_MS / 1000L
    }

    private val lock = Any()
    private val playbackQueue = LinkedBlockingDeque<ByteArray>()
    private val pendingBytes = AtomicLong(0)
    private val queueLock = Any()
    private var lastBacklogWarningMs = 0L
    private var lastMetricsMs = 0L
    private var totalWrittenFrames = 0L
    @Volatile private var playbackActive = false
    private var playbackThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audioManager: AudioManager? = null
    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var started = false
    private var volume = 1f

    fun start() = synchronized(lock) {
        if (started) return@synchronized
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    Log.d(TAG, "Audio focus changed: $change")
                }
                .build()
            focusRequest = request
            Log.d(TAG, "Audio focus result: ${manager.requestAudioFocus(request)}")
        }
        started = true
        totalWrittenFrames = 0L
        playbackActive = true
        clearQueue()
        createTrackLocked()
        playbackThread = Thread({ playbackLoop() }, "ALAD-PCM-Playback").apply { start() }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onRouteChanged("added")
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onRouteChanged("removed")
            }
        }
        deviceCallback = callback
        manager.registerAudioDeviceCallback(callback, handler)
    }

    private fun onRouteChanged(reason: String) = synchronized(lock) {
        if (!started) return@synchronized
        // AudioTrack follows the system media route automatically. Recreating it
        // for unrelated device events flushes audio and can cause audible gaps.
        Log.i(TAG, "Output devices $reason; route=${audioTrack?.routedDevice?.type}")
    }

    private fun createTrackLocked() {
        releaseTrackLocked()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid minimum AudioTrack buffer size: $minBuffer")
            return
        }
        val bufferBytes = maxOf(minBuffer, BYTES_PER_SECOND * TARGET_TRACK_BUFFER_MS / 1000)
        val manager = audioManager
        val bluetoothPresent = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } == true

        fun buildTrack(lowLatency: Boolean): AudioTrack =
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    else AudioTrack.PERFORMANCE_MODE_NONE)
                .build()

        var track: AudioTrack? = null
        var lowLatencyEnabled = false
        if (bluetoothPresent) {
            try {
                val candidate = buildTrack(true)
                if (candidate.state == AudioTrack.STATE_INITIALIZED &&
                    candidate.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
                    track = candidate
                    lowLatencyEnabled = true
                } else {
                    Log.i(TAG, "Bluetooth low-latency mode unavailable; using normal playback")
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth low-latency setup failed; using normal playback", e)
            }
        }
        if (track == null) {
            try {
                track = buildTrack(false)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot create normal AudioTrack", e)
                return
            }
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                track.release()
                return
            }
            track.setVolume(volume)
            track.play()
            audioTrack = track
            Log.i(TAG, "AudioTrack started; route=${track.routedDevice?.type}; minBufferBytes=$minBuffer; actualBufferBytes=${track.bufferSizeInFrames * 2}; capacityFrames=${track.bufferCapacityInFrames}; bluetoothPresent=$bluetoothPresent; lowLatencyEnabled=$lowLatencyEnabled; performanceMode=${track.performanceMode}")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start playback track", e)
            track.release()
        }
    }

    /**
     * WebSocket callbacks must never wait for Bluetooth AudioTrack writes.
     * Queue chunks for a dedicated playback thread instead.
     */
    private fun clearQueue() = synchronized(queueLock) {
        playbackQueue.clear()
        pendingBytes.set(0)
    }

    /**
     * Bound the *duration* of queued 24 kHz PCM, not the number of packets.
     * Dropping the oldest queued dialogue is preferable to drifting 10+ seconds
     * behind a live video. Does not discard audio already inside AudioTrack.
     */
    fun playAudioData(data: ByteArray) {
        if (!playbackActive || data.isEmpty()) return
        synchronized(queueLock) {
            if (!playbackActive) return
            // A single oversized response can exceed the entire latency budget.
            val incoming = if (data.size > MAX_PENDING_BYTES) {
                data.copyOfRange(data.size - MAX_PENDING_BYTES.toInt(), data.size)
            } else data
            while (pendingBytes.get() + incoming.size > MAX_PENDING_BYTES) {
                val dropped = playbackQueue.pollFirst() ?: break
                pendingBytes.addAndGet(-dropped.size.toLong())
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBacklogWarningMs >= 2000L) {
                    Log.w(TAG, "Bluetooth/live playback backlog: dropping stale audio; pending=${pendingBytes.get() * 1000 / BYTES_PER_SECOND}ms")
                    lastBacklogWarningMs = now
                }
            }
            playbackQueue.offerLast(incoming)
            pendingBytes.addAndGet(incoming.size.toLong())
        }
    }

    private fun playbackLoop() {
        while (playbackActive) {
            try {
                val data = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                synchronized(queueLock) {
                    pendingBytes.addAndGet(-data.size.toLong())
                }
                synchronized(lock) {
                    if (!started) return@synchronized
                    val track = audioTrack ?: run {
                        Log.w(TAG, "Gemini audio received but AudioTrack is unavailable")
                        return@synchronized
                    }
                    var offset = 0
                    while (offset < data.size && playbackActive) {
                        val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) {
                            Log.e(TAG, "AudioTrack write failed: $written; remaining=${data.size - offset}")
                            break
                        }
                        offset += written
                        totalWrittenFrames += written / 2L
                    }
                    if (offset != data.size) Log.w(TAG, "Incomplete Gemini audio playback: $offset/${data.size} bytes")
                }
                // Log bounded pending duration rather than packet count.
                val pendingMs = pendingBytes.get() * 1000 / BYTES_PER_SECOND
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastMetricsMs >= 2000L) {
                    lastMetricsMs = now
                    synchronized(lock) {
                        audioTrack?.let { currentTrack ->
                            val framesWritten = currentTrack.playbackHeadPosition.toLong() and 0xffffffffL
                            Log.i(TAG, "PCM diagnostics: queuedMs=$pendingMs, writtenFrames=$totalWrittenFrames, playbackHeadFrames=$framesWritten, submittedMinusPlayedMs=${(totalWrittenFrames - framesWritten).coerceAtLeast(0L) * 1000L / SAMPLE_RATE}, trackBufferFrames=${currentTrack.bufferSizeInFrames}, route=${currentTrack.routedDevice?.type}")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Playback worker failed", e)
            }
        }
    }

    fun setVolume(value: Float) = synchronized(lock) {
        volume = value.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }

    fun stop() {
        playbackActive = false
        clearQueue()
        playbackThread?.interrupt()
        playbackThread = null
        synchronized(lock) {
        if (!started) return@synchronized
        started = false
        deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        releaseTrackLocked()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
        audioManager = null
        }
    }

    private fun releaseTrackLocked() {
        val old = audioTrack
        audioTrack = null
        if (old != null) {
            try { old.pause(); old.flush() } catch (e: IllegalStateException) {
                Log.w(TAG, "Track already stopped", e)
            }
            old.release()
        }
    }
}
