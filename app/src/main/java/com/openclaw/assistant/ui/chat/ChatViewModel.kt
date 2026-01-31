package com.openclaw.assistant.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    val partialText: String = "" // For real-time speech transcription
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val settings = SettingsRepository.getInstance(application)
    private val apiClient = OpenClawClient()
    private val speechManager = SpeechRecognizerManager(application)
    private val ttsManager = TTSManager(application)

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Add user message
        addMessage(text, isUser = true)
        _uiState.update { it.copy(isThinking = true) }

        viewModelScope.launch {
            try {
                val result = apiClient.sendMessage(
                    webhookUrl = settings.webhookUrl,
                    message = text,
                    sessionId = settings.sessionId,
                    authToken = settings.authToken.takeIf { it.isNotBlank() }
                )

                result.fold(
                    onSuccess = { response ->
                        val responseText = response.getResponseText() ?: "No response"
                        addMessage(responseText, isUser = false)
                        _uiState.update { it.copy(isThinking = false) }
                        speak(responseText)
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isThinking = false, error = error.message) }
                        addMessage("Error: ${error.message}", isUser = false)
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isThinking = false, error = e.message) }
            }
        }
    }

    private var lastInputWasVoice = false
    private var listeningJob: kotlinx.coroutines.Job? = null

    fun startListening() {
        if (_uiState.value.isListening) return
        
        lastInputWasVoice = true // Mark as voice input
        listeningJob?.cancel() // Cancel existing
        
        // Stop TTS if speaking
        ttsManager.stop()

        _uiState.update { it.copy(isListening = true, partialText = "") }

        listeningJob = viewModelScope.launch {
            speechManager.startListening("ja-JP").collect { result ->
                when (result) {
                    is SpeechResult.PartialResult -> {
                        _uiState.update { it.copy(partialText = result.text) }
                    }
                    is SpeechResult.Result -> {
                        _uiState.update { it.copy(isListening = false, partialText = "") }
                        sendMessage(result.text)
                    }
                    is SpeechResult.Error -> {
                        _uiState.update { it.copy(isListening = false, error = result.message) }
                        // Only stop loop on fatal errors? Or restart?
                        // For now, let's treat error as stop
                        lastInputWasVoice = false 
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopListening() {
        lastInputWasVoice = false // User manually stopped
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun speak(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }
            val success = ttsManager.speak(text)
            _uiState.update { it.copy(isSpeaking = false) }
            
            // If it was a voice conversation, continue listening
            if (success && lastInputWasVoice) {
                // Wait for TTS to fully release audio focus
                kotlinx.coroutines.delay(800)
                
                // Restart listening
                startListening() 
            }
        }
    }
    
    fun stopSpeaking() {
        lastInputWasVoice = false // Stop loop if manually stopped
        ttsManager.stop()
        _uiState.update { it.copy(isSpeaking = false) }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val message = ChatMessage(text = text, isUser = isUser)
        _uiState.update { state ->
            state.copy(messages = state.messages + message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
        ttsManager.shutdown()
    }
}
