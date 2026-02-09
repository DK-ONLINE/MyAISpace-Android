package com.openclaw.assistant.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenClaw WebSocket Client (Node Protocol)
 */
class OpenClawClient {

    companion object {
        private const val TAG = "OpenClawClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Use a large timeout for the WS
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected: Boolean = false
    private val callId = AtomicLong(System.currentTimeMillis())

    // Channel to pass incoming messages (responses) back to the caller
    private val _responseChannel = Channel<OpenClawResponse>(Channel.UNLIMITED)
    val responseFlow = _responseChannel.receiveAsFlow()

    fun isConnected(): Boolean = isConnected

    fun connect(gatewayUrl: String, authToken: String?): Result<Boolean> {
        if (isConnected) return Result.success(true)
        
        try {
            // Note: We MUST convert http/https to ws/wss for OkHttp
            val wsUrl = gatewayUrl.replace("http", "ws")
            Log.d(TAG, "Connecting to WebSocket: $wsUrl")

            val requestBuilder = Request.Builder().url(wsUrl)
            
            if (!authToken.isNullOrEmpty()) {
                requestBuilder.header("Authorization", "Bearer $authToken")
            }
            
            val request = requestBuilder.build()
            webSocket = client.newWebSocket(request, WebSocketListenerImpl())
            return Result.success(true)
        } catch (e: Exception) {
            isConnected = false
            webSocket = null
            Log.e(TAG, "Connection attempt failed", e)
            return Result.failure(e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket")
        webSocket?.close(1000, "User disconnect")
        isConnected = false
        webSocket = null
    }

    /**
     * Send message using the OpenClaw session.send command.
     */
    fun sendMessage(
        message: String,
        sessionId: String,
        authToken: String? = null
    ) {
        if (!isConnected || webSocket == null) {
            Log.e(TAG, "Cannot send message: Not connected")
            _responseChannel.trySend(OpenClawResponse(error = "Gateway not connected. Please check settings."))
            return
        }
        
        val id = callId.incrementAndGet().toString()

        // Construct the message matching OpenClaw Node protocol
        val data = JsonObject().apply {
            addProperty("message", message)
            addProperty("sessionKey", sessionId)
            addProperty("agentId", "main")
        }

        val nodeMessage = JsonObject().apply {
            addProperty("id", id)
            addProperty("kind", "session.send")
            add("data", data)
        }

        val json = gson.toJson(nodeMessage)
        Log.d(TAG, "Sending WebSocket frame: $json")
        val success = webSocket?.send(json) ?: false
        if (!success) {
            Log.e(TAG, "Failed to queue WebSocket frame")
            _responseChannel.trySend(OpenClawResponse(error = "Failed to send message frame"))
        }
    }

    suspend fun testConnection(
        gatewayUrl: String,
        authToken: String?
    ): Result<Boolean> = connect(gatewayUrl, authToken)

    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket Connected")
            isConnected = true
            _responseChannel.trySend(OpenClawResponse(response = "CONNECTED"))
            // REMOVED immediate sendMessage here to avoid 1008 error
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                val kind = json.get("kind")?.asString

                if (kind == "session.message" || kind == "agent.turn") {
                    val data = json.getAsJsonObject("data")
                    val message = data.get("message")?.asString
                    _responseChannel.trySend(OpenClawResponse(response = message))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing incoming message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket Closing: $code / $reason")
            isConnected = false
            _responseChannel.trySend(OpenClawResponse(error = "Disconnected ($code: $reason)"))
            this@OpenClawClient.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket Failure", t)
            isConnected = false
            val errorMsg = response?.message ?: t.message ?: "Unknown error"
            _responseChannel.trySend(OpenClawResponse(error = "Connection failed: $errorMsg"))
            this@OpenClawClient.webSocket = null
        }
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    fun getResponseText(): String? = response
}
