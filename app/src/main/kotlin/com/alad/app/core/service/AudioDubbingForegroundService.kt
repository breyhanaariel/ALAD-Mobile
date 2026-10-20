package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.sqrt

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alad_dubbing_channel"
        private const val NOTIFICATION_ID = 101
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "DubbingService"
        
        val isRunning = MutableStateFlow(false)
        val audioAmplitude = MutableStateFlow(0f)
        val connectionStatus = MutableStateFlow("Disconnected")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var langObservationJob: Job? = null
    private var receivedAudioChunks = 0
    private var captureBytes = 0L
    private var responseBytes = 0L
    private var firstCaptureMs = 0L
    private var firstResponseMs = 0L
    private var lastCaptureLogMs = 0L
    private var lastResponseLogMs = 0L
    @Volatile private var lastAudibleCaptureMs = 0L
    @Volatile private var sourceQuiet = false
    @Volatile private var lastResponseReceivedMs = 0L
    @Volatile private var sessionStartedMs = 0L
    private var lastRecoveryMs = 0L
    private var lastActiveSourceMs = 0L
    private var recoveryCount = 0
    private val stallAfterMs = 9000L
    private val recoveryCooldownMs = 30000L
    private var lastQuietLogMs = 0L
    private val silenceThreshold = 0.008f
    private val quietAfterMs = 5000L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultCode != 0 && data != null) {
                    startDubbing(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startDubbing(resultCode: Int, data: Intent) {
        isRunning.value = true
        lastResponseReceivedMs = 0L
        lastActiveSourceMs = 0L
        lastRecoveryMs = 0L
        recoveryCount = 0
        sourceQuiet = false
        connectionStatus.value = "Connecting..."
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        serviceScope.launch {
            val repository = UserPreferencesRepository(applicationContext)
            
            val apiKey = repository.apiKeyFlow.first()
            val targetLang = repository.targetLangFlow.first()
            val sourceLang = repository.sourceLangFlow.first()
            val volumeRatio = repository.volumeRatioFlow.first()
            
            webSocketManager = ALADWebSocketManager(OkHttpClient())
            
            audioPlayerManager = AudioPlayerManager(applicationContext)
            audioPlayerManager?.start()
            audioPlayerManager?.setVolume(volumeRatio)
            
            webSocketManager?.onStatusChanged = { status ->
                connectionStatus.value = status
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, "WS Status: $status", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            
            sessionStartedMs = android.os.SystemClock.elapsedRealtime()
            webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                lastResponseReceivedMs = android.os.SystemClock.elapsedRealtime()
                val now = android.os.SystemClock.elapsedRealtime()
                if (firstResponseMs == 0L) {
                    firstResponseMs = now
                    Log.i(TAG, "Latency response firstAudioMs=$now, sinceFirstCaptureMs=${if (firstCaptureMs > 0L) now - firstCaptureMs else -1L}")
                }
                responseBytes += audioChunk.size
                if (now - lastResponseLogMs >= 2000L) {
                    lastResponseLogMs = now
                    Log.i(TAG, "Latency response elapsedMs=${now - firstResponseMs}, outputPcmMs=${responseBytes * 1000L / 48000L}, bytes=${audioChunk.size}, sinceLastCaptureMs=${if (lastCaptureLogMs > 0L) now - lastCaptureLogMs else -1L}")
                }
                receivedAudioChunks++
                if (receivedAudioChunks % 50 == 0) {
                    Log.d(TAG, "Received 50 more Gemini audio chunks; latest bytes=${audioChunk.size}")
                }
                audioPlayerManager?.playAudioData(audioChunk)
            }
            
            // Preserve the chosen source preference in the connection interface.
            // The current translation API only documents target-language configuration.
            webSocketManager?.connect(apiKey, sourceLang, targetLang)
            
            langObservationJob?.cancel()
            langObservationJob = serviceScope.launch {
                // Observe both settings atomically; reconnect only when the effective
                // translation configuration changes, not for unrelated preferences.
                var previous: Pair<String, String>? = sourceLang to targetLang
                repository.translationSettingsFlow.collect { current ->
                    if (current != previous) {
                        previous = current
                        val currentKey = repository.apiKeyFlow.first()
                        webSocketManager?.disconnect()
                        webSocketManager?.connect(currentKey, current.first, current.second)
                    }
                }
            }

            audioCaptureManager = AudioCaptureManager()
            val appUid = applicationInfo.uid
            
            mediaProjection?.let { projection ->
                audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (firstCaptureMs == 0L) {
                        firstCaptureMs = now
                        Log.i(TAG, "Latency capture firstAudioMs=$now")
                    }
                    captureBytes += pcmData.size
                    if (now - lastCaptureLogMs >= 2000L) {
                        lastCaptureLogMs = now
                        Log.i(TAG, "Latency capture elapsedMs=${now - firstCaptureMs}, inputPcmMs=${captureBytes * 1000L / 32000L}, bytes=${pcmData.size}")
                    }
                    // Calculate RMS amplitude
                    var sum = 0.0
                    for (i in pcmData.indices step 2) {
                        if (i + 1 < pcmData.size) {
                            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i+1].toInt() shl 8)
                            val signedSample = sample.toShort().toFloat()
                            sum += signedSample * signedSample
                        }
                    }
                    val rms = if (pcmData.isNotEmpty()) sqrt(sum / (pcmData.size / 2)).toFloat() else 0f
                    // Normalize and boost slightly for better visual effect (x3)
                    val normalized = (rms / 32767f * 3f).coerceIn(0f, 1f)
                    if (normalized >= silenceThreshold) {
                        lastActiveSourceMs = now
                        lastAudibleCaptureMs = now
                        if (sourceQuiet) {
                            sourceQuiet = false
                            Log.i(TAG, "Silence diagnostics: source resumed; translation output enabled")
                        }
                    } else if (lastAudibleCaptureMs > 0L && now - lastAudibleCaptureMs >= quietAfterMs) {
                        if (!sourceQuiet) {
                            sourceQuiet = true
                            Log.w(TAG, "Silence diagnostics: sustained source quiet for ${now - lastAudibleCaptureMs}ms; suppressing late output")
                        }
                    }
                    // Only restart after a sustained response stall during active source audio.
                    // Do not restart for quiet scenes or short gaps between dialogue.
                    val responseBaseline = if (lastResponseReceivedMs > sessionStartedMs) lastResponseReceivedMs else sessionStartedMs
                    if (lastActiveSourceMs > 0L &&
                        now - lastActiveSourceMs < 1000L &&
                        now - responseBaseline >= stallAfterMs &&
                        now - sessionStartedMs >= stallAfterMs &&
                        now - lastRecoveryMs >= recoveryCooldownMs) {
                        lastRecoveryMs = now
                        recoveryCount++
                        Log.w(TAG, "Recovery: stalled translated audio for ${now - responseBaseline}ms with active source; reconnecting session; attempt=$recoveryCount")
                        val manager = webSocketManager
                        if (manager != null) {
                            manager.disconnect()
                            sessionStartedMs = now
                            lastResponseReceivedMs = 0L
                            manager.connect(apiKey, sourceLang, targetLang)
                        }
                    }
                    webSocketManager?.sendAudioData(pcmData)
                    
                    // Apply low-pass filter for smooth animation
                    val current = audioAmplitude.value
                    audioAmplitude.value = current * 0.5f + normalized * 0.5f
                }
            }
        }
    }

    private fun stopDubbing() {
        isRunning.value = false
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        
        audioPlayerManager?.stop()
        audioPlayerManager = null
        
        webSocketManager?.disconnect()
        webSocketManager = null
        
        audioAmplitude.value = 0f
        connectionStatus.value = "Disconnected"
        
        langObservationJob?.cancel()
        langObservationJob = null
        
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.value) {
            stopDubbing()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Dubbing Service"
            val descriptionText = "Capturing and streaming audio for dubbing"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALAD Live Dubbing")
            .setContentText("Connected and dubbing...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
