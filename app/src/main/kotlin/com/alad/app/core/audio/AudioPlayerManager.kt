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
    }

    private val lock = Any()
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
        createTrackLocked()
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
        Log.d(TAG, "Output devices $reason; rebuilding media AudioTrack")
        createTrackLocked()
    }

    private fun createTrackLocked() {
        releaseTrackLocked()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid minimum AudioTrack buffer size: $minBuffer")
            return
        }
        try {
            val track = AudioTrack(
                attributes,
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build(),
                maxOf(minBuffer * 4, SAMPLE_RATE * 2 / 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                track.release()
                return
            }
            track.setVolume(volume)
            track.play()
            audioTrack = track
            Log.i(TAG, "AudioTrack started; routed device type=${track.routedDevice?.type}")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create playback track", e)
        }
    }

    fun playAudioData(data: ByteArray) = synchronized(lock) {
        if (!started || data.isEmpty()) return@synchronized
        val track = audioTrack ?: run {
            Log.w(TAG, "Gemini audio received but AudioTrack is unavailable")
            return@synchronized
        }
        var offset = 0
        while (offset < data.size && started) {
            val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                Log.e(TAG, "AudioTrack write failed: $written; remaining=${data.size - offset}")
                break
            }
            offset += written
        }
        if (offset != data.size) Log.w(TAG, "Incomplete Gemini audio playback: $offset/${data.size} bytes")
    }

    fun setVolume(value: Float) = synchronized(lock) {
        volume = value.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }

    fun stop() = synchronized(lock) {
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
