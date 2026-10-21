package com.alad.app.core.network

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class ALADWebSocketManager(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    var onBinaryMessageReceived: ((ByteArray) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    @Volatile private var isSetupComplete = false
    private var userInitiatedClose = false
    private var lastServerError: String? = null
    private var lastQueueLogMs = 0L
    @Volatile private var connectionGeneration = 0L

    fun connect(apiKey: String, sourceLang: String, targetLang: String) {
        val generation = ++connectionGeneration
        synchronized(pendingAudioLock) { pendingAudio.clear() }
        val finalUrl = "$GEMINI_WS_URL?key=$apiKey"
        val request = Request.Builder().url(finalUrl).build()
        isSetupComplete = false
        userInitiatedClose = false
        lastServerError = null
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration) return
                Log.d(TAG, "Connected to Gemini Live API")
                onStatusChanged?.invoke("Connected")
                sendGeminiSetup(targetLang)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                Log.d(TAG, "Gemini response received (${text.length} chars)")
                try {
                    val json = JSONObject(text)
                    
                    if (json.has("serverContent") || json.has("server_content")) {
                        val serverContent = json.optJSONObject("serverContent") ?: json.optJSONObject("server_content")
                        if (serverContent != null) {
                            val inputText = (serverContent.optJSONObject("inputTranscription") ?: serverContent.optJSONObject("input_transcription"))?.optString("text").orEmpty()
                            val outputText = (serverContent.optJSONObject("outputTranscription") ?: serverContent.optJSONObject("output_transcription"))?.optString("text").orEmpty()
                            if (inputText.isNotBlank()) onInputTranscript?.invoke(inputText)
                            if (outputText.isNotBlank()) onOutputTranscript?.invoke(outputText)
                        }
                        if (serverContent != null && (serverContent.has("modelTurn") || serverContent.has("model_turn"))) {
                            val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
                            val parts = modelTurn?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)
                                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                                    if (inlineData != null) {
                                        val base64Data = inlineData.getString("data")
                                        val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                        onBinaryMessageReceived?.invoke(audioBytes)
                                    }
                                }
                            }
                        }
                    } else if (json.has("setupComplete") || json.has("setup_complete")) {
                        synchronized(pendingAudioLock) {
                            isSetupComplete = true
                            pendingAudio.forEach { sendAudioNow(it) }
                            pendingAudio.clear()
                        }
                        onStatusChanged?.invoke("Gemini Ready")
                    } else if (json.has("error")) {
                        val errMessage = json.getJSONObject("error").optString("message", "Unknown error")
                        Log.e(TAG, "API Error: $errMessage")
                        lastServerError = errMessage
                        onStatusChanged?.invoke("Gemini rejected request: $errMessage")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                onMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                isSetupComplete = false
                Log.d(TAG, "Closing: $code $reason")
                if (!userInitiatedClose) {
                    val detail = lastServerError ?: reason.ifBlank { "server closed connection" }
                    onStatusChanged?.invoke("Gemini closed connection ($code): $detail")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration) return
                isSetupComplete = false
                Log.e(TAG, "WebSocket error", t)
                if (!userInitiatedClose) onStatusChanged?.invoke("Connection failed: ${t.message ?: response?.message ?: "network error"}")
            }
        })
    }
    
    private fun sendGeminiSetup(targetLang: String) {
        // The raw v1beta BidiGenerateContent wire format places transcription
        // configuration on setup, NOT inside generationConfig. The latter
        // causes a WebSocket 1007 "Invalid JSON payload" response.
        // Transcripts are not requested: ALAD is an audio-only dubbing app.
        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-3.5-live-translate-preview")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLang)
                        put("echoTargetLanguage", false)
                    })
                })
            })
        }
        if (webSocket?.send(setupPayload.toString()) != true) {
            onStatusChanged?.invoke("Unable to send Gemini setup request")
        }
    }

    private val pendingAudio = mutableListOf<String>()
    private val pendingAudioLock = Any()

    fun sendAudioData(pcmData: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        synchronized(pendingAudioLock) {
            if (!isSetupComplete) {
                pendingAudio.add(base64Audio)
                if (pendingAudio.size > 8) pendingAudio.removeAt(0)
            } else {
                sendAudioNow(base64Audio)
            }
        }
    }
    
    private fun sendAudioNow(base64Audio: String) {
        val inputPayload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                })
            })
        }
        val socket = webSocket ?: return
        val sent = socket.send(inputPayload.toString())
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastQueueLogMs >= 2000L) {
            lastQueueLogMs = now
            Log.i(TAG, "Input transport diagnostics: websocketQueuedBytes=${socket.queueSize()}, sendAccepted=$sent, setupComplete=$isSetupComplete")
        }
        if (!sent) Log.w(TAG, "Gemini input audio send rejected; websocketQueuedBytes=${socket.queueSize()}")
    }

    fun disconnect() {
        connectionGeneration++
        userInitiatedClose = true
        isSetupComplete = false
        synchronized(pendingAudioLock) { pendingAudio.clear() }
        webSocket?.close(1000, "User requested stop")
        webSocket = null
        onStatusChanged?.invoke("Disconnected")
    }
}
