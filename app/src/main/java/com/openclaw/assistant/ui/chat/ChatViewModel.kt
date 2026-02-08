package com.openclaw.assistant.ui.chat

import android.app.Application
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "ChatViewModel"

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val partialText: String = "", // For real-time speech transcription
    val isConnected: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val settings = SettingsRepository.getInstance(application)
    private val apiClient = OpenClawClient()
    private val speechManager = SpeechRecognizerManager(application)

    // TTS will be set from Activity
    private var tts: TextToSpeech? = null
    private var isTTSReady = false
    private var isResponseCollectorRunning = false

    init {
        // Start connection and response collection flow immediately
        connectAndCollect()
    }

    private fun connectAndCollect() {
        if (settings.isConfigured() && !apiClient.isConnected() && !isResponseCollectorRunning) {
            
            // 1. Connect
            val connectResult = apiClient.connect(
                settings.webhookUrl, // This field now holds the Gateway WS URL
                settings.authToken.takeIf { it.isNotBlank() }
            )
            
            connectResult.onFailure { error ->
                _uiState.update { it.copy(error = "Connection failed: ${error.message}", isConnected = false) }
            }
            
            if (connectResult.isSuccess) {
                _uiState.update { it.copy(isConnected = true, error = null) }
            }
        }
        
        // 2. Start collecting responses if not already running
        if (!isResponseCollectorRunning) {
            isResponseCollectorRunning = true
            viewModelScope.launch {
                apiClient.responseFlow.collect { response ->
                    handleIncomingResponse(response)
                }
            }.invokeOnCompletion { 
                isResponseCollectorRunning = false 
            }
        }
    }

    private fun handleIncomingResponse(response: com.openclaw.assistant.api.OpenClawResponse) {
        // Handle connection status/error messages first
        if (response.response == "CONNECTED") {
            // Initial connection message (ignore or log)
            return
        }

        if (!response.error.isNullOrBlank()) {
            _uiState.update { it.copy(isThinking = false, error = response.error, isConnected = apiClient.isConnected()) }
            addMessage("System Error: ${response.error}", isUser = false)
            return
        }
        
        // Handle successful AI response
        val responseText = response.getResponseText() ?: "No response"
        addMessage(responseText, isUser = false)
        _uiState.update { it.copy(isThinking = false) }
        
        if (settings.ttsEnabled) {
            speak(responseText)
        } else if (lastInputWasVoice && settings.continuousMode) {
            // If TTS is disabled but we're in continuous mode, restart listening directly
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                startListening()
            }
        } else {
            // Resume hotword only if we aren't continuing
            sendResumeBroadcast()
        }
    }

    /**
     * ActivityからTTSを設定する
     */
    fun setTTS(textToSpeech: TextToSpeech) {
        Log.e(TAG, "setTTS called")
        tts = textToSpeech
        isTTSReady = true
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        // Ensure connection is attempted before sending
        if (!apiClient.isConnected()) {
            connectAndCollect()
            if (!apiClient.isConnected()) {
                _uiState.update { it.copy(error = "Gateway is not connected. Check settings.") }
                return
            }
        }

        // Add user message
        addMessage(text, isUser = true)
        _uiState.update { it.copy(isThinking = true) }

        // Send message asynchronously (fire and forget)
        apiClient.sendMessage(
            message = text,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() }
        )
        
        // The response will be handled by the collect block
    }

    private var lastInputWasVoice = false
    private var listeningJob: Job? = null

    fun startListening() {
        Log.e(TAG, "startListening() called, isListening=${_uiState.value.isListening}")
        if (_uiState.value.isListening) return

        // Ensure connection is ready if we are starting via voice
        if (!apiClient.isConnected()) {
            connectAndCollect()
            if (!apiClient.isConnected()) {
                _uiState.update { it.copy(error = "Gateway is not connected. Check settings.") }
                // Don't proceed with listening if not connected
                return
            }
        }

        // Pause Hotword Service to prevent microphone conflict
        sendPauseBroadcast()

        lastInputWasVoice = true // Mark as voice input
        listeningJob?.cancel()

        // Stop TTS if speaking
        tts?.stop()

        listeningJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for TTS resource release before starting mic
            delay(500)

            try {
                while (isActive && !hasActuallySpoken) {
                    Log.e(TAG, "Starting speechManager.startListening(), isListening=true")
                    _uiState.update { it.copy(isListening = true, partialText = "") }

                    speechManager.startListening("ja-JP").collect { result ->
                        Log.e(TAG, "SpeechResult: $result")
                        when (result) {
                            is SpeechResult.PartialResult -> {
                                _uiState.update { it.copy(partialText = result.text) }
                            }
                            is SpeechResult.Result -> {
                                hasActuallySpoken = true
                                _uiState.update { it.copy(isListening = false, partialText = "") }
                                sendMessage(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH
                                
                                if (isTimeout && settings.continuousMode && elapsed < 5000) {
                                    Log.d(TAG, "Speech timeout within 5s window ($elapsed ms), retrying loop...")
                                    // Just fall through to next while iteration
                                    _uiState.update { it.copy(isListening = false) }
                                } else {
                                    // Permanent error or out of time
                                    _uiState.update { it.copy(isListening = false, error = result.message) }
                                    lastInputWasVoice = false
                                    hasActuallySpoken = true // Break the while loop
                                }
                            }
                            else -> {}
                        }
                    }
                    
                    if (!hasActuallySpoken) {
                        delay(300) // Small gap between retries
                    }
                }
            } finally {
                // If the loop finishes (e.g. error or spoken), and we are NOT continuing to speak/think immediately,
                // we might want to resume hotword...
                if (!lastInputWasVoice) {
                    sendResumeBroadcast()
                }
            }
        }
    }

    fun stopListening() {
        lastInputWasVoice = false // User manually stopped
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false) }
        sendResumeBroadcast()
    }

    private fun speak(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }
            
            val success = if (isTTSReady && tts != null) {
                speakWithTTS(text)
            } else {
                Log.e(TAG, "TTS not ready, skipping speech")
                false
            }
            
            _uiState.update { it.copy(isSpeaking = false) }

            // If it was a voice conversation and continuous mode is on, continue listening
            if (success && lastInputWasVoice && settings.continuousMode) {
                // Explicit cleanup and wait for TTS to fully release audio focus
                speechManager.destroy()
                kotlinx.coroutines.delay(1000) // Increased from 800ms for more reliable cleanup

                // Restart listening (which will pause hotword again if needed, though it should still be paused)
                startListening()
            } else {
                // Conversation ended, hotword resume handled by handleIncomingResponse
            }
        }
    }

    private suspend fun speakWithTTS(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()
        
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            
            override fun onDone(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $errorCode")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
        
        tts?.setOnUtteranceProgressListener(listener)
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.e(TAG, "TTS speak result: $result")
        
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed immediately")
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        
        continuation.invokeOnCancellation {
            tts?.stop()
        }
    }
    
    fun stopSpeaking() {
        lastInputWasVoice = false // Stop loop if manually stopped
        tts?.stop()
        _uiState.update { it.copy(isSpeaking = false) }
        sendResumeBroadcast()
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val message = ChatMessage(text = text, isUser = isUser)
        _uiState.update { state ->
            state.copy(messages = state.messages + message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        apiClient.disconnect()
        speechManager.destroy()
        sendResumeBroadcast()
    }

    private fun sendPauseBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
}
